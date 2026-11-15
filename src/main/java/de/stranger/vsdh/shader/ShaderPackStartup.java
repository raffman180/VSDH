package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.option.OptionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Pack selection, at startup from config/vsdh.json and later from the Shader settings page. A pack is
 * read and compiled on a worker; the render thread swaps the world host only while no level is loaded.
 */
public final class ShaderPackStartup {
    private static final org.slf4j.Logger LOGGER=LoggerFactory.getLogger("VSDH-Shaders");
    private static final SystemToast.SystemToastId TOAST=new SystemToast.SystemToastId();
    /** The newest selection not yet installed; a later request replaces it. */
    private static volatile Request request;
    private static boolean listening;
    /** The selection last asked for (installed or pending). */
    private static volatile Selection requested=Selection.OFF;
    /** The selection the installed world host shows. */
    private static volatile Selection active=Selection.OFF;

    private ShaderPackStartup() {}

    /** What a player picked: pack name (blank for none), profile, option overrides and the DH programs switch. */
    public record Selection(String name,String profile,Map<String,String> options,boolean distant) {
        public static final Selection OFF=new Selection("","",Map.of(),true);
        public Selection {
            distant=distant&&de.stranger.vsdh.Vsdh.distantHorizons(); // DH programs need DH installed
            name=name==null?"":name.strip();
            profile=profile==null?"":profile.strip();
            options=options==null?Map.of():Map.copyOf(options);
            if(name.isEmpty()) { profile="";options=Map.of();distant=true; }
        }
        public boolean off() { return name.isEmpty(); }
    }

    private static final class Request {
        final Selection selection;
        final CompletableFuture<Prepared> pending; // null: switch to the standard renderer
        boolean waitingForMenu;
        Request(Selection selection,CompletableFuture<Prepared> pending) { this.selection=selection;this.pending=pending; }
    }

    /** A prepared pack; shadowFrom/shadowTo differ when the shadow map was made smaller to fit the GPU. */
    record Prepared(WorldPackSet set,int shadowFrom,int shadowTo,String packFile,boolean budgetKnown,PackOptionsView view) {
        boolean shadowReduced() { return shadowTo!=shadowFrom; }
    }

    private record Opened(WorldPackSet set,List<String> shadowResolutions,PackOptionsView view) {}

    /**
     * Prepared packs of this session, newest use last. Preparing a large pack takes many seconds (Eclipse: about 18 s
     * of translation and compilation on a worker); the result holds no GPU objects, so switching back to a pack that
     * was prepared before, with the same file, profile and options, is immediate. {@code -Dvsdh.packCache=0} turns it
     * off; the number is how many packs are kept (default 3).
     */
    private static final int PACK_CACHE=Math.max(0,Integer.getInteger("vsdh.packCache",3));
    private static final java.util.LinkedHashMap<PackPreparationKey,Preparation> PREPARATIONS=new java.util.LinkedHashMap<>(8,.75f,true);
    /** One low-priority worker for packs prepared ahead (see {@link #warm}); requests never wait in its queue. */
    private static final java.util.concurrent.ExecutorService WARM=java.util.concurrent.Executors.newSingleThreadExecutor(task->{
        var thread=new Thread(task,"VSDH shaderpack warm-up");
        thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static final java.util.concurrent.locks.ReentrantLock PREPARING=new java.util.concurrent.locks.ReentrantLock(true); // fair: a request does not wait behind repeated warm-ups

    /** Drops the cached preparation of this selection, so the next one runs again (shadow budget now known). */
    private static void forget(Path directory,Selection selection) {
        try {
            var key=key(directory,PackFileFallback.resolve(directory,selection.name()),selection);
            synchronized(PREPARATIONS) { PREPARATIONS.remove(key); }
        } catch(IOException|RuntimeException unreadable) {
            // Nothing cached under a key that cannot be built.
        }
    }

    private static final class Preparation {
        final CompletableFuture<Prepared> future=new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable work;
        Preparation(java.util.concurrent.Callable<Prepared> compute) {
            work=()->{
                // One pack at a time: the translator and its switches were never meant to run for two packs at once.
                PREPARING.lock();
                try { future.complete(compute.call()); }
                catch(IOException failure) { future.completeExceptionally(new CompletionException(failure)); }
                catch(Throwable failure) { future.completeExceptionally(failure); }
                finally { PREPARING.unlock(); }
            };
        }
        /** Runs the work on the calling thread unless someone already started it. */
        void runOnce() { if(started.compareAndSet(false,true))work.run(); }
    }

    private static PackPreparationKey key(Path directory,String packFile,Selection selection) throws IOException {
        Path path=resolvePack(directory,packFile);
        long size=Files.isRegularFile(path)?Files.size(path):-1;
        // The machine description (vendor, renderer) decides vendor branches of the pack: part of the key, so a result
        // built before the device was known is never reused afterwards.
        return new PackPreparationKey(packFile,size,Files.getLastModifiedTime(path).toMillis(),selection.profile(),selection.options(),
                selection.distant(),Boolean.getBoolean("vsdh.reduceTrig"),Boolean.getBoolean("vsdh.flatVolumes"),
                String.valueOf(dev.vitrail.pack.option.EngineDefines.machine()));
    }

    /** Directory packs are edited in place (their own mtime does not change): never cached, prepared on every request. */
    private static boolean cacheable(Path directory,String packFile) throws IOException {
        return !Files.isDirectory(resolvePack(directory,packFile));
    }

    /** The cached or new preparation of this selection; not started yet when new. */
    private static Preparation preparation(Path directory,Selection selection) throws IOException {
        String packFile=PackFileFallback.resolve(directory,selection.name());
        var key=key(directory,packFile,selection);
        synchronized(PREPARATIONS) {
            var known=PREPARATIONS.get(key);
            if(known!=null && !known.future.isCompletedExceptionally())return known;
            var created=new Preparation(()->compute(directory,packFile,selection));
            if(PACK_CACHE>0 && cacheable(directory,packFile)) {
                PREPARATIONS.put(key,created);
                var eldest=PREPARATIONS.entrySet().iterator();
                while(PREPARATIONS.size()>PACK_CACHE && eldest.hasNext()){eldest.next();eldest.remove();}
            }
            return created;
        }
    }

    /**
     * Prepares a pack ahead on a low-priority worker so that choosing it later switches at once. Does nothing for the
     * standard renderer, for an unreadable pack or with the cache off; a failure is reported when the pack is chosen.
     */
    public static void warm(Selection selection) {
        if(selection.off() || PACK_CACHE==0)return;
        try {
            describeMachine(); // before compiling: vendor branches of the pack read it (review 15.11.)
            Path directory=FMLPaths.GAMEDIR.get().resolve("shaderpacks");
            if(!cacheable(directory,PackFileFallback.resolve(directory,selection.name())))return;
            var preparation=preparation(directory,selection);
            if(!preparation.started.get())WARM.execute(()->{
                // A pack the player asked for goes first: a warm-up that has not started yields while a request is open.
                var open=request;
                if(open!=null && open.pending!=null && !open.pending.isDone() && open.pending!=preparation.future)return;
                try {
                    // Few workers at the lowest priority: nobody waits for a pack prepared ahead.
                    PreparePool.background(()->{ preparation.runOnce();return null; });
                } catch(Exception impossible) {
                    // runOnce reports through the preparation's future.
                }
            });
        } catch(IOException|RuntimeException unreadable) {
            // Chosen later, the same error reaches the player through the normal path.
        }
    }

    private static final String SHADOW_RESOLUTION="shadowMapResolution";

    private static CompletableFuture<Prepared> prepare(Path directory,Selection selection) {
        Preparation preparation;
        try {
            preparation=preparation(directory,selection);
        } catch(IOException|RuntimeException failure) {
            return CompletableFuture.failedFuture(new CompletionException(failure));
        }
        // The player waits: a warm-up that is running (this pack or the one before it) gets every compile worker.
        PreparePool.urgent(true);
        preparation.future.whenComplete((prepared,failure)->{
            var open=request;
            if(open==null || open.pending==null || open.pending.isDone())PreparePool.urgent(false);
        });
        // A pack that waits in the warm-up queue starts now, on its own worker.
        if(!preparation.started.get())Thread.ofVirtual().name("VSDH shaderpack preparation").start(preparation::runOnce);
        // The settings page lists the options of the pack that was asked for, also when it came from the cache.
        preparation.future.thenAccept(prepared->{ if(prepared.view()!=null)PackOptionsView.remember(prepared.view()); });
        return preparation.future;
    }

    private static Prepared compute(Path directory,String packFile,Selection selection) throws IOException {
        // A newer version may have replaced the configured file (v1.0 -> v1.0.1): use it, keep the saved choice.
        if(!packFile.equals(selection.name()))LOGGER.warn("Shader pack {} not found; using {} instead",selection.name(),packFile);
        ShaderDiskCache.ensure();
        // One preparation at a time (PREPARING), so these tallies belong to this pack.
        PrepareClock.reset();
        dev.vitrail.glsl.LoadClock.reset();
        SpirvCache.resetCounters();
        dev.vitrail.glsl.TranslationCache.reset();
        long started=System.nanoTime();
        var first=open(directory,packFile,selection,selection.options(),true);
        LOGGER.info("Shader pack {} prepared in {} ms",packFile,(System.nanoTime()-started)/1_000_000);
        LOGGER.info("Shader pack {} preparation: {} compile workers; compiled programs {} from disk, {} compiled; translations {} from disk of {}; "
                        +"flattening {} ms, translation {} ms (worker time); phases: {}",packFile,PreparePool.THREADS,
                SpirvCache.hits(),SpirvCache.misses(),dev.vitrail.glsl.TranslationCache.served(),dev.vitrail.glsl.LoadClock.translated(),
                dev.vitrail.glsl.LoadClock.expansionMillis(),dev.vitrail.glsl.LoadClock.translationMillis(),PrepareClock.report());
        int resolution=largestShadowResolution(first.set());
        long budget=preparationShadowBudget();
        if(budget<=0 || largestShadowBytes(first.set())<=budget)return new Prepared(first.set(),resolution,resolution,packFile,budget>0,first.view());
        // Too large for this GPU: try the pack's own smaller values of shadowMapResolution, largest first,
        // instead of dropping the whole pack at world join. The player's saved options stay unchanged.
        for(int smaller:ShadowResolutionFallback.smallerResolutions(first.shadowResolutions(),resolution)) {
            var options=new HashMap<>(selection.options());
            options.put(SHADOW_RESOLUTION,Integer.toString(smaller));
            var retry=open(directory,packFile,selection,options,false);
            if(largestShadowBytes(retry.set())<=budget) {
                LOGGER.warn("Shadow map of {} reduced from {} to {} to fit {} MiB of GPU memory",
                        selection.name(),resolution,smaller,budget>>20);
                return new Prepared(retry.set(),resolution,smaller,packFile,true,first.view());
            }
        }
        return new Prepared(first.set(),resolution,resolution,packFile,true,first.view()); // the world join then reports the limit
    }

    private static Opened open(Path directory,String packFile,Selection selection,Map<String,String> options,boolean first) throws IOException {
        try(var pack=OpenedPack.open(resolvePack(directory,packFile),parseOptions(options),selection.profile())) {
            for (var entry : selection.options().entrySet()) {
                var option = pack.options().get(entry.getKey())
                        .orElseThrow(() -> new IOException("Unknown shader option " + entry.getKey()));
                if (option.hasValueList() && !option.values().contains(entry.getValue().strip()))
                    throw new IOException("Unsupported value for shader option " + entry.getKey());
            }
            // The settings page lists this pack's options from the same opening (no second read).
            PackOptionsView view=null;
            if(first) {
                try {
                    view=PackOptionsView.read(pack,selection.name(),selection.profile(),language());
                } catch(RuntimeException unreadable) {
                    LOGGER.warn("Cannot list the options of {}: {}",selection.name(),unreadable.toString());
                }
            }
            // The driver's sin/cos, as under Iris on the same GPU. The translator's reduced
            // software sine (-Dvsdh.reduceTrig=true) cost 0.6 ms per frame (Complementary, 1440p).
            dev.vitrail.glsl.GlslTranslator.reduceTrig(Boolean.getBoolean("vsdh.reduceTrig"));
            // Real 3D textures for the volumes a pack ships, sampled trilinearly by the hardware as
            // under Iris; -Dvsdh.flatVolumes=true restores the 2D atlas with a shader helper.
            dev.vitrail.glsl.GlslTranslator.nativeVolumes(!Boolean.getBoolean("vsdh.flatVolumes"));
            var values=pack.options().get(SHADOW_RESOLUTION).map(dev.vitrail.pack.option.PackOption::values).orElse(List.of());
            return new Opened(WorldPackSet.prepare(pack,selection.distant()),values,view);
        }
    }

    private static int largestShadowResolution(WorldPackSet set) {
        int largest=0;
        for(var plan:set.dimensions().values())
            if(plan.shadow()!=null)largest=Math.max(largest,plan.directives().shadowMapResolution());
        return largest;
    }

    private static long largestShadowBytes(WorldPackSet set) {
        long largest=0;
        for(var plan:set.dimensions().values())largest=Math.max(largest,NativeWorldShadows.estimateBytes(plan));
        return largest;
    }

    /** The share of GPU memory shadows may take; 0 while the device is not known yet (then nothing is reduced). */
    private static long preparationShadowBudget() {
        try {
            var memory=net.vulkanmod.vulkan.memory.MemoryManager.getInstance();
            return memory==null?0:NativeWorldShadows.shadowBudgetBytes(memory.getDeviceMemoryMB());
        } catch(RuntimeException|LinkageError notReady) {
            return 0;
        }
    }



    private static String language() {
        try {
            var mc=Minecraft.getInstance();
            if(mc!=null && mc.options!=null && mc.options.languageCode!=null)return mc.options.languageCode;
        } catch(RuntimeException ignored) {}
        return "en_us";
    }

    /** Startup: the configured pack, if any. Runs on the mod-loading thread. */
    public static void start(String configuredName,String profile,Map<String,String> configuredOptions,boolean distant) {
        listen();
        var selection=new Selection(configuredName,profile,configuredOptions,distant);
        if(selection.off())return;
        describeMachine();
        submit(selection);
    }

    /**
     * Settings page: switches to {@code selection} (blank name: the standard renderer). Takes effect at
     * once on the title screen, otherwise when the player next leaves the world. Asking again for what
     * is already shown or pending does nothing.
     */
    public static void request(Selection selection) {
        listen();
        if(selection.equals(requested))return;
        if(selection.off()) {
            requested=selection;
            request=new Request(selection,null);
            LOGGER.info("Vulkan shaderpack switched off");
            return;
        }
        describeMachine();
        submit(selection);
        notify(Minecraft.getInstance(),"preparing",Component.literal(selection.name()));
    }

    /** The selection last asked for; equals the installed one once no request is pending. */
    public static Selection requested() { return requested; }

    /** The selection the installed world host shows (blank name: standard renderer). */
    public static Selection active() { return active; }

    /** True while a selection waits for preparation or for the player to leave the world. */
    public static boolean pending() { return request!=null; }

    private static void submit(Selection selection) {
        requested=selection;
        request=new Request(selection,prepare(FMLPaths.GAMEDIR.get().resolve("shaderpacks"),selection));
        LOGGER.info("Preparing Vulkan shaderpack {}",selection.name());
    }

    private static synchronized void listen() {
        if(listening)return;
        listening=true;
        NeoForge.EVENT_BUS.addListener(ShaderPackStartup::tick);
    }

    /**
     * The machine a pack sees through MC_GL_VENDOR_* / MC_GL_RENDERER_* and MC_OS_*, in the words
     * Iris's OpenGL strings would use for the same device: packs pick vendor-specific paths on them
     * (Complementary on AMD, Photon and Solas on Intel, Mellow on Radeon). Before this every pack
     * saw "other". Vulkan is initialised before mods construct, so the device is known here.
     */
    private static String lastMachine;

    static synchronized void describeMachine() {
        var device=net.vulkanmod.vulkan.device.DeviceManager.device;
        String vendor="",renderer="";
        if(device!=null) {
            int id=net.vulkanmod.vulkan.device.DeviceManager.deviceProperties!=null
                    ?net.vulkanmod.vulkan.device.DeviceManager.deviceProperties.vendorID():0;
            vendor=switch(id){
                case 0x10DE->"NVIDIA Corporation";
                case 0x1002->"ATI Technologies Inc.";
                case 0x8086->"Intel";
                default->device.vendorIdString==null?"":device.vendorIdString;
            };
            renderer=device.deviceName==null?"":device.deviceName;
        }
        String osName=System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT);
        var os=osName.startsWith("windows")?dev.vitrail.pack.option.EngineDefines.Os.WINDOWS
                :osName.startsWith("mac")?dev.vitrail.pack.option.EngineDefines.Os.MAC
                :osName.contains("linux")?dev.vitrail.pack.option.EngineDefines.Os.LINUX
                :dev.vitrail.pack.option.EngineDefines.Os.OTHER;
        dev.vitrail.pack.option.EngineDefines.machine(new dev.vitrail.pack.option.EngineDefines.Environment(
                dev.vitrail.pack.option.EngineDefines.DEFAULT_MC_VERSION,os,vendor,renderer,4,false,Map.of(),java.util.List.of()));
        String described=vendor+"|"+renderer+"|"+os;
        if(!described.equals(lastMachine))LOGGER.info("Shaderpack machine: vendor '{}', renderer '{}', os {}",vendor,renderer,os);
        lastMachine=described;
    }

    private static Map<String,OptionValue> parseOptions(Map<String,String> options) throws IOException {
        var parsed = new HashMap<String,OptionValue>();
        for (var entry : options.entrySet()) {
            String name = entry.getKey(), value = entry.getValue();
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || value == null || !value.strip().matches("[A-Za-z0-9_.+-]+"))
                throw new IOException("Invalid shader option key or value: " + name);
            parsed.put(name, OptionValue.parse(value));
        }
        return parsed;
    }

    static Path resolvePack(Path directory,String name) throws IOException {
        Path relative=Path.of(name);
        if(relative.getRoot()!=null || relative.getNameCount()!=1 || !relative.toString().equals(name)
                || name.equals(".") || name.equals("..")) {
            throw new IOException("shaderPack must be a single file or directory name in shaderpacks");
        }
        Path root=directory.toRealPath();
        Path path=root.resolve(relative).toRealPath();
        if(!path.startsWith(root) || path.equals(root))
            throw new IOException("shaderPack must resolve inside the shaderpacks directory");
        if(!Files.isDirectory(path) && !(Files.isRegularFile(path) && name.toLowerCase(Locale.ROOT).endsWith(".zip")))
            throw new IOException("shaderPack must be a ZIP or a directory");
        return path;
    }

    /**
     * Pipelines are built lazily in the first frames that draw with a pack, not at installation. The counters are
     * marked when a pack is installed and reported once, when pipelines were created since and none for a while.
     */
    private static String pipelineReportPack;
    private static long pipelineReportCount,pipelineReportNanos;

    private static void markPackPipelines(String name) {
        pipelineReportPack=name;
        pipelineReportCount=net.vulkanmod.vulkan.shader.PipelineCacheStore.createdCount();
        pipelineReportNanos=net.vulkanmod.vulkan.shader.PipelineCacheStore.createdNanos();
    }

    private static void reportPackPipelines() {
        long count=net.vulkanmod.vulkan.shader.PipelineCacheStore.createdCount()-pipelineReportCount;
        if(count<=0 || !net.vulkanmod.vulkan.shader.PipelineCacheStore.quiet())return;
        LOGGER.info("Pack pipelines of {}: {} created in {} ms (cache: {} KiB loaded)",pipelineReportPack,count,
                (net.vulkanmod.vulkan.shader.PipelineCacheStore.createdNanos()-pipelineReportNanos)/1_000_000,
                net.vulkanmod.vulkan.shader.PipelineCacheStore.loadedBytes()>>10);
        pipelineReportPack=null;
    }

    private static void tick(ClientTickEvent.Post event) {
        if(pipelineReportPack!=null)reportPackPipelines();
        var current=request;
        if(current==null || current.pending!=null && !current.pending.isDone())return;
        var mc=Minecraft.getInstance();
        boolean failed=current.pending!=null && current.pending.isCompletedExceptionally();
        String name=current.selection.name();
        if(!failed && current.pending!=null && request==current) {
            var prepared=current.pending.join();
            if(ShadowResolutionFallback.needsRecheck(prepared.budgetKnown(),largestShadowBytes(prepared.set()),preparationShadowBudget())) {
                LOGGER.info("Shadow map of {} exceeds this GPU's shadow budget; preparing it again with the device known",name);
                forget(FMLPaths.GAMEDIR.get().resolve("shaderpacks"),current.selection); // not the cached result again
                request=new Request(current.selection,prepare(FMLPaths.GAMEDIR.get().resolve("shaderpacks"),current.selection));
                return;
            }
        }
        if(mc.level!=null && !failed && LIVE_SWITCH) {
            if(request!=current)return;
            request=null;
            switchInWorld(mc,current);
            return;
        }
        if(mc.level!=null && !failed) {
            if(!current.waitingForMenu) {
                current.waitingForMenu=true;
                notify(mc,"waiting",current.selection.off()
                        ?Component.translatable("vsdh.shader.standard"):Component.literal(name));
            }
            return;
        }
        if(request!=current)return;
        request=null;
        if(failed) {
            // The installed host (pack or standard renderer) stays; the player may pick again.
            try { current.pending.join(); } catch(RuntimeException failure) {
                LOGGER.error("Could not prepare Vulkan shaderpack "+name,failure);
            }
            requested=active;
            notify(mc,"failed",Component.translatable("vsdh.shader.failed.details"));
            return;
        }
        var previous=WorldShaderSession.current();
        try {
            if(previous!=null)previous.uninstall();
            active=Selection.OFF;
            pipelineReportPack=null;
            if(current.pending==null) {
                LOGGER.info("Vulkan shaderpack off; standard renderer active");
                notify(mc,"off",Component.translatable("vsdh.shader.standard"));
                return;
            }
            var prepared=current.pending.join();
            WorldShaderSession.install(prepared.set());
            active=current.selection;
            markPackPipelines(name);
            LOGGER.info("Vulkan shaderpack ready: {}",name);
            announce(mc,prepared,name);
        } catch(RuntimeException failure) {
            LOGGER.error("Could not activate Vulkan shaderpack "+name,failure);
            requested=active;
            notify(mc,"failed",Component.translatable("vsdh.shader.failed.details"));
        }
    }

    /** -Dvsdh.liveShaderSwitch=false: apply a pack change only after leaving the world, as before 14.11.2026. */
    private static final boolean LIVE_SWITCH=!"false".equals(System.getProperty("vsdh.liveShaderSwitch"));

    /** A finished preparation (or "off") while a world is loaded: swap at once and rebuild the terrain, as Iris does. */
    private static void switchInWorld(Minecraft mc,Request current) {
        String name=current.selection.name();
        try {
            Prepared prepared=current.pending==null?null:current.pending.join();
            pipelineReportPack=null;
            long pipelineCount=net.vulkanmod.vulkan.shader.PipelineCacheStore.createdCount();
            long pipelineNanos=net.vulkanmod.vulkan.shader.PipelineCacheStore.createdNanos();
            WorldShaderSession.replaceInWorld(prepared==null?null:prepared.set());
            active=current.selection;
            if(prepared!=null) { // count what the switch itself already built
                pipelineReportPack=name;pipelineReportCount=pipelineCount;pipelineReportNanos=pipelineNanos;
            }
            if(prepared==null) {
                LOGGER.info("Vulkan shaderpack off in the world; standard renderer active");
                notify(mc,"off",Component.translatable("vsdh.shader.standard"));
            } else {
                LOGGER.info("Vulkan shaderpack switched in the world: {}",name);
                announce(mc,prepared,name);
            }
        } catch(RuntimeException failure) {
            LOGGER.error("Could not switch the Vulkan shaderpack in the world: "+name,failure);
            // replaceInWorld may have released the old host before the new one failed.
            active=WorldShaderSession.current()==null?Selection.OFF:active;
            requested=active;
            notify(mc,"failed",Component.translatable("vsdh.shader.failed.details"));
        }
    }

    /** The ready toast; says when another version of the pack or a smaller shadow map was used. */
    private static void announce(Minecraft mc,Prepared prepared,String name) {
        if(!prepared.packFile().equals(name)) {
            notify(mc,"replaced",Component.translatable("vsdh.shader.replaced.details",prepared.packFile(),name));
        } else if(prepared.shadowReduced()) {
            notify(mc,"shadowReduced",Component.translatable("vsdh.shader.shadowReduced.details",
                    prepared.shadowFrom(),prepared.shadowTo()));
        } else {
            notify(mc,"ready",Component.literal(name));
        }
    }

    private static void notify(Minecraft mc,String state,Component details) {
        SystemToast.addOrUpdate(mc.getToasts(),TOAST,Component.translatable("vsdh.shader."+state),details);
    }
}
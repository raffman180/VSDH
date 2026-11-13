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
        final CompletableFuture<WorldPackSet> pending; // null: switch to the standard renderer
        boolean waitingForMenu;
        Request(Selection selection,CompletableFuture<WorldPackSet> pending) { this.selection=selection;this.pending=pending; }
    }

    private static CompletableFuture<WorldPackSet> prepare(Path directory,Selection selection) {
        return CompletableFuture.supplyAsync(()->{
            try(var pack=OpenedPack.open(resolvePack(directory,selection.name()),parseOptions(selection.options()),selection.profile())) {
                for (var entry : selection.options().entrySet()) {
                    var option = pack.options().get(entry.getKey())
                            .orElseThrow(() -> new IOException("Unknown shader option " + entry.getKey()));
                    if (option.hasValueList() && !option.values().contains(entry.getValue().strip()))
                        throw new IOException("Unsupported value for shader option " + entry.getKey());
                }
                // The settings page lists this pack's options from the same opening (no second read).
                try {
                    PackOptionsView.remember(PackOptionsView.read(pack,selection.name(),selection.profile(),language()));
                } catch(RuntimeException unreadable) {
                    LOGGER.warn("Cannot list the options of {}: {}",selection.name(),unreadable.toString());
                }
                // The driver's sin/cos, as under Iris on the same GPU. The translator's reduced
                // software sine (-Dvsdh.reduceTrig=true) cost 0.6 ms per frame (Complementary, 1440p).
                dev.vitrail.glsl.GlslTranslator.reduceTrig(Boolean.getBoolean("vsdh.reduceTrig"));
                // Real 3D textures for the volumes a pack ships, sampled trilinearly by the hardware as
                // under Iris; -Dvsdh.flatVolumes=true restores the 2D atlas with a shader helper.
                dev.vitrail.glsl.GlslTranslator.nativeVolumes(!Boolean.getBoolean("vsdh.flatVolumes"));
                return WorldPackSet.prepare(pack,selection.distant());
            } catch(IOException failure) {
                throw new CompletionException(failure);
            }
        }, command->Thread.ofVirtual().name("VSDH shaderpack preparation").start(command));
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

    private static void tick(ClientTickEvent.Post event) {
        var current=request;
        if(current==null || current.pending!=null && !current.pending.isDone())return;
        var mc=Minecraft.getInstance();
        boolean failed=current.pending!=null && current.pending.isCompletedExceptionally();
        String name=current.selection.name();
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
            if(current.pending==null) {
                LOGGER.info("Vulkan shaderpack off; standard renderer active");
                notify(mc,"off",Component.translatable("vsdh.shader.standard"));
                return;
            }
            WorldShaderSession.install(current.pending.join());
            active=current.selection;
            LOGGER.info("Vulkan shaderpack ready: {}",name);
            notify(mc,"ready",Component.literal(name));
        } catch(RuntimeException failure) {
            LOGGER.error("Could not activate Vulkan shaderpack "+name,failure);
            requested=active;
            notify(mc,"failed",Component.translatable("vsdh.shader.failed.details"));
        }
    }

    private static void notify(Minecraft mc,String state,Component details) {
        SystemToast.addOrUpdate(mc.getToasts(),TOAST,Component.translatable("vsdh.shader."+state),details);
    }
}

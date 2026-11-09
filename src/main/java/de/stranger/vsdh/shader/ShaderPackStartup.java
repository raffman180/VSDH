package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.OpenedPack;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Optional pack selection at startup. Only the render thread can install a prepared world host. */
public final class ShaderPackStartup {
    private static final org.slf4j.Logger LOGGER=LoggerFactory.getLogger("VSDH-Shaders");
    private static final SystemToast.SystemToastId TOAST=new SystemToast.SystemToastId();
    private final String name;
    private CompletableFuture<WorldPackSet> pending;
    private boolean waitingForMenu;

    private ShaderPackStartup(Path directory,String name,String profile,boolean distant) {
        this.name=name;
        pending=CompletableFuture.supplyAsync(()->{
            try(var pack=OpenedPack.open(resolvePack(directory,name),Map.of(),profile)) {
                return WorldPackSet.prepare(pack,distant);
            } catch(IOException failure) {
                throw new CompletionException(failure);
            }
        }, command->Thread.ofVirtual().name("VSDH shaderpack preparation").start(command));
    }

    public static void start(String configuredName,String profile,boolean distant) {
        if(configuredName==null || configuredName.isBlank())return;
        String name=configuredName.strip();
        var startup=new ShaderPackStartup(FMLPaths.GAMEDIR.get().resolve("shaderpacks"),name,profile==null?"":profile.strip(),distant);
        NeoForge.EVENT_BUS.addListener(startup::tick);
        LOGGER.info("Preparing Vulkan shaderpack {}",name);
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

    private void tick(ClientTickEvent.Post event) {
        if(pending==null || !pending.isDone())return;
        var mc=Minecraft.getInstance();
        if(mc.level!=null && !pending.isCompletedExceptionally()) {
            if(!waitingForMenu) {
                waitingForMenu=true;
                notify(mc,"waiting",Component.literal(name));
            }
            return;
        }
        var result=pending;
        pending=null;
        try {
            WorldShaderSession.install(result.join());
            LOGGER.info("Vulkan shaderpack ready: {}",name);
            notify(mc,"ready",Component.literal(name));
        } catch(RuntimeException failure) {
            LOGGER.error("Could not activate Vulkan shaderpack "+name,failure);
            notify(mc,"failed",Component.translatable("vsdh.shader.failed.details"));
        }
    }

    private static void notify(Minecraft mc,String state,Component details) {
        SystemToast.addOrUpdate(mc.getToasts(),TOAST,Component.translatable("vsdh.shader."+state),details);
    }
}

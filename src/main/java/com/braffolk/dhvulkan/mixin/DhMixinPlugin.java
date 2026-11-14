package com.braffolk.dhvulkan.mixin;

import com.braffolk.dhvulkan.DhSupport;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/**
 * Selects the shutdown hooks matching DH's loaded SQL implementation.
 *
 * <p>The DH class names in this class and in the hooks are the plain ones; with DH 3.3.0 the nested
 * vsdh-dh330 library rewrites them to the {@code _neoforge} twins while these classes load.
 */
public final class DhMixinPlugin implements IMixinConfigPlugin {
    private static final String REPOSITORY = "com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo";
    private Boolean regenerationQueue;

    /**
     * Distant Horizons is optional: without it, or with a DH that VSDH cannot serve, none of the DH hooks
     * may load (their targets are missing or differ).
     */
    public static boolean distantHorizonsInstalled() {
        return DhSupport.active();
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Every hook here (dh3.* and the DH composite in shared.*) serves DH; without a usable DH none may load.
        if (!distantHorizonsInstalled()) return false;
        return switch (mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)) {
            case "MixinRegenQueryScope", "MixinUpdatePropagatorShutdown" -> hasRegenerationQueue();
            case "MixinLegacyUpdateQueryScope", "MixinLegacyUpdatePropagatorShutdown" -> !hasRegenerationQueue();
            // The busy loop exists only where DH runs its own chunk update thread (3.3.x, not 3.2.0-b).
            case "MixinChunkUpdateQueueBackoff" -> !"false".equals(System.getProperty("vsdh.dhQueueBackoff"))
                    && dhClassHasMethod("com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager", "runQueueingLoop");
            default -> true;
        };
    }

    private static boolean dhClassHasMethod(String className, String method) {
        var mod = FMLLoader.getLoadingModList().getModFileById("distanthorizons");
        var path = mod.getFile().findResource(className.replace('.', '/') + ".class");
        if (!Files.exists(path)) return false;
        try (var stream = Files.newInputStream(path)) {
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node.methods.stream().anyMatch(candidate -> candidate.name.equals(method));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot inspect the installed DH class " + className, failure);
        }
    }

    private boolean hasRegenerationQueue() {
        if (regenerationQueue == null) {
            var mod = FMLLoader.getLoadingModList().getModFileById("distanthorizons");
            var path = mod.getFile().findResource(REPOSITORY.replace('.', '/') + ".class");
            try (var stream = Files.newInputStream(path)) {
                var repo = new ClassNode();
                new ClassReader(stream).accept(repo, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                regenerationQueue = repo.methods.stream().anyMatch(method -> method.name.equals("getChildPositionsToRegen")
                        && method.desc.equals("(IIII)Lit/unimi/dsi/fastutil/longs/LongArrayList;"));
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot inspect the installed DH repository interface", failure);
            }
        }
        return regenerationQueue;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
}

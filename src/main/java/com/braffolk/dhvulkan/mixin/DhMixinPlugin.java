package com.braffolk.dhvulkan.mixin;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/** Selects the shutdown hooks matching DH's loaded SQL implementation. */
public final class DhMixinPlugin implements IMixinConfigPlugin {
    private static final String REPOSITORY = "com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo";
    private Boolean regenerationQueue;

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return switch (mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)) {
            case "MixinRegenQueryScope", "MixinUpdatePropagatorShutdown" -> hasRegenerationQueue();
            case "MixinLegacyUpdateQueryScope", "MixinLegacyUpdatePropagatorShutdown" -> !hasRegenerationQueue();
            default -> true;
        };
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

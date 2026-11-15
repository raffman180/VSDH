package de.stranger.vsdh.shader;

import dev.vitrail.glsl.TranslationCache;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Puts the two on-disk stores of a pack preparation under {@code config/vsdh-shadercache}: compiled programs
 * ({@link SpirvCache}, {@code spirv/}) and translated programs (the translator's own TranslationCache,
 * {@code translations/}). With both filled, preparing a pack that was used before reads files instead of translating
 * and compiling (Jurgens: 22.7 s sequential and cold, about 1.2 to 2.2 s from disk, 15.11.2026).
 * <p>
 * The translation store is filed under a digest of the translator's own classes in this mod file, so a build with a
 * changed translator never reads what another build wrote; the translator keeps one neighbouring edition and drops
 * the rest. {@code -Dvsdh.spirvCache=false} and {@code -Dvsdh.translationCache=false} turn either off.
 * Called once on the first preparation worker; never on the render thread.
 */
final class ShaderDiskCache {
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("VSDH-Shaders");
    private static boolean done;

    private ShaderDiskCache() {}

    static synchronized void ensure() {
        if (done) return;
        done = true;
        try {
            Path root = FMLPaths.CONFIGDIR.get().resolve("vsdh-shadercache");
            SpirvCache.install(root.resolve("spirv"));
            if (!"false".equals(System.getProperty("vsdh.translationCache")) && !TranslationCache.installed()) {
                String edition = translatorEdition();
                if (edition != null) {
                    TranslationCache.install(root, "vsdh+" + edition, "vsdh");
                    if (!TranslationCache.problem().isEmpty())
                        LOGGER.warn("Translated shader programs are not kept on disk: {}", TranslationCache.problem());
                }
            }
            LOGGER.info("Shader disk cache in {}: compiled programs {}, translated programs {}; {} compile workers", root,
                    SpirvCache.usable() ? "on" : "off", TranslationCache.installed() ? "on" : "off", PreparePool.THREADS);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("Shader disk cache not available: {}", failure.toString());
        }
    }

    /** A digest of the translator classes of this mod file, or null when they cannot be listed. */
    private static String translatorEdition() {
        try {
            var mods = FMLLoader.getLoadingModList();
            var mod = mods == null ? null : mods.getModFileById(de.stranger.vsdh.Vsdh.MOD_ID);
            if (mod == null) return null;
            return ClassTreeDigest.of(mod.getFile().findResource("dev/vitrail"));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}

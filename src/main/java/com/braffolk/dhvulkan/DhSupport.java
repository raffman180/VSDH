package com.braffolk.dhvulkan;

import net.neoforged.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.List;

/**
 * Decides once per start whether VSDH's Distant Horizons adapter may run with the installed DH.
 *
 * <p>DH is optional. One VSDH JAR serves every supported DH version: DH 3.3.0 names its NeoForge
 * classes {@code X_neoforge}, and the library nested in the VSDH JAR (de.stranger.vsdh.dh330)
 * rewrites VSDH's references as its classes load. This class cross-checks that rewrite against the
 * installed DH file, so a DH that VSDH cannot serve ends in one clear log line and a disabled
 * adapter, never in a missing-class crash.
 *
 * <p>Loads no DH class.
 */
public final class DhSupport {
    /** DH versions for Minecraft 1.21.1 that passed the release matrix; keep in step with neoforge.mods.toml. */
    public static final List<String> SUPPORTED_VERSIONS = List.of("3.2.0-b", "3.3.0", "3.3.1", "3.3.2", "3.3.3");

    private static final String MOD_ID = "distanthorizons";
    /** Present only in DH builds that twin their classes for NeoForge (3.3.0). Not a class name, so never rewritten. */
    private static final String TWIN_RESOURCE = "com/seibel/distanthorizons/core/config/Config_neoforge.class";
    private static final String TWIN_SUFFIX = "_neoforge";

    private DhSupport() {}

    public enum Status {
        /** DH is not installed. */
        ABSENT,
        /** A supported DH is installed and VSDH's classes match its class names. */
        ACTIVE,
        /** DH is installed in a version VSDH was not verified with. */
        UNSUPPORTED_VERSION,
        /** VSDH's classes and the installed DH disagree about DH's class names. */
        ADAPTER_MISMATCH
    }

    public record Result(Status status, String version, String message) {
        public boolean active() {
            return status == Status.ACTIVE;
        }
    }

    /**
     * @param version the installed DH version, or null without DH
     * @param twinnedClasses whether the installed DH carries {@code _neoforge} twins of its core classes
     * @param adapted whether VSDH's classes were rewritten to those twins while loading
     */
    public static Result evaluate(String version, boolean twinnedClasses, boolean adapted) {
        if (version == null) return new Result(Status.ABSENT, null,
                "Distant Horizons is not installed; VSDH runs the Vulkan renderer and shader packs without LODs.");
        if (!SUPPORTED_VERSIONS.contains(version)) return new Result(Status.UNSUPPORTED_VERSION, version,
                "Distant Horizons " + version + " is not supported by this VSDH build (supported: "
                        + String.join(", ", SUPPORTED_VERSIONS) + "). VSDH's DH renderer and DH hooks stay off; "
                        + "install a supported DH version or remove DH.");
        if (twinnedClasses && !adapted) return new Result(Status.ADAPTER_MISMATCH, version,
                "Distant Horizons " + version + " uses separately named NeoForge classes, but VSDH's load-time adapter for them "
                        + "did not run (see the earlier vsdh-dh330 log line; an unknown DH " + version + " file is refused). "
                        + "VSDH's DH renderer and DH hooks stay off.");
        if (!twinnedClasses && adapted) return new Result(Status.ADAPTER_MISMATCH, version,
                "VSDH was adapted to separately named NeoForge classes that Distant Horizons " + version
                        + " does not contain. VSDH's DH renderer and DH hooks stay off.");
        return new Result(Status.ACTIVE, version, "Distant Horizons " + version + " detected"
                + (adapted ? "; VSDH was adapted to its NeoForge class names at load time." : "."));
    }

    /**
     * Whether this class went through the DH 3.3.0 rewrite: the rewrite turns every class-name
     * constant of a twinned DH class into its twin, this probe included.
     */
    static boolean adapted() {
        return adapted("com.seibel.distanthorizons.core.config.Config");
    }

    static boolean adapted(String probe) {
        return probe.endsWith(TWIN_SUFFIX);
    }

    /** The decision for the running game. */
    public static Result result() {
        return Holder.RESULT;
    }

    /** Whether DH is installed and VSDH's DH adapter may be used. */
    public static boolean active() {
        return Holder.RESULT.active();
    }

    private static final class Holder {
        static final Result RESULT = detect();
    }

    private static Result detect() {
        Logger logger = LoggerFactory.getLogger("vsdh");
        Result result;
        try {
            var mods = FMLLoader.getLoadingModList();
            var file = mods == null ? null : mods.getModFileById(MOD_ID);
            if (file == null) return evaluate(null, false, false);
            String version = file.getMods().stream().filter(mod -> MOD_ID.equals(mod.getModId())).findFirst()
                    .map(mod -> mod.getVersion().toString()).orElse("unknown");
            result = evaluate(version, Files.exists(file.getFile().findResource(TWIN_RESOURCE)), adapted());
        } catch (RuntimeException | LinkageError failure) {
            logger.error("VSDH could not identify the installed Distant Horizons; its DH renderer and DH hooks stay off.", failure);
            return new Result(Status.ADAPTER_MISMATCH, "unknown", "The installed Distant Horizons could not be identified.");
        }
        if (result.active()) logger.info(result.message());
        else logger.error(result.message());
        return result;
    }
}

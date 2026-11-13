/* Adapted from Braffolk's DH Vulkan extension, LGPL-3.0. See NOTICE. */
package com.braffolk.dhvulkan.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loaded on the mod-loading worker. The shader fields can also change from the Shader settings page,
 * which saves the file again ({@link #save}); the other fields take effect after restarting.
 */
public final class DhVulkanConfig {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("vsdh.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("VSDH");
    private static volatile DhVulkanConfig instance;
    /** 0=normal, 1=DH depth, 2=SSAO, 3=fog alpha, 4=fog color, 5=normals, 6=MC depth. */
    public int vulkanRenderMode = 0;
    public String shaderPack = "";
    public String shaderProfile = "MEDIUM";
    public java.util.Map<String,String> shaderOptions = java.util.Map.of();
    public boolean shaderDistantHorizons = true;
    /**
     * Profile and options of packs that are not selected right now, by pack name, so that switching
     * packs on the settings page and back keeps what the player had set for each.
     */
    public java.util.Map<String,PackSettings> otherPacks = new java.util.TreeMap<>();
    /** The file existed but could not be read; {@link #save} keeps a copy before replacing it. */
    private transient boolean unreadable;

    public record PackSettings(String profile, java.util.Map<String,String> options) {}

    /** Writes the current values; the render thread calls this after the settings page applied them. */
    public synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (unreadable && Files.exists(CONFIG_PATH)) {
                // The file could not be read at startup: keep the player's version beside the new one.
                Path backup = CONFIG_PATH.resolveSibling("vsdh.json.unreadable");
                Files.copy(CONFIG_PATH, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LOGGER.warn("Kept the unreadable {} as {}", CONFIG_PATH, backup);
            }
            unreadable = false;
            Path temporary = CONFIG_PATH.resolveSibling("vsdh.json.tmp");
            Files.writeString(temporary, GSON.toJson(this));
            Files.move(temporary, CONFIG_PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            LOGGER.warn("Cannot save {}", CONFIG_PATH, e);
        }
    }

    /** Selects {@code pack}, keeping the profile and options of the previous one for later. */
    public synchronized void selectPack(String pack, String profileForNew) {
        pack = pack == null ? "" : pack.strip();
        String previous = shaderPack == null ? "" : shaderPack.strip();
        if (pack.equals(previous)) return;
        if (otherPacks == null) otherPacks = new java.util.TreeMap<>();
        if (!previous.isEmpty())
            otherPacks.put(previous, new PackSettings(shaderProfile, shaderOptions == null ? java.util.Map.of() : shaderOptions));
        var saved = pack.isEmpty() ? null : otherPacks.remove(pack);
        shaderPack = pack;
        if (pack.isEmpty()) return; // the previous pack's values wait in otherPacks
        shaderProfile = saved != null && saved.profile() != null ? saved.profile() : profileForNew;
        shaderOptions = saved != null && saved.options() != null ? new java.util.TreeMap<>(saved.options()) : java.util.Map.of();
    }

    /** The profile remembered for {@code pack}, or null. */
    public synchronized String rememberedProfile(String pack) {
        if (pack != null && pack.strip().equals(shaderPack == null ? "" : shaderPack.strip())) return shaderProfile;
        var saved = otherPacks == null || pack == null ? null : otherPacks.get(pack.strip());
        return saved == null ? null : saved.profile();
    }

    public static synchronized DhVulkanConfig get() {
        if (instance != null) return instance;
        if (Files.exists(CONFIG_PATH)) {
            try {
                instance = GSON.fromJson(Files.readString(CONFIG_PATH), DhVulkanConfig.class);
                if (instance == null) throw new IllegalArgumentException("empty configuration");
                instance.vulkanRenderMode = Math.clamp(instance.vulkanRenderMode, 0, 6);
                if (instance.shaderPack == null) instance.shaderPack = "";
                if (instance.shaderProfile == null) instance.shaderProfile = "";
                if (instance.shaderOptions == null) instance.shaderOptions = java.util.Map.of();
                if (instance.otherPacks == null) instance.otherPacks = new java.util.TreeMap<>();
                return instance;
            } catch (Exception e) {
                LOGGER.warn("Cannot load {}; using defaults without overwriting the file", CONFIG_PATH, e);
                instance = new DhVulkanConfig();
                instance.unreadable = true;
                return instance;
            }
        }
        instance = new DhVulkanConfig();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(instance));
        } catch (Exception e) {
            LOGGER.warn("Cannot create {}; continuing with defaults", CONFIG_PATH, e);
        }
        return instance;
    }
}

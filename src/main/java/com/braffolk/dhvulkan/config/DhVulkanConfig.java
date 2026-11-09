/* Adapted from Braffolk's DH Vulkan extension, LGPL-3.0. See NOTICE. */
package com.braffolk.dhvulkan.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loaded on the mod-loading worker. Changes take effect after restarting. */
public final class DhVulkanConfig {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("vsdh.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("VSDH");
    private static volatile DhVulkanConfig instance;
    /** 0=normal, 1=DH depth, 2=SSAO, 3=fog alpha, 4=fog color, 5=normals, 6=MC depth. */
    public int vulkanRenderMode = 0;
    public String shaderPack = "";
    public String shaderProfile = "MEDIUM";
    public boolean shaderDistantHorizons = true;

    public static synchronized DhVulkanConfig get() {
        if (instance != null) return instance;
        if (Files.exists(CONFIG_PATH)) {
            try {
                instance = GSON.fromJson(Files.readString(CONFIG_PATH), DhVulkanConfig.class);
                if (instance == null) throw new IllegalArgumentException("empty configuration");
                instance.vulkanRenderMode = Math.clamp(instance.vulkanRenderMode, 0, 6);
                return instance;
            } catch (Exception e) {
                LOGGER.warn("Cannot load {}; using defaults without overwriting the file", CONFIG_PATH, e);
                return instance = new DhVulkanConfig();
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

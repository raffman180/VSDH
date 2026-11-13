package de.stranger.vsdh;

import com.braffolk.dhvulkan.api.ApiDhIntegration;
import com.braffolk.dhvulkan.core.VulkanRenderEngine;
import com.braffolk.dhvulkan.config.DhVulkanConfig;
import de.stranger.vsdh.shader.ShaderPackStartup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import com.mojang.blaze3d.systems.RenderSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Physical-client entrypoint. GPU initialization is deferred until rendering. */
@Mod(value = Vsdh.MOD_ID, dist = Dist.CLIENT)
public final class Vsdh {
    public static final String MOD_ID = "vsdh";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public Vsdh() {
        // Load the small config on the mod-loading worker, before rendering.
        var config=DhVulkanConfig.get();
        if (distantHorizons()) {
            // Only touch DH's API when DH is installed; the Vulkan renderer and shader packs work without it.
            DistantHorizonsSetup.initialize();
            NeoForge.EVENT_BUS.addListener(this::onLogout);
        } else {
            LOGGER.info("Distant Horizons is not installed; VSDH runs the Vulkan renderer and shader packs without LODs.");
        }
        if(!Boolean.getBoolean("vsdh.smoke") || Boolean.getBoolean("vsdh.smoke.configuredPack"))
            ShaderPackStartup.start(config.shaderPack,config.shaderProfile,config.shaderOptions,config.shaderDistantHorizons&&distantHorizons());
        LOGGER.info("VSDH native renderer initialized; complete shaderpack and DH-pack integration remain in development.");
    }

    /** Whether Distant Horizons is installed (it is optional). */
    public static boolean distantHorizons() {
        return DISTANT_HORIZONS;
    }

    private static final boolean DISTANT_HORIZONS = net.neoforged.fml.ModList.get() != null
            ? net.neoforged.fml.ModList.get().isLoaded("distanthorizons")
            : net.neoforged.fml.loading.FMLLoader.getLoadingModList().getModFileById("distanthorizons") != null;

    private void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (RenderSystem.isOnRenderThread()) DistantHorizonsSetup.cleanup();
        else RenderSystem.recordRenderCall(DistantHorizonsSetup::cleanup);
    }

    /** Kept in its own class so that DH's API classes load only when DH is installed. */
    private static final class DistantHorizonsSetup {
        static void initialize() {
            ApiDhIntegration integration = new ApiDhIntegration();
            integration.initialize(new VulkanRenderEngine());
        }

        static void cleanup() {
            ApiDhIntegration integration = ApiDhIntegration.getInstance();
            if (integration != null) integration.getBackend().cleanup();
        }
    }
}

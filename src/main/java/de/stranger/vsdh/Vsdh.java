package de.stranger.vsdh;

import com.braffolk.dhvulkan.DhSupport;
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
        } else if (DhSupport.result().status() == DhSupport.Status.ABSENT) {
            LOGGER.info(DhSupport.result().message());
        }
        if(!Boolean.getBoolean("vsdh.smoke") || Boolean.getBoolean("vsdh.smoke.configuredPack"))
            ShaderPackStartup.start(config.shaderPack,config.shaderProfile,config.shaderOptions,config.shaderDistantHorizons&&distantHorizons());
        LOGGER.info("VSDH native renderer initialized; complete shaderpack and DH-pack integration remain in development.");
    }

    /** Whether Distant Horizons is installed (it is optional) in a version VSDH's adapter serves. */
    public static boolean distantHorizons() {
        return DISTANT_HORIZONS;
    }

    /** False without DH and with a DH version VSDH cannot serve (DhSupport logs why). */
    private static final boolean DISTANT_HORIZONS = DhSupport.active();

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

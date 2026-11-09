package de.stranger.vsdh;

import com.braffolk.dhvulkan.api.ApiDhIntegration;
import com.braffolk.dhvulkan.core.VulkanRenderEngine;
import com.braffolk.dhvulkan.config.DhVulkanConfig;
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
        DhVulkanConfig.get();
        ApiDhIntegration integration = new ApiDhIntegration();
        integration.initialize(new VulkanRenderEngine());
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        LOGGER.info("VSDH native renderer initialized; complete shaderpack and DH-pack integration remain in development.");
    }

    private void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        Runnable cleanup = () -> {
            ApiDhIntegration integration = ApiDhIntegration.getInstance();
            if (integration != null) integration.getBackend().cleanup();
        };
        if (RenderSystem.isOnRenderThread()) cleanup.run();
        else RenderSystem.recordRenderCall(cleanup::run);
    }
}

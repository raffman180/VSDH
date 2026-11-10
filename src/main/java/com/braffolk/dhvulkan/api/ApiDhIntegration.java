package com.braffolk.dhvulkan.api;

import com.braffolk.dhvulkan.bridge.DhIntegration;
import com.braffolk.dhvulkan.core.VulkanBackend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DH 3.0 integration layer. Creates VkRenderApiDefinition and defers
 * binding into DH's SingletonInjector until DH's own delayed setup calls
 * setRenderingApiBindings() (intercepted by MixinDependencySetup).
 */
public class ApiDhIntegration implements DhIntegration {
    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod");

    private static volatile ApiDhIntegration instance;

    private VkRenderApiDefinition renderApi;
    private VulkanBackend backend;

    @Override
    public void initialize(VulkanBackend backend) {
        this.backend = backend;
        this.renderApi = createRenderApi(backend);
        instance = this;
        LOGGER.info("[DH-VulkanMod] DH 3.0 API integration created (renderer binding deferred to DH setup).");
    }

    private static VkRenderApiDefinition createRenderApi(VulkanBackend backend) {
        ClassLoader loader = ApiDhIntegration.class.getClassLoader();
        try {
            Class.forName("com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection", false, loader);
        } catch (ClassNotFoundException legacyDepthApi) {
            // The legacy subclass is compiled against 3.3.1 and loaded only on that API.
            try {
                return Class.forName("com.braffolk.dhvulkan.api.LegacyVkRenderApiDefinition", true, loader)
                        .asSubclass(VkRenderApiDefinition.class).getConstructor(VulkanBackend.class).newInstance(backend);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot initialize VSDH for the DH 3.3.0–3.3.1 renderer API", failure);
            }
        }
        return new ModernVkRenderApiDefinition(backend);
    }

    @Override
    public VulkanBackend getBackend() {
        return this.backend;
    }

    @Override
    public String getName() {
        return "DH 3.0 API";
    }

    /** Called from MixinDependencySetup to bind renderers at the right time */
    public void bindRenderApi() {
        if (renderApi != null) {
            renderApi.bindRenderers();
            LOGGER.info("[DH-VulkanMod] Vulkan renderers bound into DH's SingletonInjector.");
        }
    }

    public static ApiDhIntegration getInstance() {
        return instance;
    }
}

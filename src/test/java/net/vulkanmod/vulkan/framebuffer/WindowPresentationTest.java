package net.vulkanmod.vulkan.framebuffer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowPresentationTest {
    @Test
    void exclusiveFullscreenAlwaysUsesTheVulkanSwapchain() {
        assertTrue(WindowPresentation.useVulkanSwapchain(true, false, "dxgi"));
        assertTrue(WindowPresentation.useVulkanSwapchain(true, true, "dxgi"));
    }

    @Test
    void borderlessFollowsTheChosenPathAndOrdinaryWindowsUseDxgi() {
        assertTrue(WindowPresentation.useVulkanSwapchain(false, true, "vulkan"));
        assertFalse(WindowPresentation.useVulkanSwapchain(false, true, "dxgi"));
        assertEquals(WindowPresentation.DEFAULT_BORDERLESS_PATH == WindowPresentation.Path.VULKAN,
                WindowPresentation.useVulkanSwapchain(false, true, null));
        assertFalse(WindowPresentation.useVulkanSwapchain(false, false, "vulkan"));
    }
}

package net.vulkanmod.vulkan.framebuffer;

/**
 * How the window is shown, as the swapchain needs to know it: exclusive fullscreen (the window owns a monitor), a
 * borderless window sized to the monitor, or an ordinary window.
 * <p>
 * A borderless window has no monitor in GLFW, so it looks like an ordinary window to the swapchain. The present path
 * for it is chosen separately: {@code -Dvsdh.borderlessPresent=vulkan} presents with the Vulkan swapchain as
 * exclusive fullscreen does, {@code dxgi} with the DXGI flip path used for ordinary windows. The default is the
 * measured better one ({@link #DEFAULT_BORDERLESS_PATH}, docs/PERFORMANCE.md).
 */
public final class WindowPresentation {
    private WindowPresentation() {}

    public enum Path { VULKAN, DXGI }

    /** Measured on the player's machine (RTX 4070, 2560x1440 at 180 Hz, Windows 11): see docs/PERFORMANCE.md. */
    static final Path DEFAULT_BORDERLESS_PATH = Path.VULKAN;

    private static volatile boolean borderless;

    /** Set by the window code whenever the window becomes or stops being a borderless fullscreen window. */
    public static void setBorderless(boolean value) { borderless = value; }

    public static boolean borderless() { return borderless; }

    static Path borderlessPath(String property) {
        if (property == null) return DEFAULT_BORDERLESS_PATH;
        return switch (property.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "dxgi" -> Path.DXGI;
            case "vulkan" -> Path.VULKAN;
            default -> DEFAULT_BORDERLESS_PATH;
        };
    }

    /** True when this frame's swapchain must be the Vulkan one: exclusive fullscreen, or borderless on the Vulkan path. */
    static boolean useVulkanSwapchain(boolean exclusiveFullscreen, boolean borderlessWindow, String property) {
        return exclusiveFullscreen || borderlessWindow && borderlessPath(property) == Path.VULKAN;
    }

    static boolean useVulkanSwapchain(boolean exclusiveFullscreen) {
        return useVulkanSwapchain(exclusiveFullscreen, borderless, System.getProperty("vsdh.borderlessPresent"));
    }
}

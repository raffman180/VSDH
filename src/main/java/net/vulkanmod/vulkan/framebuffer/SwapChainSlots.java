package net.vulkanmod.vulkan.framebuffer;

/**
 * Index and retry decisions of {@link SwapChain} that need no GPU.
 * <p>
 * While the window has no size the Vulkan swapchain is destroyed and the swapchain owns no image at all, but the
 * client tick keeps running (screenshots, mods asking for the main colour texture). Nothing may then hand out an
 * image of the destroyed swapchain, and the image index of the last frame may be past the end of a new image list.
 */
final class SwapChainSlots {
    private SwapChainSlots() {}

    /** Nanoseconds between two attempts to get a swapchain back for a window that reports a size. */
    static final long REVIVE_INTERVAL_NANOS = 250_000_000L;

    /**
     * The image slot to use for the renderer's current image index: -1 without images, the index itself when it is
     * valid, otherwise 0 (an index left over from a swapchain with more images, before the next acquire).
     */
    static int slot(int currentImage, int imageCount) {
        if (imageCount <= 0) return -1;
        return currentImage >= 0 && currentImage < imageCount ? currentImage : 0;
    }

    /**
     * Whether a swapchain that was given up for a window without size should be tried again: the window reports a
     * size now, and the last attempt is long enough ago. Resize events normally do this; one that arrived while the
     * surface still reported no extent would otherwise leave the client without a swapchain for good.
     */
    static boolean shouldRevive(int swapChainWidth, int swapChainHeight, int windowWidth, int windowHeight,
                                long nowNanos, long lastAttemptNanos) {
        if (swapChainWidth != 0 || swapChainHeight != 0) return false;
        if (windowWidth <= 0 || windowHeight <= 0) return false;
        return nowNanos - lastAttemptNanos >= REVIVE_INTERVAL_NANOS;
    }
}

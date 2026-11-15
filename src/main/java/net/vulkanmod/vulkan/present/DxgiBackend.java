package net.vulkanmod.vulkan.present;

/**
 * The way of getting Vulkan frames onto a DXGI flip-model swapchain ({@link D3d12Presenter}). It owns
 * {@link #IMAGES} shared textures that Vulkan imports and renders into, plus two shared fences imported
 * as timeline semaphores. Render thread only.
 */
public interface DxgiBackend extends AutoCloseable {
    int IMAGES = 3;

    /** VkExternalMemoryHandleTypeFlagBits value of {@link #textureHandle}. */
    int memoryHandleType();

    /** NT handle of shared texture {@code index} (owned by the backend). */
    long textureHandle(int index);

    long renderFenceHandle();

    long copyFenceHandle();

    int width();

    int height();

    /**
     * Queues the copy of texture {@code index} (once Vulkan's render timeline reaches
     * {@code renderValue}) into the back buffer and presents. Returns the copy fence value Vulkan has
     * to wait for before drawing into {@code index} again, or -1 when presenting failed.
     */
    long present(int index, long renderValue, boolean vsync);

    /** Resizes the swapchain and recreates the shared textures; the caller re-imports them. */
    void resize(int width, int height);

    @Override
    void close();
}

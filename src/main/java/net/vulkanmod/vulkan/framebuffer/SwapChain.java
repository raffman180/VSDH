package net.vulkanmod.vulkan.framebuffer;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.vulkanmod.Initializer;
import net.vulkanmod.gl.GlFramebuffer;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.render.util.MathUtil;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.vulkanmod.vulkan.Vulkan.*;
import static net.vulkanmod.vulkan.device.DeviceManager.vkDevice;
import static net.vulkanmod.vulkan.util.VUtil.UINT32_MAX;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class SwapChain extends Framebuffer {
    private static final int DEFAULT_IMAGE_COUNT = 3;

    // -Dvsdh.presentMode=mailbox: diagnostic switch to compare the uncapped present modes.
    private static final int defUncappedMode = "mailbox".equals(System.getProperty("vsdh.presentMode"))
            ? checkPresentMode(VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR)
            : checkPresentMode(VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR);

    private final Long2ReferenceOpenHashMap<long[]> FBO_map = new Long2ReferenceOpenHashMap<>();

    private long swapChainId = VK_NULL_HANDLE;
    private List<VulkanImage> swapChainImages;
    private VkExtent2D extent2D;
    public boolean isBGRAformat;
    private boolean vsync = false;

    private int[] glIds;
    private int depthAttachmentGlId = -1;

    public SwapChain() {
        this.attachmentCount = 2;
        this.depthFormat = Vulkan.getDefaultDepthFormat();

        this.hasColorAttachment = true;
        this.hasDepthAttachment = true;

        recreate();
    }

    public void recreate() {
        GlFramebuffer.invalidateBorrowedAttachments();
        if (this.depthAttachment != null) {
            this.depthAttachment.free();
            this.depthAttachment = null;
        }

        if (!DYNAMIC_RENDERING) {
            this.FBO_map.forEach((pass, framebuffers) -> Arrays.stream(framebuffers).forEach(id -> vkDestroyFramebuffer(getVkDevice(), id, null)));
            this.FBO_map.clear();
        }

        createSwapChain();
    }

    private void createSwapChain() {
        try (MemoryStack stack = stackPush()) {
            VkDevice device = Vulkan.getVkDevice();
            DeviceManager.SurfaceProperties surfaceProperties = DeviceManager.querySurfaceProperties(device.getPhysicalDevice(), stack);

            VkSurfaceFormatKHR surfaceFormat = getFormat(surfaceProperties.formats);
            int presentMode = getPresentMode(surfaceProperties.presentModes);
            VkExtent2D extent = getExtent(surfaceProperties.capabilities);
            // Recorded before the minimised early return too, so windowModeChanged() cannot request a rebuild every
            // frame while the window has no size.
            this.createdForExclusiveFullscreen = org.lwjgl.glfw.GLFW.glfwGetWindowMonitor(Vulkan.window) != 0;

            if (extent.width() == 0 && extent.height() == 0) {
                if (this.dxgi != null) {
                    this.width = 0;
                    this.height = 0;
                    return;
                }
                if (this.swapChainId != VK_NULL_HANDLE) {
                    this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
                    vkDestroySwapchainKHR(device, this.swapChainId, null);
                    this.swapChainId = VK_NULL_HANDLE;
                }

                this.width = 0;
                this.height = 0;
                return;
            }

            // DXGI flip presentation only in a window: in exclusive fullscreen the Vulkan swapchain was
            // faster (docs/PERFORMANCE.md). Switching either way needs the other swapchain gone first;
            // the caller has waited for the device to be idle.
            boolean fullscreen = org.lwjgl.glfw.GLFW.glfwGetWindowMonitor(Vulkan.window) != 0;
            this.createdForExclusiveFullscreen = fullscreen;
            if (this.dxgi != null && (this.dxgiDisabled || fullscreen)) closeDxgi();
            if (!this.dxgiDisabled && !fullscreen && DeviceManager.isDxgiInteropEnabled()) {
                if (this.swapChainId != VK_NULL_HANDLE) {
                    this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
                    vkDestroySwapchainKHR(device, this.swapChainId, null);
                    this.swapChainId = VK_NULL_HANDLE;
                    this.swapChainImages = new ArrayList<>();
                }
                if (createDxgi(extent.width(), extent.height())) {
                    createGlIds();
                    createDepthResources();
                    return;
                }
            }

            int requestedImages = Math.max(DEFAULT_IMAGE_COUNT, surfaceProperties.capabilities.minImageCount());

            IntBuffer imageCount = stack.ints(requestedImages);

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            createInfo.surface(Vulkan.getSurface());

            this.format = surfaceFormat.format();
            this.extent2D = VkExtent2D.create().set(extent);

            createInfo.minImageCount(requestedImages);
            createInfo.imageFormat(this.format);
            createInfo.imageColorSpace(surfaceFormat.colorSpace());
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            int supportedTransfers = surfaceProperties.capabilities.supportedUsageFlags()
                    & (VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | supportedTransfers);

            Queue.QueueFamilyIndices indices = Queue.getQueueFamilies();

            if (indices.graphicsFamily != indices.presentFamily) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(stack.ints(indices.graphicsFamily, indices.presentFamily));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            createInfo.preTransform(surfaceProperties.capabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);

            createInfo.oldSwapchain(this.swapChainId);

            LongBuffer pSwapChain = stack.longs(VK_NULL_HANDLE);

            int result = vkCreateSwapchainKHR(device, createInfo, null, pSwapChain);
            Vulkan.checkResult(result, "Failed to create swap chain");

            if (this.swapChainId != VK_NULL_HANDLE) {
                this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
                vkDestroySwapchainKHR(device, this.swapChainId, null);
            }

            this.swapChainId = pSwapChain.get(0);

            vkGetSwapchainImagesKHR(device, this.swapChainId, imageCount, null);

            LongBuffer pSwapchainImages = stack.mallocLong(imageCount.get(0));

            vkGetSwapchainImagesKHR(device, this.swapChainId, imageCount, pSwapchainImages);
            org.slf4j.LoggerFactory.getLogger("VulkanMod").info("Swapchain: {} images (requested {}, surface min {}), present mode {}, format {}",
                    imageCount.get(0), requestedImages, surfaceProperties.capabilities.minImageCount(), presentMode, this.format);

            this.swapChainImages = new ArrayList<>(imageCount.get(0));

            this.width = extent2D.width();
            this.height = extent2D.height();

            for (int i = 0; i < pSwapchainImages.capacity(); i++) {
                long imageId = pSwapchainImages.get(i);
                long imageView = VulkanImage.createImageView(imageId, this.format, VK_IMAGE_ASPECT_COLOR_BIT, 1);

                VulkanImage image = new VulkanImage(imageId, this.format, 1, this.width, this.height, 4,
                        createInfo.imageUsage(), imageView);
                image.updateTextureSampler(true, true, false);
                this.swapChainImages.add(image);
            }
        }

        createGlIds();
        createDepthResources();
    }

    // DXGI flip presentation (net.vulkanmod.vulkan.present): shared images instead of swapchain images.
    private static final int DXGI_IMAGE_USAGE = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    private net.vulkanmod.vulkan.present.DxgiBackend dxgi;
    private net.vulkanmod.vulkan.present.DxgiVulkanTargets dxgiTargets;
    private boolean dxgiDisabled;
    /** Whether the window had a monitor (exclusive fullscreen) when this swapchain was made. */
    private boolean createdForExclusiveFullscreen;

    /**
     * True when the window's fullscreen kind changed since creation without a size change. Borderless-window mods
     * turn exclusive fullscreen into a monitor-sized undecorated window of the same size: no resize event arrives,
     * and the swapchain would keep the fullscreen present path (Vulkan instead of DXGI flip) in a window.
     */
    public boolean windowModeChanged() {
        return createdForExclusiveFullscreen != (org.lwjgl.glfw.GLFW.glfwGetWindowMonitor(Vulkan.window) != 0);
    }
    private final long[] dxgiCopyValues = new long[net.vulkanmod.vulkan.present.DxgiBackend.IMAGES];
    private int dxgiNext;

    /** Creates or resizes the DXGI path; false (and disabled for good) when it is unavailable. */
    private boolean createDxgi(int width, int height) {
        try {
            if (this.dxgi == null) {
                long hwnd = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(Vulkan.window);
                this.dxgi = "d3d11".equals(DeviceManager.dxgiMode())
                        ? net.vulkanmod.vulkan.present.DxgiPresenter.create(hwnd, DeviceManager.deviceLuid(), width, height)
                        : net.vulkanmod.vulkan.present.D3d12Presenter.create(hwnd, DeviceManager.deviceLuid(), width, height);
                if (this.dxgi == null) {
                    this.dxgiDisabled = true;
                    return false;
                }
                this.dxgiTargets = net.vulkanmod.vulkan.present.DxgiVulkanTargets.create(this.dxgi, DXGI_IMAGE_USAGE);
            } else if (this.dxgi.width() != width || this.dxgi.height() != height) {
                // Caller (Renderer.recreateSwapChain) has waited for the device to be idle.
                this.dxgiTargets.close();
                this.dxgiTargets = null;
                this.dxgi.resize(width, height);
                this.dxgiTargets = net.vulkanmod.vulkan.present.DxgiVulkanTargets.create(this.dxgi, DXGI_IMAGE_USAGE);
            }
        } catch (RuntimeException failure) {
            Initializer.LOGGER.warn("DXGI presentation failed, using the Vulkan swapchain", failure);
            closeDxgi();
            this.dxgiDisabled = true;
            return false;
        }
        Arrays.fill(this.dxgiCopyValues, 0);
        this.dxgiNext = 0;
        this.format = net.vulkanmod.vulkan.present.DxgiVulkanTargets.FORMAT;
        this.isBGRAformat = false;
        this.extent2D = VkExtent2D.create().set(width, height);
        this.width = width;
        this.height = height;
        this.swapChainImages = new ArrayList<>(this.dxgiTargets.images());
        return true;
    }

    private void closeDxgi() {
        if (this.dxgiTargets != null) this.dxgiTargets.close();
        this.dxgiTargets = null;
        if (this.dxgi != null) this.dxgi.close();
        this.dxgi = null;
        this.swapChainImages = new ArrayList<>();
    }

    /** Whether frames go out through DXGI rather than vkQueuePresentKHR. */
    public boolean isDxgi() {
        return this.dxgi != null && this.width > 0;
    }

    /** DXGI path: the next shared image; its previous copy has to finish first (see {@link #dxgiWaitValue}). */
    public int dxgiAcquire() {
        int index = this.dxgiNext;
        this.dxgiNext = (index + 1) % this.swapChainImages.size();
        return index;
    }

    public long dxgiWaitValue(int index) { return this.dxgiCopyValues[index]; }
    public long dxgiCopySemaphore() { return this.dxgiTargets.copySemaphore(); }
    public long dxgiRenderSemaphore() { return this.dxgiTargets.renderSemaphore(); }

    /** Hands image {@code index} to DXGI once Vulkan's render timeline reaches {@code renderValue}. */
    public boolean dxgiPresent(int index, long renderValue) {
        long copy = this.dxgi.present(index, renderValue, this.vsync);
        if (copy < 0) {
            this.dxgiDisabled = true; // the next recreate falls back to the Vulkan swapchain
            return false;
        }
        this.dxgiCopyValues[index] = copy;
        return true;
    }

    /** Layout the frame's colour image ends in: GENERAL for a shared image, PRESENT_SRC for a swapchain image. */
    public int presentLayout() {
        return this.dxgi != null ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    }

    private void createGlIds() {
        this.glIds = new int[this.swapChainImages.size()];

        for (int i = 0; i < this.swapChainImages.size(); i++) {
            int id = GlTexture.genTextureId();
            this.glIds[i] = id;
            GlTexture.bindIdToImage(id, this.swapChainImages.get(i));
        }
    }

    public int getColorAttachmentGlId() {
        return this.glIds[Renderer.getCurrentImage()];
    }

    private long[] createFramebuffers(RenderPass renderPass) {

        try (MemoryStack stack = MemoryStack.stackPush()) {

            long[] framebuffers = new long[this.swapChainImages.size()];

            for (int i = 0; i < this.swapChainImages.size(); ++i) {
                LongBuffer attachments = stack.longs(this.swapChainImages.get(i).getImageView(), this.depthAttachment.getImageView());

                LongBuffer pFramebuffer = stack.mallocLong(1);

                VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
                framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
                framebufferInfo.renderPass(renderPass.getId());
                framebufferInfo.width(this.width);
                framebufferInfo.height(this.height);
                framebufferInfo.layers(1);
                framebufferInfo.pAttachments(attachments);

                if (vkCreateFramebuffer(Vulkan.getVkDevice(), framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create framebuffer");
                }

                framebuffers[i] = pFramebuffer.get(0);
            }

            return framebuffers;
        }
    }

    private void createDepthResources() {
        this.depthAttachment = VulkanImage.createDepthImage(depthFormat, this.width, this.height,
                MainDepthSnapshot.depthAttachmentUsage(),
                false, false);

        if (this.depthAttachmentGlId == -1) {
            this.depthAttachmentGlId = GlTexture.genTextureId();
        }
        GlTexture.bindIdToImage(this.depthAttachmentGlId, this.depthAttachment);
    }

    public int getDepthAttachmentGlId() {
        return this.depthAttachmentGlId;
    }

    @Override
    protected long getFramebufferId(RenderPass renderPass) {
        // Looked up for every render pass begin: no capturing lambda on the hit path.
        long[] framebuffers = this.FBO_map.get(renderPass.id);
        if (framebuffers == null) {
            framebuffers = createFramebuffers(renderPass);
            if (framebuffers != null) this.FBO_map.put(renderPass.id, framebuffers);
        }
        return framebuffers[Renderer.getCurrentImage()];
    }

    public void cleanUp() {
        GlFramebuffer.invalidateBorrowedAttachments();
        VkDevice device = Vulkan.getVkDevice();

        if (!DYNAMIC_RENDERING) {
            this.FBO_map.forEach((pass, framebuffers) -> Arrays.stream(framebuffers).forEach(id -> vkDestroyFramebuffer(getVkDevice(), id, null)));
            this.FBO_map.clear();
        }

        if (this.dxgi != null || this.dxgiTargets != null) {
            closeDxgi();
        } else {
            vkDestroySwapchainKHR(device, this.swapChainId, null);
            this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
        }

        if (this.glIds != null) {
            for (int id : this.glIds) {
                GlTexture.setVulkanImage(id, null);
            }
        }

        if (this.depthAttachmentGlId != -1) {
            GlTexture.setVulkanImage(this.depthAttachmentGlId, null);
            this.depthAttachmentGlId = -1;
        }

        this.depthAttachment.free();
    }

    public long getId() {
        return this.swapChainId;
    }

    public List<VulkanImage> getImages() {
        return this.swapChainImages;
    }

    public long getImageId(int i) {
        return this.swapChainImages.get(i).getId();
    }

    public VkExtent2D getExtent() {
        return this.extent2D;
    }

    public VulkanImage getColorAttachment() {
        return this.swapChainImages.get(Renderer.getCurrentImage());
    }

    public long getImageView(int i) {
        return this.swapChainImages.get(i).getImageView();
    }

    private VkSurfaceFormatKHR getFormat(VkSurfaceFormatKHR.Buffer availableFormats) {
        List<VkSurfaceFormatKHR> list = availableFormats.stream().toList();

        VkSurfaceFormatKHR format = list.get(0);

        for (VkSurfaceFormatKHR availableFormat : list) {
            if (availableFormat.format() == VK_FORMAT_R8G8B8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                return availableFormat;

            if (availableFormat.format() == VK_FORMAT_B8G8R8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                format = availableFormat;
            }
        }

        if (format.format() == VK_FORMAT_B8G8R8A8_UNORM)
            isBGRAformat = true;
        return format;
    }

    private int getPresentMode(IntBuffer availablePresentModes) {
        int requestedMode = vsync ? VK_PRESENT_MODE_FIFO_KHR : defUncappedMode;

        if (requestedMode == VK_PRESENT_MODE_FIFO_KHR)
            return VK_PRESENT_MODE_FIFO_KHR;

        for (int i = 0; i < availablePresentModes.capacity(); i++) {
            if (availablePresentModes.get(i) == requestedMode) {
                return requestedMode;
            }
        }

        Initializer.LOGGER.warn("Requested mode not supported: " + getDisplayModeString(requestedMode) + ": using VSync");
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private String getDisplayModeString(int requestedMode) {
        return switch (requestedMode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "Immediate";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "Mailbox (FastSync)";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO Relaxed (Adaptive VSync)";
            default -> "FIFO (VSync)";
        };
    }

    private static VkExtent2D getExtent(VkSurfaceCapabilitiesKHR capabilities) {

        if (capabilities.currentExtent().width() != UINT32_MAX) {
            return capabilities.currentExtent();
        }

        IntBuffer width = stackGet().ints(0);
        IntBuffer height = stackGet().ints(0);

        glfwGetFramebufferSize(window, width, height);

        VkExtent2D actualExtent = VkExtent2D.mallocStack().set(width.get(0), height.get(0));

        VkExtent2D minExtent = capabilities.minImageExtent();
        VkExtent2D maxExtent = capabilities.maxImageExtent();

        actualExtent.width(MathUtil.clamp(minExtent.width(), maxExtent.width(), actualExtent.width()));
        actualExtent.height(MathUtil.clamp(minExtent.height(), maxExtent.height(), actualExtent.height()));

        return actualExtent;
    }

    private static int checkPresentMode(int... requestedModes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var a = DeviceManager.querySurfaceProperties(vkDevice.getPhysicalDevice(), stack).presentModes;
            for (int dMode : requestedModes) {
                for (int i = 0; i < a.capacity(); i++) {
                    if (a.get(i) == dMode) {
                        return dMode;
                    }
                }
            }
            return VK_PRESENT_MODE_FIFO_KHR;
        }
    }

    public boolean isVsync() {
        return this.vsync;
    }

    public void setVsync(boolean vsync) {
        this.vsync = vsync;
    }

    public int getImagesNum() {
        return this.swapChainImages.size();
    }
}

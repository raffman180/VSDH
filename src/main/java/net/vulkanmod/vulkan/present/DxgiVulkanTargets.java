package net.vulkanmod.vulkan.present;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRExternalMemoryWin32.*;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Vulkan side of a {@link DxgiBackend}: the shared D3D12 textures imported as colour images (they
 * take the swapchain images' place) and the two shared fences imported as timeline semaphores.
 * Render thread only; destroy only after the device is idle.
 */
public final class DxgiVulkanTargets implements AutoCloseable {
    public static final int FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    private static final int SEMAPHORE_HANDLE_TYPE = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT;

    private final List<VulkanImage> images = new ArrayList<>();
    private final List<long[]> owned = new ArrayList<>(); // image, view, memory
    private long renderSemaphore, copySemaphore;
    private boolean semaphoresOwned;

    public List<VulkanImage> images() { return images; }
    /** Vulkan signals this timeline when a frame's image is complete. */
    public long renderSemaphore() { return renderSemaphore; }
    /** D3D signals this timeline when it has copied an image to the back buffer. */
    public long copySemaphore() { return copySemaphore; }

    public static DxgiVulkanTargets create(DxgiBackend presenter, int usage) {
        var targets = new DxgiVulkanTargets();
        try {
            targets.renderSemaphore = importTimeline(presenter.renderFenceHandle());
            targets.copySemaphore = importTimeline(presenter.copyFenceHandle());
            targets.semaphoresOwned = true;
            targets.importImages(presenter, usage);
            return targets;
        } catch (RuntimeException failure) {
            targets.close();
            throw failure;
        }
    }

    private void importImages(DxgiBackend presenter, int usage) {
        VkDevice device = DeviceManager.vkDevice;
        int handleType = presenter.memoryHandleType();
        int width = presenter.width(), height = presenter.height();
        for (int i = 0; i < DxgiBackend.IMAGES; i++) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default().handleTypes(handleType);
                var info = VkImageCreateInfo.calloc(stack).sType$Default().pNext(external)
                        .imageType(VK_IMAGE_TYPE_2D).format(FORMAT).mipLevels(1).arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                info.extent().set(width, height, 1);
                LongBuffer pImage = stack.mallocLong(1);
                check(vkCreateImage(device, info, null, pImage), "vkCreateImage (shared)");
                long image = pImage.get(0);
                long memory = VK_NULL_HANDLE;
                try {
                    var requirements = VkMemoryRequirements.malloc(stack);
                    vkGetImageMemoryRequirements(device, image, requirements);
                    var handleProperties = VkMemoryWin32HandlePropertiesKHR.calloc(stack).sType$Default();
                    check(vkGetMemoryWin32HandlePropertiesKHR(device, handleType, presenter.textureHandle(i), handleProperties),
                            "vkGetMemoryWin32HandlePropertiesKHR");
                    int bits = requirements.memoryTypeBits() & handleProperties.memoryTypeBits();
                    int type = memoryType(bits);
                    if (type < 0) throw new IllegalStateException("no memory type for the shared texture (bits " + bits + ")");
                    var dedicated = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default().image(image);
                    var importInfo = VkImportMemoryWin32HandleInfoKHR.calloc(stack).sType$Default().pNext(dedicated.address())
                            .handleType(handleType).handle(presenter.textureHandle(i));
                    var allocate = VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(importInfo)
                            .allocationSize(requirements.size()).memoryTypeIndex(type);
                    LongBuffer pMemory = stack.mallocLong(1);
                    check(vkAllocateMemory(device, allocate, null, pMemory), "vkAllocateMemory (import)");
                    memory = pMemory.get(0);
                    check(vkBindImageMemory(device, image, memory, 0), "vkBindImageMemory (shared)");
                } catch (RuntimeException failure) {
                    if (memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, null);
                    vkDestroyImage(device, image, null);
                    throw failure;
                }
                long view = VulkanImage.createImageView(image, FORMAT, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                var wrapper = new VulkanImage(image, FORMAT, 1, width, height, 4, usage, view);
                wrapper.updateTextureSampler(true, true, false);
                images.add(wrapper);
                owned.add(new long[]{image, view, memory});
            }
        }
    }

    private static int memoryType(int bits) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(DeviceManager.physicalDevice, properties);
            int fallback = -1;
            for (int i = 0; i < properties.memoryTypeCount(); i++) {
                if ((bits & (1 << i)) == 0) continue;
                if ((properties.memoryTypes(i).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) return i;
                if (fallback < 0) fallback = i;
            }
            return fallback;
        }
    }

    private static long importTimeline(long handle) {
        VkDevice device = DeviceManager.vkDevice;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0);
            var info = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type);
            LongBuffer pSemaphore = stack.mallocLong(1);
            check(vkCreateSemaphore(device, info, null, pSemaphore), "vkCreateSemaphore (timeline)");
            long semaphore = pSemaphore.get(0);
            var importInfo = VkImportSemaphoreWin32HandleInfoKHR.calloc(stack).sType$Default()
                    .semaphore(semaphore).flags(0).handleType(SEMAPHORE_HANDLE_TYPE).handle(handle);
            // Called through the raw pointer: LWJGL's struct check insists on a non-null name, which the
            // specification forbids when a handle is imported.
            long function = device.getCapabilities().vkImportSemaphoreWin32HandleKHR;
            if (function == 0) {
                vkDestroySemaphore(device, semaphore, null);
                throw new IllegalStateException("vkImportSemaphoreWin32HandleKHR not loaded");
            }
            int result = org.lwjgl.system.JNI.callPPI(device.address(), importInfo.address(), function);
            if (result != VK_SUCCESS) {
                vkDestroySemaphore(device, semaphore, null);
                throw new IllegalStateException("vkImportSemaphoreWin32HandleKHR failed: " + result);
            }
            return semaphore;
        }
    }

    private void destroyImages() {
        VkDevice device = DeviceManager.vkDevice;
        for (long[] resources : owned) {
            vkDestroyImageView(device, resources[1], null);
            vkDestroyImage(device, resources[0], null);
            vkFreeMemory(device, resources[2], null);
        }
        owned.clear();
        images.clear();
    }

    @Override
    public void close() {
        destroyImages();
        if (semaphoresOwned || renderSemaphore != 0 || copySemaphore != 0) {
            if (renderSemaphore != 0) vkDestroySemaphore(DeviceManager.vkDevice, renderSemaphore, null);
            if (copySemaphore != 0) vkDestroySemaphore(DeviceManager.vkDevice, copySemaphore, null);
            renderSemaphore = copySemaphore = 0;
        }
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: " + result);
    }
}

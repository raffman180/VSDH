package net.vulkanmod.vulkan.device;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.Initializer;
import net.vulkanmod.compat.gl.GlPatchState;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.MainDepthSnapshot;
import net.vulkanmod.vulkan.queue.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.toSet;
import static net.vulkanmod.vulkan.queue.Queue.findQueueFamilies;
import static net.vulkanmod.vulkan.util.VUtil.asPointerBuffer;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public abstract class DeviceManager {
    public static List<Device> availableDevices;
    public static List<Device> suitableDevices;

    public static VkPhysicalDevice physicalDevice;
    public static VkDevice vkDevice;

    public static Device device;
    private static boolean tessellationEnabled;
    private static boolean geometryShaderEnabled, fragmentStoresEnabled, vertexStoresEnabled;

    private static boolean dxgiInteropEnabled;
    private static boolean attachmentFeedbackLoopEnabled;
    private static long deviceLuid;

    /** Windows: shared D3D11 textures and fences can be imported (DXGI presentation, net.vulkanmod.vulkan.present). */
    public static boolean isDxgiInteropEnabled() { return dxgiInteropEnabled; }
    /**
     * VK_EXT_attachment_feedback_loop_layout: an image may be sampled while it is an attachment of the
     * same pass (GL-style feedback, used by shader packs). Enabled only where the device offers it.
     */
    public static boolean isAttachmentFeedbackLoopEnabled() { return attachmentFeedbackLoopEnabled; }
    /** The device LUID (LowPart in the low 32 bits) for picking the same DXGI adapter. */
    public static long deviceLuid() { return deviceLuid; }

    public static boolean isGeometryShaderEnabled() { return geometryShaderEnabled; }
    public static boolean isFragmentStoresEnabled() { return fragmentStoresEnabled; }
    public static boolean isVertexStoresEnabled() { return vertexStoresEnabled; }
    private static boolean independentBlendEnabled;

    public static boolean supportsIndependentBlend() {
        return independentBlendEnabled;
    }

    public static VkPhysicalDeviceProperties deviceProperties;
    public static VkPhysicalDeviceMemoryProperties memoryProperties;

    public static SurfaceProperties surfaceProperties;

    static GraphicsQueue graphicsQueue;
    static PresentQueue presentQueue;
    static boolean separatePresentQueue;
    static TransferQueue transferQueue;
    static ComputeQueue computeQueue;

    public static void init(VkInstance instance) {
        try {
            DeviceManager.getSuitableDevices(instance);

            if (availableDevices.isEmpty()) {
                throw new RuntimeException("No Vulkan-capable GPU was detected.\n"
                        + net.vulkanmod.config.Platform.vulkanDriverHint());
            }
            if (suitableDevices.isEmpty()) {
                throw new RuntimeException("Found " + availableDevices.size()
                        + " GPU(s), but none support the required Vulkan extensions."
                        + getAvailableDevicesInfo());
            }

            DeviceManager.pickPhysicalDevice();
            DeviceManager.createLogicalDevice();
        } catch (Exception e) {
            Initializer.LOGGER.info(getAvailableDevicesInfo());
            throw new RuntimeException(e);
        }
    }

    static List<Device> getAvailableDevices(VkInstance instance) {
        try (MemoryStack stack = stackPush()) {
            List<Device> devices = new ObjectArrayList<>();

            IntBuffer deviceCount = stack.ints(0);

            vkEnumeratePhysicalDevices(instance, deviceCount, null);

            if (deviceCount.get(0) == 0) {
                return List.of();
            }

            PointerBuffer ppPhysicalDevices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(instance, deviceCount, ppPhysicalDevices);

            VkPhysicalDevice currentDevice;

            for (int i = 0; i < ppPhysicalDevices.capacity(); i++) {
                currentDevice = new VkPhysicalDevice(ppPhysicalDevices.get(i), instance);

                Device device = new Device(currentDevice);
                devices.add(device);
            }

            return devices;
        }
    }

    static void getSuitableDevices(VkInstance instance) {
        availableDevices = getAvailableDevices(instance);

        List<Device> devices = new ObjectArrayList<>();
        for (Device device : availableDevices) {
            if (isDeviceSuitable(device.physicalDevice)) {
                devices.add(device);
            }
        }

        suitableDevices = devices;
    }

    public static void pickPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {

            int deviceIdx = Initializer.CONFIG.device;
            if (deviceIdx >= 0 && deviceIdx < suitableDevices.size())
                DeviceManager.device = suitableDevices.get(deviceIdx);
            else {
                DeviceManager.device = autoPickDevice();
                Initializer.CONFIG.device = -1;
            }

            physicalDevice = DeviceManager.device.physicalDevice;

            deviceProperties = device.properties;

            memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);

            surfaceProperties = querySurfaceProperties(physicalDevice, stack);

            Initializer.LOGGER.info(
                    "Selected Vulkan device: {} ({}) driver {} Vulkan {} indirectDrawSupported={} fastIndirectDraw={}",
                    device.deviceName,
                    device.vendorIdString,
                    device.driverVersion,
                    device.vkVersion,
                    device.isDrawIndirectSupported(),
                    supportsFastIndirectDraw()
            );
        }
    }

    public static boolean supportsFastIndirectDraw() {
        return device != null && device.isDrawIndirectSupported() && !device.isIntel();
    }

    public static boolean isTessellationEnabled() {
        return tessellationEnabled && vkDevice != null;
    }

    public static int maxTessellationPatchSize() {
        return isTessellationEnabled() && deviceProperties != null
                ? deviceProperties.limits().maxTessellationPatchSize() : 0;
    }

    static Device autoPickDevice() {
        ArrayList<Device> integratedGPUs = new ArrayList<>();
        ArrayList<Device> otherDevices = new ArrayList<>();

        boolean flag = false;

        Device currentDevice = null;
        for (Device device : suitableDevices) {
            currentDevice = device;

            int deviceType = device.properties.deviceType();
            if (deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                flag = true;
                break;
            } else if (deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU)
                integratedGPUs.add(device);
            else
                otherDevices.add(device);
        }

        if (!flag) {
            if (!integratedGPUs.isEmpty())
                currentDevice = integratedGPUs.get(0);
            else if (!otherDevices.isEmpty())
                currentDevice = otherDevices.get(0);
            else {
                throw new IllegalStateException("Failed to find a suitable GPU");
            }
        }

        return currentDevice;
    }

    public static void createLogicalDevice() {
        tessellationEnabled = false;
        GlPatchState.configureLimit(0);
        try (MemoryStack stack = stackPush()) {

            net.vulkanmod.vulkan.queue.Queue.QueueFamilyIndices indices = findQueueFamilies(physicalDevice);

            int[] uniqueQueueFamilies = indices.unique();

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueQueueFamilies.length, stack);

            // Present gets a queue of its own when it shares the graphics family and the family
            // has a second queue: a windowed present then waits only for its own frame, not for
            // the next frame already submitted to the graphics queue (Renderer.PRESENT_LAG).
            IntBuffer familyCount = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, familyCount, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(familyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, familyCount, families);
            separatePresentQueue = "true".equals(System.getProperty("vsdh.separatePresentQueue"))
                    && indices.presentFamily == indices.graphicsFamily
                    && families.get(indices.graphicsFamily).queueCount() >= 2;

            for (int i = 0; i < uniqueQueueFamilies.length; i++) {
                VkDeviceQueueCreateInfo queueCreateInfo = queueCreateInfos.get(i);
                queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
                queueCreateInfo.queueFamilyIndex(uniqueQueueFamilies[i]);
                boolean two = separatePresentQueue && uniqueQueueFamilies[i] == indices.graphicsFamily;
                queueCreateInfo.pQueuePriorities(two ? stack.floats(1.0f, 1.0f) : stack.floats(1.0f));
            }

            VkPhysicalDeviceVulkan11Features deviceVulkan11Features = VkPhysicalDeviceVulkan11Features.calloc(stack);
            deviceVulkan11Features.sType$Default();
            deviceVulkan11Features.shaderDrawParameters(device.isDrawIndirectSupported());

            VkPhysicalDeviceFeatures2 deviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack);
            deviceFeatures.sType$Default();
            deviceFeatures.features().samplerAnisotropy(device.availableFeatures.features().samplerAnisotropy());
            deviceFeatures.features().logicOp(device.availableFeatures.features().logicOp());
            independentBlendEnabled = device.availableFeatures.features().independentBlend();
            deviceFeatures.features().independentBlend(independentBlendEnabled);
            deviceFeatures.features().tessellationShader(device.availableFeatures.features().tessellationShader());
            // Optional shaderpack contracts (VSDH): stores/atomics from graphics stages for SSBOs and
            // images, typed storage views of extended formats, and geometry stages. Enabled only
            // where the device offers them; the shader host checks the flags before relying on one.
            var available = device.availableFeatures.features();
            geometryShaderEnabled = available.geometryShader();
            fragmentStoresEnabled = available.fragmentStoresAndAtomics();
            vertexStoresEnabled = available.vertexPipelineStoresAndAtomics();
            deviceFeatures.features().geometryShader(geometryShaderEnabled);
            deviceFeatures.features().fragmentStoresAndAtomics(fragmentStoresEnabled);
            deviceFeatures.features().vertexPipelineStoresAndAtomics(vertexStoresEnabled);
            deviceFeatures.features().shaderStorageImageExtendedFormats(available.shaderStorageImageExtendedFormats());

            deviceFeatures.features().multiDrawIndirect(device.isDrawIndirectSupported());

            if (device.availableFeatures.features().wideLines()) {
                deviceFeatures.features().wideLines(true);
                VRenderSystem.canSetLineWidth = true;
            }

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.sType$Default();
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            createInfo.pEnabledFeatures(deviceFeatures.features());
            createInfo.pNext(deviceVulkan11Features);

            if (Vulkan.DYNAMIC_RENDERING) {
                VkPhysicalDeviceDynamicRenderingFeaturesKHR dynamicRenderingFeaturesKHR = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack);
                dynamicRenderingFeaturesKHR.sType$Default();
                dynamicRenderingFeaturesKHR.dynamicRendering(true);

                deviceVulkan11Features.pNext(dynamicRenderingFeaturesKHR.address());

            }

            java.util.Set<String> deviceExtensions = new java.util.HashSet<>(Vulkan.REQUIRED_EXTENSION);
            if (device.isPortabilitySubset()) {
                deviceExtensions.add(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
            }
            dxgiInteropEnabled = false;
            if (dxgiInteropAvailable(stack)) {
                // DXGI flip presentation: import shared D3D11 textures and fences (timeline semaphores).
                deviceExtensions.add(org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME);
                deviceExtensions.add(org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME);
                var vulkan12 = org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default()
                        .timelineSemaphore(true);
                vulkan12.pNext(deviceVulkan11Features.address());
                createInfo.pNext(vulkan12);
                dxgiInteropEnabled = true;
            }
            attachmentFeedbackLoopEnabled = false;
            if (attachmentFeedbackLoopAvailable(stack)) {
                deviceExtensions.add(org.lwjgl.vulkan.EXTAttachmentFeedbackLoopLayout.VK_EXT_ATTACHMENT_FEEDBACK_LOOP_LAYOUT_EXTENSION_NAME);
                var feedback = org.lwjgl.vulkan.VkPhysicalDeviceAttachmentFeedbackLoopLayoutFeaturesEXT.calloc(stack).sType$Default()
                        .attachmentFeedbackLoopLayout(true);
                feedback.pNext(createInfo.pNext());
                createInfo.pNext(feedback.address());
                attachmentFeedbackLoopEnabled = true;
            }
            org.slf4j.LoggerFactory.getLogger("VulkanMod").info("Attachment feedback loops: {}",
                    attachmentFeedbackLoopEnabled ? "VK_EXT_attachment_feedback_loop_layout" : "not supported (snapshot copies)");
            createInfo.ppEnabledExtensionNames(asPointerBuffer(deviceExtensions));

            createInfo.ppEnabledLayerNames(Vulkan.ENABLE_VALIDATION_LAYERS ? asPointerBuffer(Vulkan.VALIDATION_LAYERS) : null);

            PointerBuffer pDevice = stack.pointers(VK_NULL_HANDLE);

            int res = vkCreateDevice(physicalDevice, createInfo, null, pDevice);
            Vulkan.checkResult(res, "Failed to create logical device");

            vkDevice = new VkDevice(pDevice.get(0), physicalDevice, createInfo, VK_API_VERSION_1_2);
            tessellationEnabled = deviceFeatures.features().tessellationShader();
            GlPatchState.configureLimit(maxTessellationPatchSize());

            graphicsQueue = new GraphicsQueue(stack, indices.graphicsFamily);
            transferQueue = new TransferQueue(stack, indices.transferFamily);
            presentQueue = new PresentQueue(stack, indices.presentFamily, separatePresentQueue ? 1 : 0);
            org.slf4j.LoggerFactory.getLogger("VulkanMod").info("Present queue: family {}, index {}", indices.presentFamily, separatePresentQueue ? 1 : 0);
            computeQueue = new ComputeQueue(stack, indices.computeFamily);
        }
    }

    /**
     * DXGI flip presentation in a window (net.vulkanmod.vulkan.present): needs Windows, both Win32 interop
     * extensions, timeline semaphores and a valid device LUID; records the LUID for the presenter.
     * -Dvsdh.dxgiPresent: auto (default: D3D12 on NVIDIA, where it was measured faster, see
     * docs/PERFORMANCE.md), d3d12/true, d3d11 (slower, kept for comparison), false.
     */
    private static boolean attachmentFeedbackLoopAvailable(MemoryStack stack) {
        String name = org.lwjgl.vulkan.EXTAttachmentFeedbackLoopLayout.VK_EXT_ATTACHMENT_FEEDBACK_LOOP_LAYOUT_EXTENSION_NAME;
        if (!device.getUnsupportedExtensions(java.util.Set.of(name)).isEmpty()) return false;
        var feedback = org.lwjgl.vulkan.VkPhysicalDeviceAttachmentFeedbackLoopLayoutFeaturesEXT.calloc(stack).sType$Default();
        var features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(feedback.address());
        org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, features);
        return feedback.attachmentFeedbackLoopLayout();
    }

    private static boolean dxgiInteropAvailable(MemoryStack stack) {
        String mode = dxgiMode();
        if (mode.equals("false")) return false;
        if (mode.equals("auto") && !device.isNvidia()) return false;
        if (org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.WINDOWS) return false;
        var missing = device.getUnsupportedExtensions(java.util.Set.of(
                org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,
                org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME));
        if (!missing.isEmpty()) return false;
        var features12 = org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
        var features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(features12);
        org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, features);
        if (!features12.timelineSemaphore()) return false;
        var id = org.lwjgl.vulkan.VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
        var properties = org.lwjgl.vulkan.VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(id);
        org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2(physicalDevice, properties);
        if (!id.deviceLUIDValid()) return false;
        deviceLuid = id.deviceLUID().getLong(0);
        return true;
    }

    /** -Dvsdh.dxgiPresent normalised: auto, d3d12, d3d11 or false. */
    public static String dxgiMode() {
        String mode = System.getProperty("vsdh.dxgiPresent", "auto").trim().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "true", "d3d12" -> "d3d12";
            case "d3d11" -> "d3d11";
            case "false", "off", "vulkan" -> "false";
            default -> "auto";
        };
    }

    private static PointerBuffer getRequiredExtensions() {
        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();

        if (Vulkan.ENABLE_VALIDATION_LAYERS) {

            MemoryStack stack = stackGet();

            PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);

            extensions.put(glfwExtensions);
            extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));

            return extensions.rewind();
        }

        return glfwExtensions;
    }

    private static boolean isDeviceSuitable(VkPhysicalDevice device) {
        try (MemoryStack stack = stackPush()) {
            Queue.QueueFamilyIndices indices = findQueueFamilies(device);

            boolean extensionsSupported = Device.getAvailableExtensions(device)
                    .containsAll(Vulkan.REQUIRED_EXTENSION);

            boolean swapChainAdequate = false;

            if (extensionsSupported) {
                SurfaceProperties surfaceProperties = querySurfaceProperties(device, stack);
                swapChainAdequate = surfaceProperties.formats.hasRemaining() && surfaceProperties.presentModes.hasRemaining();
            }

            VkPhysicalDeviceFeatures supportedFeatures = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(device, supportedFeatures);
            boolean anisotropicFilterSupported = supportedFeatures.samplerAnisotropy();

            return indices.isSuitable() && extensionsSupported && swapChainAdequate;
        }
    }

    public static int findDepthFormat(boolean use24BitsDepthFormat) {

        int[] formats = use24BitsDepthFormat ? new int[]
                {VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D32_SFLOAT}
                : new int[]{VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D32_SFLOAT};

        return findSupportedFormat(
                VK_IMAGE_TILING_OPTIMAL,
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT,
                MainDepthSnapshot.depthAttachmentUsage(),
                formats);
    }

    private static int findSupportedFormat(int tiling, int features, int usage, int... formatCandidates) {
        try (MemoryStack stack = stackPush()) {

            VkFormatProperties props = VkFormatProperties.calloc(stack);
            VkImageFormatProperties imageProps = VkImageFormatProperties.calloc(stack);

            for (int format : formatCandidates) {

                vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props);

                boolean supportedFeatures = tiling == VK_IMAGE_TILING_LINEAR
                        ? (props.linearTilingFeatures() & features) == features
                        : tiling == VK_IMAGE_TILING_OPTIMAL && (props.optimalTilingFeatures() & features) == features;
                if (supportedFeatures) {
                    int result = vkGetPhysicalDeviceImageFormatProperties(physicalDevice, format, VK_IMAGE_TYPE_2D,
                            tiling, usage, 0, imageProps);
                    if (result == VK_SUCCESS && (imageProps.sampleCounts() & VK_SAMPLE_COUNT_1_BIT) != 0) {
                        return format;
                    }
                }

            }
        }

        throw new RuntimeException("Failed to find supported format");
    }

    public static String getAvailableDevicesInfo() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("\n");

        if (availableDevices == null) {
            stringBuilder.append("\tDevice Manager not initialized");
            return stringBuilder.toString();
        }

        if (availableDevices.isEmpty()) {
            stringBuilder.append("\tNo available device found");
        }

        for (Device device : availableDevices) {
            stringBuilder.append("\tDevice: %s\n".formatted(device.deviceName));

            stringBuilder.append("\t\tVulkan Version: %s\n".formatted(device.vkVersion));

            stringBuilder.append("\t\t");
            var unsupportedExtensions = device.getUnsupportedExtensions(Vulkan.REQUIRED_EXTENSION);
            if (unsupportedExtensions.isEmpty()) {
                stringBuilder.append("All required extensions are supported\n");
            } else {
                stringBuilder.append("Unsupported extension: %s\n".formatted(unsupportedExtensions));
            }
        }

        return stringBuilder.toString();
    }

    public static void destroy() {
        try {
            graphicsQueue.cleanUp();
            transferQueue.cleanUp();
            computeQueue.cleanUp();

            vkDestroyDevice(vkDevice, null);
        } finally {
            vkDevice = null;
            tessellationEnabled = false;
            GlPatchState.configureLimit(0);
        }
    }

    public static GraphicsQueue getGraphicsQueue() {
        return graphicsQueue;
    }

    public static PresentQueue getPresentQueue() {
        return presentQueue;
    }

    public static TransferQueue getTransferQueue() {
        return transferQueue;
    }

    public static ComputeQueue getComputeQueue() {
        return computeQueue;
    }

    public static SurfaceProperties querySurfaceProperties(VkPhysicalDevice device, MemoryStack stack) {

        long surface = Vulkan.getSurface();
        SurfaceProperties details = new SurfaceProperties();

        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities);

        IntBuffer count = stack.ints(0);

        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null);

        if (count.get(0) != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, details.formats);
        }

        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, null);

        if (count.get(0) != 0) {
            details.presentModes = stack.mallocInt(count.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, details.presentModes);
        }

        return details;
    }

    public static class SurfaceProperties {
        public VkSurfaceCapabilitiesKHR capabilities;
        public VkSurfaceFormatKHR.Buffer formats;
        public IntBuffer presentModes;
    }

}

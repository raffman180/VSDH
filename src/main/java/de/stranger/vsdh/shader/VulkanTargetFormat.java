package de.stranger.vsdh.shader;

import dev.vitrail.pack.model.TargetFormat;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;

import static org.lwjgl.vulkan.VK10.*;

/** Explicit mapping: these are VkFormats, not OpenGL internal-format numbers. */
public final class VulkanTargetFormat {
    private static final java.util.Set<Integer> INTEGER_FORMATS = java.util.Arrays.stream(TargetFormat.values())
            .filter(TargetFormat::integer).map(VulkanTargetFormat::of).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private VulkanTargetFormat() {}
    public static boolean isInteger(int format) { return INTEGER_FORMATS.contains(format); }

    public static int of(TargetFormat format) {
        return switch (format) {
            case R8_UNORM -> VK_FORMAT_R8_UNORM;
            case R8_SNORM -> VK_FORMAT_R8_SNORM;
            case RG8_UNORM -> VK_FORMAT_R8G8_UNORM;
            case RG8_SNORM -> VK_FORMAT_R8G8_SNORM;
            case RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;
            case RGBA8_SNORM -> VK_FORMAT_R8G8B8A8_SNORM;
            case R16_UNORM -> VK_FORMAT_R16_UNORM;
            case R16_SNORM -> VK_FORMAT_R16_SNORM;
            case RG16_UNORM -> VK_FORMAT_R16G16_UNORM;
            case RG16_SNORM -> VK_FORMAT_R16G16_SNORM;
            case RGBA16_UNORM -> VK_FORMAT_R16G16B16A16_UNORM;
            case RGBA16_SNORM -> VK_FORMAT_R16G16B16A16_SNORM;
            case R8_UINT -> VK_FORMAT_R8_UINT;
            case R8_SINT -> VK_FORMAT_R8_SINT;
            case RG8_UINT -> VK_FORMAT_R8G8_UINT;
            case RG8_SINT -> VK_FORMAT_R8G8_SINT;
            case RGBA8_UINT -> VK_FORMAT_R8G8B8A8_UINT;
            case RGBA8_SINT -> VK_FORMAT_R8G8B8A8_SINT;
            case R16_UINT -> VK_FORMAT_R16_UINT;
            case R16_SINT -> VK_FORMAT_R16_SINT;
            case RG16_UINT -> VK_FORMAT_R16G16_UINT;
            case RG16_SINT -> VK_FORMAT_R16G16_SINT;
            case RGBA16_UINT -> VK_FORMAT_R16G16B16A16_UINT;
            case RGBA16_SINT -> VK_FORMAT_R16G16B16A16_SINT;
            case R32_UINT -> VK_FORMAT_R32_UINT;
            case R32_SINT -> VK_FORMAT_R32_SINT;
            case RG32_UINT -> VK_FORMAT_R32G32_UINT;
            case RG32_SINT -> VK_FORMAT_R32G32_SINT;
            case RGBA32_UINT -> VK_FORMAT_R32G32B32A32_UINT;
            case RGBA32_SINT -> VK_FORMAT_R32G32B32A32_SINT;
            case R16_FLOAT -> VK_FORMAT_R16_SFLOAT;
            case RG16_FLOAT -> VK_FORMAT_R16G16_SFLOAT;
            case RGBA16_FLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case R32_FLOAT -> VK_FORMAT_R32_SFLOAT;
            case RG32_FLOAT -> VK_FORMAT_R32G32_SFLOAT;
            case RGBA32_FLOAT -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case RGB10A2_UNORM -> VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case RGB10A2_UINT -> VK_FORMAT_A2B10G10R10_UINT_PACK32;
            case RG11B10_FLOAT -> VK_FORMAT_B10G11R11_UFLOAT_PACK32;
        };
    }

    public static void requireRenderable(org.lwjgl.vulkan.VkPhysicalDevice device, TargetFormat format, boolean linear) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(device, of(format), properties);
            int required = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            if (linear) required |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if ((properties.optimalTilingFeatures() & required) != required)
                throw new IllegalArgumentException("Device cannot render/sample shader target " + format + " with linear=" + linear);
        }
    }
}

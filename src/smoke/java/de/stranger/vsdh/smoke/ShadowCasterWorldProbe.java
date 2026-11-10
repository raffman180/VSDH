package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.NativeWorldShadows;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;

final class ShadowCasterWorldProbe {
    private static boolean verified;

    static void arm(NativeWorldShadows shadows) {
        shadows.casterDepthObserver(new java.util.function.BiConsumer<>() {
            private ByteBuffer terrain;

            @Override
            public void accept(VulkanImage depth, Boolean after) {
                if (!after) {
                    terrain = read(depth);
                    return;
                }
                var combined = read(depth);
                int changed = 0;
                try {
                    for (int i = 0; i < combined.capacity(); i += 4)
                        if (combined.getFloat(i) < terrain.getFloat(i)) changed++;
                } finally {
                    MemoryUtil.memFree(terrain);
                    MemoryUtil.memFree(combined);
                }
                shadows.casterDepthObserver(null);
                if (shadows.entityDraws() == 0 || shadows.blockDraws() == 0 || changed < 8)
                    throw new AssertionError("Missing shadow casters: entities=" + shadows.entityDraws()
                            + ", blocks=" + shadows.blockDraws() + ", changed=" + changed);
                verified = true;
                LoggerFactory.getLogger("VSDH-Smoke").info("MODEL SHADOW WORLD PASS: entities={}, blocks={}, addedDepthPixels={}",
                        shadows.entityDraws(), shadows.blockDraws(), changed);
            }
        });
    }

    private static ByteBuffer read(VulkanImage depth) {
        var bytes = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(depth.width, depth.height), 4));
        ImageUtil.downloadTextureRegion(depth, MemoryUtil.memAddress(bytes), 0, 0,
                depth.width, depth.height, VK_IMAGE_ASPECT_DEPTH_BIT, 4);
        return bytes;
    }

    static void requireVerified() {
        if (!verified) throw new AssertionError("Model shadow probe was skipped");
    }
}

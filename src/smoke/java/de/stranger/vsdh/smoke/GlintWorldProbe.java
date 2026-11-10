package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.WorldShaderSession;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

final class GlintWorldProbe {
    private static boolean verified;

    static void arm(WorldShaderSession session) {
        session.glintObserver(new java.util.function.BiConsumer<>() {
            private ByteBuffer base;
            private int attempts;

            @Override
            public void accept(VulkanImage color, Boolean after) {
                if (!after) {
                    base = read(color);
                    return;
                }
                var glint = read(color);
                int changed = 0;
                try {
                    for (int i = 0; i < glint.capacity(); i += color.formatSize) {
                        for (int c = 0; c < color.formatSize; c++) {
                            if (base.get(i + c) != glint.get(i + c)) {
                                changed++;
                                break;
                            }
                        }
                    }
                } finally {
                    MemoryUtil.memFree(base);
                    MemoryUtil.memFree(glint);
                }
                if (changed == 0 && ++attempts < 24) return;
                session.glintObserver(null);
                if (changed == 0) throw new AssertionError("Pack glint never reached the held item's depth after " + attempts + " frames");
                verified = true;
                LoggerFactory.getLogger("VSDH-Smoke").info("GLINT WORLD PASS: draws={}, changedPixels={}", session.glintDraws(), changed);
            }
        });
    }

    private static ByteBuffer read(VulkanImage color) {
        var bytes = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(color.width, color.height), color.formatSize));
        ImageUtil.downloadTexture(color, MemoryUtil.memAddress(bytes));
        return bytes;
    }

    static void requireVerified() {
        if (!verified) throw new AssertionError("Held item glint probe was skipped");
    }
}

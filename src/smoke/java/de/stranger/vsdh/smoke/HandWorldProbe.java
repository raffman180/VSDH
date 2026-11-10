package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.WorldShaderSession;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;

final class HandWorldProbe {
    static void verify(WorldShaderSession session) {
        if (session.handDraws(false) == 0 || session.handDraws(true) == 0)
            throw new AssertionError("Both native hand phases must draw: solid=" + session.handDraws(false)
                    + ", translucent=" + session.handDraws(true));
        var before = session.worldDepth("depthtex2");
        var after = session.worldDepth("depthtex1");
        int pixels = Math.multiplyExact(before.width, before.height);
        var a = MemoryUtil.memAlloc(Math.multiplyExact(pixels, 4));
        var b = MemoryUtil.memAlloc(a.capacity());
        int changed = 0;
        try (var scope = Renderer.getInstance().temporaryRenderTarget()) {
            ImageUtil.downloadTextureRegion(before, MemoryUtil.memAddress(a), 0, 0, before.width, before.height, VK_IMAGE_ASPECT_DEPTH_BIT, 4);
            ImageUtil.downloadTextureRegion(after, MemoryUtil.memAddress(b), 0, 0, after.width, after.height, VK_IMAGE_ASPECT_DEPTH_BIT, 4);
            for (int i = 0; i < pixels; i++) if (a.getInt(i * 4) != b.getInt(i * 4)) changed++;
        } finally {
            MemoryUtil.memFree(b);
            MemoryUtil.memFree(a);
        }
        if (changed < 32) throw new AssertionError("Solid hand depth missing between snapshots: " + changed);
        LoggerFactory.getLogger("VSDH-Smoke").info("HAND WORLD PASS: solid={}, translucent={}, changedDepthPixels={}",
                session.handDraws(false), session.handDraws(true), changed);
    }
}

package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.WorldShaderSession;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;

/** Checks the two actual depth images written by the original pack over the live DH mesh. */
final class DistantWorldProbe {
    static void verify(WorldShaderSession session) {
        var distant = session.distant();
        if (distant == null) throw new AssertionError("DH pack graph is absent");
        if (distant.terrainDraws() == 0 || distant.waterDraws() == 0)
            throw new AssertionError("DH pack draws missing: opaque=" + distant.terrainDraws() + ", water=" + distant.waterDraws());
        var full = distant.image("dhDepthTex0");
        var solid = distant.image("dhDepthTex1");
        if (full == solid || full.format != VK_FORMAT_D32_SFLOAT || solid.format != full.format
                || full.width != solid.width || full.height != solid.height)
            throw new AssertionError("DH full and opaque depths must be separate matching targets");
        int pixels = Math.multiplyExact(full.width, full.height);
        var a = MemoryUtil.memAlloc(Math.multiplyExact(pixels, 4));
        var b = MemoryUtil.memAlloc(a.capacity());
        try (var scope = Renderer.getInstance().temporaryRenderTarget()) {
            ImageUtil.downloadTexture(full, MemoryUtil.memAddress(a));
            ImageUtil.downloadTexture(solid, MemoryUtil.memAddress(b));
            int land = 0, water = 0;
            for (int i = 0; i < pixels; i++) {
                float all = a.getFloat(i * 4), opaque = b.getFloat(i * 4);
                if (!Float.isFinite(all) || !Float.isFinite(opaque) || all < 0 || opaque > 1 || all > opaque + 1e-5f)
                    throw new AssertionError("Invalid DH depth at " + i + ": " + all + "/" + opaque);
                if (opaque < .99999f) land++;
                if (all < opaque - 1e-5f) water++;
            }
            if (land < 32 || water < 32) throw new AssertionError("DH shader depth coverage missing: opaque=" + land + ", water=" + water);
            LoggerFactory.getLogger("VSDH-Smoke").info(
                    "DH PACK GPU PASS: terrainDraws={}, waterDraws={}, opaquePixels={}, waterPixels={}, size={}x{}",
                    distant.terrainDraws(), distant.waterDraws(), land, water, full.width, full.height);
        } finally {
            MemoryUtil.memFree(a);
            MemoryUtil.memFree(b);
        }
    }
}

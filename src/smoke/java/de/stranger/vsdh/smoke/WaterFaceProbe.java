package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.WorldShaderSession;
import dev.vitrail.pack.target.TargetSchedule.Side;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

/** Reads the encoded normals of the flat ocean surface in the fixed smoke camera view. */
final class WaterFaceProbe {
    static void verify(WorldShaderSession session) {
        var image = session.chain().targets().image(1, Side.MAIN);
        int x = image.width * 3 / 8;
        int y = image.height * 5 / 6;
        int width = image.width / 4;
        int height = image.height / 8;
        int pixels = Math.multiplyExact(width, height);
        var bytes = MemoryUtil.memAlloc(Math.multiplyExact(pixels, 8));
        try (var scope = Renderer.getInstance().temporaryRenderTarget()) {
            ImageUtil.downloadTextureRegion(image, MemoryUtil.memAddress(bytes), x, y, width, height, image.aspect, 8);
            int up = 0, down = 0;
            for (int offset = 0; offset < bytes.capacity(); offset += 8) {
                float nx = NativePassGpuCheck.halfFloat(bytes.getShort(offset));
                float ny = NativePassGpuCheck.halfFloat(bytes.getShort(offset + 2));
                float nz = NativePassGpuCheck.halfFloat(bytes.getShort(offset + 4));
                if (Math.abs(nx - .5f) > .025f || Math.abs(nz - .5f) > .025f) continue;
                if (ny > .975f) up++;
                if (ny < .025f) down++;
            }
            if (up < pixels * 9 / 10 || down > pixels / 100)
                throw new AssertionError("Wrong visible water face: up=" + up + ", down=" + down + ", pixels=" + pixels);
            LoggerFactory.getLogger("VSDH-Smoke").info(
                    "WATER FACE GPU PASS: upward={}, downward={}, sampled={}, size={}x{}", up, down, pixels, image.width, image.height);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }
}

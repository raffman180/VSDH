package de.stranger.vsdh.shader;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.source.OpenedPack;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.lwjgl.vulkan.VK10.*;

/** Reads the production mesh conversion with both UV orientations and multiple IDs in one draw. */
public final class EntityContractGpuCheck {
    private static CompletableFuture<PackProgramSource.Prepared> pending;

    private EntityContractGpuCheck() {}

    public static void prepare() {
        if (pending != null) return;
        pending = CompletableFuture.supplyAsync(() -> {
            try {
                var source = Path.of(EntityContractGpuCheck.class.getResource("/shaderpacks/vsdh-entity-contract").toURI());
                try (var pack = OpenedPack.open(source, Map.of(), "")) {
                    return PackProgramSource.prepare(pack, "gbuffers_entities", VertexInputs.VULKAN_ENTITY);
                }
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
    }

    public static boolean ready() { return pending != null && pending.isDone(); }

    public static void run() {
        int stride = NativeEntityMesh.FORMAT.getVertexSize();
        if (stride != 56) throw new AssertionError("Entity stride: " + stride);
        var framebuffer = Framebuffer.builder(6, 4, 1, false).setFormat(VK_FORMAT_R16G16B16A16_SFLOAT).build();
        var renderPass = RenderPass.builder(framebuffer).build();
        var pixels = MemoryUtil.memAlloc(6 * 4 * 8);
        try (var scope = Renderer.getInstance().temporaryRenderTarget();
             var program = new Program(pending.join());
             var memory = new ByteBufferBuilder(8 * DefaultVertexFormat.NEW_ENTITY.getVertexSize());
             var extended = new NativeEntityMesh()) {
            var builder = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            writeQuad(builder, -1, 0xFFFA8040, .875f, .375f);
            writeQuad(builder, 0, 0xFFFA80C0, .375f, .875f);
            short[] ids = {601, 601, 601, 601, -1, -1, -1, -1};
            try (var vanilla = builder.buildOrThrow()) {
                var state = new MeshData.DrawState(NativeEntityMesh.FORMAT, 8, 12,
                        VertexFormat.Mode.QUADS, VertexFormat.IndexType.SHORT);
                program.layout().putFloats(program.uniforms(), "of_ModelViewProjectionMatrix", 0, new Matrix4f().get(new float[16]));
                program.layout().putFloats(program.uniforms(), "of_DepthConv", 0, .5f, .5f, 1, 0);
                for (int type = 0; type < 2; type++) {
                    var vertices = extended.extend(vanilla, ids, type == 1);
                    program.layout().putInts(program.uniforms(), "testBlockEntities", 0, type);
                    program.render(framebuffer, renderPass, vertices, state);
                    ImageUtil.downloadTexture(framebuffer.getColorAttachment(), MemoryUtil.memAddress(pixels));
                    for (int pixel = 0; pixel < 24; pixel++) for (int channel = 0; channel < 4; channel++) {
                        short value = pixels.getShort(pixel * 8 + channel * 2);
                        if (value != 0x3C00) throw new AssertionError("Entity mesh conversion: type " + type + ", pixel " + pixel
                                + ", channel " + channel + ", half-float bits " + Integer.toHexString(Short.toUnsignedInt(value)));
                    }
                }
            }
        } finally {
            MemoryUtil.memFree(pixels);
            renderPass.cleanUp();
            framebuffer.cleanUp();
        }
        LoggerFactory.getLogger("VSDH-Smoke").info("ENTITY CONTRACT GPU PASS: mob and block entity IDs use separate lanes, both UV orientations, light and tangent frame");
    }

    private static void writeQuad(BufferBuilder builder, float x, int color, float bottomV, float topV) {
        builder.addVertex(x, -1, 0, color, .25f, bottomV, 0, 0x00F00080, 0, 0, 1);
        builder.addVertex(x + 1, -1, 0, color, .5f, bottomV, 0, 0x00F00080, 0, 0, 1);
        builder.addVertex(x + 1, 1, 0, color, .5f, topV, 0, 0x00F00080, 0, 0, 1);
        builder.addVertex(x, 1, 0, color, .25f, topV, 0, 0x00F00080, 0, 0, 1);
    }

    private static final class Program extends NativeGraphicsProgram {
        private Program(PackProgramSource.Prepared source) {
            super(source.path(), source.translated(), source.compiled(), NativeEntityMesh.FORMAT);
        }

        private void render(Framebuffer framebuffer, RenderPass pass, java.nio.ByteBuffer vertices, MeshData.DrawState mesh) {
            int assembly = PipelineState.AssemblyRasterState.encode(false, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);
            var state = new PipelineState(assembly, 0, 0, 0, 0, 15, pass);
            renderDraw(framebuffer, pass, state, command -> Renderer.getDrawer().draw(vertices, mesh, null));
        }
    }
}

package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.*;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.id.BlockIds;
import dev.vitrail.pack.source.OpenedPack;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.VertexBuffer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.lwjgl.vulkan.VK10.*;

/** Real worker-format -> Vulkan vertex fetch -> translated GLSL -> MRT readback. No user world edits. */
final class TerrainGpuCheck {
    private record Prepared(List<PackProgramSource.Prepared> fixtures,
                            List<PackProgramSource.Prepared> complementary, List<BlockIds.Entry> materials) {}
    private static CompletableFuture<Prepared> pending;

    static void prepare() {
        if (pending != null) return;
        pending = CompletableFuture.supplyAsync(() -> {
            try {
                var fixture = Path.of(TerrainGpuCheck.class.getResource("/shaderpacks/vsdh-terrain").toURI());
                List<PackProgramSource.Prepared> fixtures = new ArrayList<>();
                try (var pack = OpenedPack.open(fixture, Map.of(), "")) {
                    for (var contract : java.util.Arrays.stream(VertexInputs.values()).filter(VertexInputs::vulkanTerrain).toList())
                        fixtures.add(PackProgramSource.prepare(pack, "gbuffers_terrain", contract));
                }
                List<PackProgramSource.Prepared> real = new ArrayList<>();
                List<BlockIds.Entry> materials = List.of();
                String external = System.getProperty("vsdh.smoke.pack");
                if (external != null) try (var pack = OpenedPack.open(Path.of(external), Map.of(), "MEDIUM")) {
                    materials = BlockIds.read(pack.source(), pack.settings().globalDefines(pack.options())).entries();
                    for (String dimension : List.of("world0", "world-1", "world1"))
                        for (String program : List.of("gbuffers_terrain", "gbuffers_water", "shadow"))
                            for (var contract : java.util.Arrays.stream(VertexInputs.values()).filter(VertexInputs::vulkanTerrain).toList())
                                real.add(PackProgramSource.prepare(pack, dimension + "/" + program, contract));
                }
                return new Prepared(List.copyOf(fixtures), List.copyOf(real), List.copyOf(materials));
            } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
    }
    static boolean ready() { return pending != null && pending.isDone(); }
    static ShaderBlockMaterials materials() { return ShaderBlockMaterials.resolve(pending.join().materials); }

    static void run() {
        Prepared prepared = pending.join();
        checkMaterials(prepared.materials);
        try (var scope = Renderer.getInstance().temporaryRenderTarget()) {
            for (var fixture : prepared.fixtures)
                check(fixture, fixture.translated().inputs().separateAo(), 8 + (fixture.translated().inputs().oldLighting() ? 3 : 0), 7);
            prepareComplementary(prepared.complementary);
        }
        LoggerFactory.getLogger("VSDH-Smoke").info("TERRAIN GPU PASS: real 44-byte mesh, all four oldLighting/separateAo policies, normals/tangents, material/fluid/light, midpoints, nonzero section offsets; {} Complementary terrain/water/shadow Vk pipelines prepared", prepared.complementary.size());
    }

    private static void check(PackProgramSource.Prepared source, boolean separate, int width, int height) {
        var framebuffer = Framebuffer.builder(width, height, 4, true).setFormat(VK_FORMAT_R16G16B16A16_SFLOAT).build();
        var builder = RenderPass.builder(framebuffer);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        var renderPass = builder.build();
        VertexBuffer vertices = new VertexBuffer(4 * ShaderTerrainVertex.STRIDE);
        IndexBuffer indices = new IndexBuffer(12, MemoryTypes.HOST_MEM);
        ByteBuffer indexData = MemoryUtil.memAlloc(12), pixels = MemoryUtil.memAlloc(width * height * 8);
        TerrainBufferBuilder mesh = new TerrainBufferBuilder(4 * ShaderTerrainVertex.STRIDE, CustomVertexFormat.SHADER_TERRAIN, state -> 33554559);
        try (var program = new NativeTerrainPass(source.path(), source.translated(), source.compiled())) {
            mesh.begin();
            mesh.setBlockAttributes(Blocks.WATER.defaultBlockState(), true, 15, 0,0,0);
            mesh.ensureCapacity();
            for (int i = 0; i < 4; i++) {
                float x = i == 1 || i == 2 ? 1 : 0, y = i >= 2 ? 1 : 0;
                mesh.vertex(x, y, y, 0xff204080, x, y, 0x00f00080, 0, 0x804080c0, .6f);
            }
            var built = mesh.end();
            vertices.copyToVertexBuffer(ShaderTerrainVertex.STRIDE, 4, built.vertexBuffer());
            built.release();
            for (short index : new short[]{0,1,2,2,3,0}) indexData.putShort(index);
            indexData.flip(); indices.copyBuffer(indexData);
            for (var member : program.layout().members())
                if (member.name().equals("of_DepthConv")) program.layout().putFloats(program.uniforms(), member.name(), 0, .5f,.5f,1,0);
            program.prepare(renderPass);
            for (int mode = 0; mode < 2; mode++) {
                NativeImageOps.clearDepth(framebuffer.getDepthAttachment(), 1, 0);
                program.layout().putInts(program.uniforms(), "testMode", 0, mode);
                // Reforged's firstInstance packs section X,Z,Y in bytes. Offset cancels it here.
                program.render(framebuffer, renderPass, vertices, indices, 6, 0, 0,
                        17 | 9 << 8 | 33 << 16, 4-17, 4-33, 4-9);
                for (int attachment = 0; attachment < 4; attachment++) {
                    ImageUtil.downloadTexture(framebuffer.getColorAttachment(attachment), MemoryUtil.memAddress(pixels));
                    for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
                        float x = (col + .5f) / width, y = (row + .5f) / height;
                        float[] expected = expected(mode, attachment, separate, source.translated().inputs().oldLighting(), x, y);
                        for (int channel = 0; channel < 4; channel++) {
                            float actual = NativePassGpuCheck.halfFloat(pixels.getShort(((row * width + col) * 4 + channel) * 2));
                            near(expected[channel], actual, .001f, "terrain mode=" + mode + " target=" + attachment + " pixel=" + col + "," + row + " channel=" + channel);
                        }
                    }
                }
            }
        } finally {
            mesh.close(); vertices.freeBuffer(); indices.freeBuffer();
            MemoryUtil.memFree(indexData); MemoryUtil.memFree(pixels);
            renderPass.cleanUp(); framebuffer.cleanUp();
        }
    }

    private static float[] expected(int mode, int attachment, boolean separate, boolean oldLighting, float x, float y) {
        float alpha = separate ? 128f/255 : 1;
        float tintScale = oldLighting ? .6f : separate ? 1 : 128f/255;
        return switch (attachment) {
            case 0 -> mode == 0 ? new float[]{.5f, .5f-(float)Math.sqrt(.5)*.5f, .5f+(float)Math.sqrt(.5)*.5f, alpha}
                    : oldLighting && !separate ? new float[]{128f/255,64f/255,32f/255,alpha}
                    : new float[]{192f/255*tintScale,128f/255*tintScale,64f/255*tintScale,alpha};
            case 1 -> mode == 0 ? new float[]{1,.5f,.5f,1} : new float[]{128f/255,240f/255,0,1};
            case 2 -> new float[]{mode == 0 ? 33554559f/67108864 : 127f/255,1,.5f,.5f};
            case 3 -> new float[]{.75f-x*.5f,.75f-y*.5f,.75f-y*.5f,1};
            default -> throw new AssertionError(attachment);
        };
    }

    private static void checkMaterials(List<BlockIds.Entry> packEntries) {
        var materials = ShaderBlockMaterials.resolve(List.of(
                new BlockIds.Entry(false,"minecraft","oak_log",Map.of("axis","x,z"),9102),
                new BlockIds.Entry(false,"minecraft","oak_log",Map.of(),9103),
                new BlockIds.Entry(false,"minecraft","does_not_exist",Map.of(),9104)));
        if (materials.applyAsInt(Blocks.OAK_LOG.defaultBlockState()) != 9103
                || materials.applyAsInt(Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X)) != 9102
                || materials.applyAsInt(Blocks.AIR.defaultBlockState()) != -1)
            throw new AssertionError("Material predicates/order/missing block do not match the registry");
        if (!packEntries.isEmpty()) {
            var real = ShaderBlockMaterials.resolve(packEntries);
            if (real.mappedStates() == 0 || real.applyAsInt(Blocks.WATER.defaultBlockState()) < 0)
                throw new AssertionError("Complementary block.properties did not map actual world blocks/water");
        }
    }

    private static void prepareComplementary(List<PackProgramSource.Prepared> programs) {
        var target = Framebuffer.builder(16, 16, 4, true).setFormat(VK_FORMAT_R16G16B16A16_SFLOAT).build();
        var pass = RenderPass.builder(target).build();
        try {
            for (var source : programs) try (var program = new NativeTerrainPass(source.path(), source.translated(), source.compiled())) {
                program.prepare(pass);
            }
        } finally { pass.cleanUp(); target.cleanUp(); }
    }
    private static void near(float expected, float actual, float tolerance, String label) {
        if (!Float.isFinite(actual) || Math.abs(expected-actual) > tolerance)
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
    }
}

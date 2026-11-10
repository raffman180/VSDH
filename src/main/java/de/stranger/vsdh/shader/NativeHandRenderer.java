package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SolidBucketItem;
import org.joml.Matrix4f;

import java.util.Map;

/** Reuses the game's first-person animation in isolated, phase-specific draw batches. */
public final class NativeHandRenderer implements AutoCloseable {
    public interface GameAccess {
        boolean vsdh$renderHandEnabled();
        void vsdh$renderItemInHand(Camera camera, float partialTick, Matrix4f view);
    }

    private final ShaderBlockMaterials blocks;
    private final Map<String, Integer> items;
    private final Matrix4f view = new Matrix4f();
    private final ByteBufferBuilder storage = new ByteBufferBuilder(4096);
    private final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(storage);
    private Camera camera;
    private float partialTick;
    private boolean drawing, late, replaced, needsLate;
    private long identifiers;

    NativeHandRenderer(ShaderBlockMaterials blocks, Map<String, Integer> items) {
        this.blocks = blocks;
        this.items = items;
    }

    void resetFrame() {
        camera = null;
        replaced = false;
        needsLate = false;
    }

    void beginFrame(Camera camera, Matrix4f view, float partialTick) {
        this.camera = camera;
        this.view.set(view);
        this.partialTick = partialTick;
    }

    void draw(boolean late) {
        if (camera == null || late && !needsLate) return;
        var game = (GameAccess) Minecraft.getInstance().gameRenderer;
        if (!game.vsdh$renderHandEnabled()) return;
        var projection = RenderSystem.getProjectionMatrix();
        var sorting = RenderSystem.getVertexSorting();
        var shader = RenderSystem.getShader();
        int lightmap = RenderSystem.getShaderTexture(2);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();
        this.late = late;
        drawing = true;
        try {
            game.vsdh$renderItemInHand(camera, partialTick, view);
        } finally {
            drawing = false;
            identifiers = 0;
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShaderTexture(2, lightmap);
            RenderSystem.setShader(() -> shader);
        }
    }

    public boolean drawing() { return drawing; }
    public boolean late() { return late; }
    public boolean replaced() { return replaced; }
    public long identifiers() { return identifiers; }
    public boolean owns(MultiBufferSource source) { return source == buffers; }

    public MultiBufferSource.BufferSource useBuffers() {
        replaced = true;
        return buffers;
    }

    public boolean skipHand(ItemStack stack) {
        boolean translucent = stack.getItem() instanceof BlockItem item
                && ItemBlockRenderTypes.getChunkRenderType(item.getBlock().defaultBlockState()) == RenderType.translucent();
        needsLate |= translucent;
        return translucent != late;
    }

    public long beginItem(ItemStack stack) {
        buffers.endBatch();
        long previous = identifiers;
        if (stack.getItem() instanceof BlockItem block && !(stack.getItem() instanceof SolidBucketItem)) {
            int id = blocks.applyAsInt(block.getBlock().defaultBlockState());
            identifiers = 1L << 16 | (long) Math.max(0, id) << 32;
        } else {
            int id = items.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), -1);
            identifiers = (id & 0xffffL) << 32;
        }
        return previous;
    }

    public void endItem(long previous) {
        try {
            buffers.endBatch();
        } finally {
            identifiers = previous;
        }
    }

    @Override
    public void close() {
        storage.close();
    }
}

package de.stranger.vsdh.shader;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.LinkedHashMap;
import java.util.List;

/** Isolated batches with the parallel vertex sinks used for a model's enchanted overlay. */
final class NativeModelBuffers implements AutoCloseable {
    private final ByteBufferBuilder shared;
    private final LinkedHashMap<RenderType, ByteBufferBuilder> glint = new LinkedHashMap<>();
    final MultiBufferSource.BufferSource source;

    NativeModelBuffers(int capacity) {
        shared = new ByteBufferBuilder(capacity);
        try {
            for (var type : List.of(RenderType.armorEntityGlint(), RenderType.glint(), RenderType.glintTranslucent(),
                    RenderType.entityGlint(), RenderType.entityGlintDirect())) glint.put(type, new ByteBufferBuilder(type.bufferSize()));
            source = MultiBufferSource.immediateWithBuffers(glint, shared);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    @Override
    public void close() {
        glint.values().forEach(ByteBufferBuilder::close);
        shared.close();
    }
}

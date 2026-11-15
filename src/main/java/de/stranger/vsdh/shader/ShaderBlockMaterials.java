package de.stranger.vsdh.shader;

import dev.vitrail.pack.id.BlockIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Immutable, pack-specific material snapshot; safe to share with terrain build workers. */
public final class ShaderBlockMaterials implements ToIntFunction<BlockState> {
    private final Map<BlockState, Integer> ids;
    private ShaderBlockMaterials(Map<BlockState, Integer> ids) {
        this.ids = Collections.unmodifiableMap(new IdentityHashMap<>(ids));
    }

    /** Build once after registry/tag binding. Rebuild alongside meshes when the pack or tags change. */
    public static ShaderBlockMaterials resolve(List<BlockIds.Entry> entries) {
        Map<BlockState, Integer> ids = new IdentityHashMap<>();
        for (var entry : entries) {
            ResourceLocation name = ResourceLocation.tryParse(entry.name());
            if (name == null) {
                // A name no registry can hold matches no block, which is also how Iris treats it.
                org.slf4j.LoggerFactory.getLogger("VSDH").warn("Ignoring invalid block material name: {}", entry.name());
                continue;
            }
            if (entry.tag()) {
                var tag = TagKey.create(Registries.BLOCK, name);
                for (Block block : BuiltInRegistries.BLOCK) {
                    for (BlockState state : block.getStateDefinition().getPossibleStates())
                        if (state.is(tag) && matches(state, entry.predicates())) ids.putIfAbsent(state, entry.id());
                }
            } else {
                // Packs commonly list optional mods and newer blocks. Missing registry entries stay unmapped.
                BuiltInRegistries.BLOCK.getOptional(name).ifPresent(block -> {
                    for (BlockState state : block.getStateDefinition().getPossibleStates())
                        if (matches(state, entry.predicates())) ids.putIfAbsent(state, entry.id());
                });
            }
        }
        return new ShaderBlockMaterials(ids);
    }

    private static boolean matches(BlockState state, Map<String, String> predicates) {
        for (var predicate : predicates.entrySet()) {
            var property = state.getBlock().getStateDefinition().getProperty(predicate.getKey());
            if (property == null) return false;
            String actual = value(state, property);
            boolean matched = false;
            for (String allowed : predicate.getValue().split(",")) if (actual.equals(allowed)) { matched = true; break; }
            if (!matched) return false;
        }
        return true;
    }
    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
    @Override public int applyAsInt(BlockState state) { return ids.getOrDefault(state, -1); }
    public int mappedStates() { return ids.size(); }
}

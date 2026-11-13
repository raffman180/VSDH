package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.OpenedPack;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CPU-prepared programs for the three vanilla dimension IDs. */
public record WorldPackSet(Map<String, WorldPackPlan> dimensions, WorldPackPlan overworld, boolean vignette, boolean underwaterOverlay) {
    public WorldPackSet(Map<String, WorldPackPlan> dimensions, WorldPackPlan overworld) {
        this(dimensions, overworld, false, false);
    }

    private static final List<String> VANILLA = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    public WorldPackSet {
        dimensions = Map.copyOf(dimensions);
        for (var plan : dimensions.values()) {
            if (!plan.materials().equals(overworld.materials()))
                throw new IllegalArgumentException("All dimensions must share the terrain material table");
        }
    }

    public static WorldPackSet single(WorldPackPlan plan) {
        return new WorldPackSet(Map.of(), plan);
    }

    public static WorldPackSet prepare(OpenedPack pack, boolean distant) throws IOException {
        var mapping = DimensionSet.discover(pack.source());
        var byFolder = new HashMap<String, WorldPackPlan>();
        var byDimension = new LinkedHashMap<String, WorldPackPlan>();
        for (String dimension : VANILLA) {
            String folder = mapping.place(dimension);
            var plan = byFolder.get(folder);
            if (plan == null) {
                plan = WorldPackPlan.prepare(pack, folder, distant);
                byFolder.put(folder, plan);
            }
            byDimension.put(dimension, plan);
        }
        var defines = pack.settings().globalDefines(pack.options());
        return new WorldPackSet(byDimension, byDimension.get(VANILLA.getFirst()),
                pack.properties().vignette(defines), pack.properties().underwaterOverlay(defines));
    }

    WorldPackPlan forDimension(String dimension) { return dimensions.getOrDefault(dimension, overworld); }
}

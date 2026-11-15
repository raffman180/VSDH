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
        try {
            // With compile workers every dimension's programs are queued before the first plan waits for one of
            // them, so the dimensions compile side by side; the plans themselves still run one after another.
            var scouted = new LinkedHashMap<String, WorldPackPlan.Scouted>();
            if (PreparePool.parallel())
                for (String dimension : VANILLA) {
                    String folder = mapping.place(dimension);
                    if (!scouted.containsKey(folder)) scouted.put(folder, WorldPackPlan.scout(pack, folder, distant));
                }
            for (String dimension : VANILLA) {
                String folder = mapping.place(dimension);
                var plan = byFolder.get(folder);
                if (plan == null) {
                    var ahead = scouted.get(folder);
                    plan = ahead == null ? WorldPackPlan.prepare(pack, folder, distant) : WorldPackPlan.prepare(ahead);
                    byFolder.put(folder, plan);
                }
                byDimension.put(dimension, plan);
            }
        } finally {
            PackProgramSource.settle(pack); // no worker of this pack runs on into the next preparation
        }
        var defines = pack.settings().globalDefines(pack.options());
        return new WorldPackSet(byDimension, byDimension.get(VANILLA.getFirst()),
                pack.properties().vignette(defines), pack.properties().underwaterOverlay(defines));
    }

    /**
     * Whether terrain sections built under this pack are byte for byte what {@code next} would build. Every pack
     * uses the one 44-byte terrain format (lighting variants are read from it by the programs, not baked
     * differently); the only pack value a section carries is the material number of each block, taken from
     * block.properties. Equal entry lists in equal order resolve to the same numbers, and all dimensions of a pack
     * share the overworld's table. The standard renderer has another vertex format and is never "the same".
     */
    boolean sameTerrainContract(WorldPackSet next) {
        return next != null && overworld.materials().equals(next.overworld().materials());
    }

    WorldPackPlan forDimension(String dimension) { return dimensions.getOrDefault(dimension, overworld); }
}

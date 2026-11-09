package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.source.OpenedPack;

import java.io.IOException;
import java.util.List;

/** The original pack programs for the two DH 3.3.3 terrain passes. */
public record DistantPackPlan(PackProgramSource.Prepared terrain, PackProgramSource.Prepared water) {
    public static DistantPackPlan prepare(OpenedPack pack, String prefix) throws IOException {
        return new DistantPackPlan(
                PackProgramSource.prepare(pack, prefix + "dh_terrain", VertexInputs.DISTANT),
                PackProgramSource.prepare(pack, prefix + "dh_water", VertexInputs.DISTANT));
    }

    public List<PackProgramSource.Prepared> programs() {
        return List.of(terrain, water);
    }
}

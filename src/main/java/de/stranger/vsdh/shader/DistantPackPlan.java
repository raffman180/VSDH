package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.source.OpenedPack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The original pack programs for the DH 3.3.3 passes.
 * <p>
 * {@code generic} is the pack's own {@code dh_generic} for DH's generic objects (its clouds,
 * beacon beams, API boxes), or null when the pack has none and those fall back to
 * {@code dh_terrain} as under Iris. {@code clouds} is false when the pack keeps DH's clouds out:
 * {@code dhClouds=off}, or no {@code dhClouds} line and {@code clouds=off} (Iris's rule).
 */
public record DistantPackPlan(PackProgramSource.Prepared terrain, PackProgramSource.Prepared water,
                              PackProgramSource.Prepared generic, boolean clouds) {
    public DistantPackPlan(PackProgramSource.Prepared terrain, PackProgramSource.Prepared water) {
        this(terrain, water, null, true);
    }

    /** Read-ahead of the programs {@link #prepare} will ask for. */
    static void prefetch(OpenedPack pack, String prefix) {
        var names = new ArrayList<String>(List.of("dh_terrain", "dh_water"));
        if (pack.source().file(prefix + "dh_generic.vsh").isPresent() && pack.source().file(prefix + "dh_generic.fsh").isPresent())
            names.add("dh_generic");
        for (String name : names)
            PackProgramSource.prefetch(pack, prefix + name, VertexInputs.DISTANT, VertexInputs.DISTANT.elements(),
                    dev.vitrail.pack.model.AlphaTest.OFF);
    }

    public static DistantPackPlan prepare(OpenedPack pack, String prefix, boolean clouds) throws IOException {
        boolean ownGeneric = pack.source().file(prefix + "dh_generic.vsh").isPresent()
                && pack.source().file(prefix + "dh_generic.fsh").isPresent();
        return new DistantPackPlan(
                PackProgramSource.prepare(pack, prefix + "dh_terrain", VertexInputs.DISTANT),
                PackProgramSource.prepare(pack, prefix + "dh_water", VertexInputs.DISTANT),
                ownGeneric ? PackProgramSource.prepare(pack, prefix + "dh_generic", VertexInputs.DISTANT) : null,
                clouds);
    }

    /** The program that shades generic objects. */
    public PackProgramSource.Prepared genericProgram() {
        return generic != null ? generic : terrain;
    }

    public List<PackProgramSource.Prepared> programs() {
        var programs = new ArrayList<PackProgramSource.Prepared>(List.of(terrain, water));
        if (generic != null) programs.add(generic);
        return List.copyOf(programs);
    }
}

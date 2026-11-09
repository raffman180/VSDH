package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.ShaderChainPlan;
import de.stranger.vsdh.shader.ShaderTargetLayout;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.OpenedPack;
import java.nio.file.Path;
import java.util.Map;

public final class RenderGraphAudit {
    public static void main(String[] args) throws Exception {
        try (var pack = OpenedPack.open(Path.of(args[0]), Map.of(), args.length > 1 ? args[1] : "MEDIUM")) {
            var dimensions = DimensionSet.discover(pack.source());
            for (String world : java.util.List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
                var chain = ShaderChainPlan.prepare(pack, dimensions.place(world));
                var layout = ShaderTargetLayout.of(chain.targets(), 2560, 1440, 16384, 512L << 20);
                System.out.println(world + ": targets=" + layout.targets().size() + " bytes=" + layout.bytes()
                        + " copyBack=" + chain.copyBack() + " external=" + chain.externalSamplers());
                for (var stage : ShaderChainPlan.Stage.values()) System.out.println(stage + " "
                        + chain.stages().get(stage).stream().map(p -> p.name() + "->" + p.outputs() + " mips=" + p.mipmaps()).toList());
            }
        }
    }
}

package de.stranger.vsdh.shader;

import dev.vitrail.pack.id.BlockIds;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.PackDirectives;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CPU-only preparation; no native GPU resources, registry access or escaping ZIP paths. */
public record WorldPackPlan(ShaderChainPlan chain, Map<String, PackProgramSource.Prepared> terrain,
                            List<BlockIds.Entry> materials, PackExpressions.Plan expressions,
                            Map<String,Integer> items, PackDirectives directives, boolean oldHandLight,ShadowPackPlan shadow,
                            PackTexturePlan textures,dev.vitrail.pack.source.ShaderProperties.SkyElements sky, DistantPackPlan distant, SkyPackPlan skyPrograms) {
    public WorldPackPlan { terrain=Map.copyOf(terrain); materials=List.copyOf(materials); items=Map.copyOf(items); }
    public WorldPackPlan(ShaderChainPlan chain, Map<String,PackProgramSource.Prepared> terrain,List<BlockIds.Entry> materials) {
        this(chain,terrain,materials,PackExpressions.empty(),Map.of(),PackDirectives.defaults(),true,null,PackTexturePlan.empty(),
                new dev.vitrail.pack.source.ShaderProperties.SkyElements(true,true,true,true,dev.vitrail.pack.source.ShaderProperties.CloudSetting.DEFAULT),null,SkyPackPlan.empty());
    }
    public static WorldPackPlan prepare(OpenedPack pack, String dimension) throws IOException {
        return prepare(pack,dimension,false);
    }
    public static WorldPackPlan prepare(OpenedPack pack, String dimension, boolean distant) throws IOException {
        if (distant) pack=new OpenedPack(pack.packPath(),pack.source(),pack.options(),pack.properties(),pack.settings().withDistantHorizons());
        var chain=ShaderChainPlan.prepare(pack,dimension);
        var prefix=chain.targets().place().isEmpty()?"":chain.targets().place()+"/";
        var defines=pack.settings().globalDefines(pack.options());
        var alphas=pack.properties().alphaTests(defines);
        Map<String,PackProgramSource.Prepared> terrain=new LinkedHashMap<>();
        for (String layer:List.of("solid","cutout_mipped","cutout","translucent","tripwire")) {
            String program=layer.equals("translucent") || layer.equals("tripwire") ? "gbuffers_water":"gbuffers_terrain";
            if (pack.source().file(prefix+program+".vsh").isEmpty()) program="gbuffers_terrain";
            AlphaTest alpha=alphas.getOrDefault(program, switch(layer) {
                case "cutout_mipped"->AlphaTest.CUTOUT;
                case "cutout","tripwire"->new AlphaTest(AlphaTest.Function.GREATER,.1f);
                default->AlphaTest.OFF;
            });
            terrain.put(layer,PackProgramSource.prepareTerrain(pack,prefix+program,alpha));
        }
        var parsed=BlockIds.read(pack.source(),defines);
        if (!parsed.problems().isEmpty()) throw new IOException("Invalid material table: "+parsed.problems());
        var shadow=ShadowPackPlan.prepare(pack,prefix,chain.targets().shadowCeiling());
        var dh=distant?DistantPackPlan.prepare(pack,prefix):null;
        var skyPrograms=SkyPackPlan.prepare(pack,prefix,chain);
        return new WorldPackPlan(chain,terrain,parsed.entries(),PackExpressions.read(pack),ShaderItemMaterials.read(pack),
                PackDirectives.read(pack.source(),pack.settings(),dimension),pack.properties().oldHandLight(defines),
                shadow,PackTexturePlan.read(pack,chain,terrain,shadow,dh,skyPrograms),pack.properties().skyElements(defines),dh,skyPrograms);
    }
}

package de.stranger.vsdh.shader;

import dev.vitrail.pack.id.BlockIds;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.pack.texture.PackTextures;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CPU-only preparation; no native GPU resources, registry access or escaping ZIP paths. */
public record WorldPackPlan(ShaderChainPlan chain, Map<String, PackProgramSource.Prepared> terrain,
                            List<BlockIds.Entry> materials, PackExpressions.Plan expressions,
                            Map<String,Integer> items, PackDirectives directives, boolean oldHandLight,ShadowPackPlan shadow,
                            PackTexturePlan textures,dev.vitrail.pack.source.ShaderProperties.SkyElements sky, DistantPackPlan distant, SkyPackPlan skyPrograms, ParticlePackPlan particles, EntityPackPlan entities) {
    public WorldPackPlan { terrain=Map.copyOf(terrain); materials=List.copyOf(materials); items=Map.copyOf(items); }
    public WorldPackPlan(ShaderChainPlan chain, Map<String,PackProgramSource.Prepared> terrain,List<BlockIds.Entry> materials) {
        this(chain,terrain,materials,PackExpressions.empty(),Map.of(),PackDirectives.defaults(),true,null,PackTexturePlan.empty(),
                new dev.vitrail.pack.source.ShaderProperties.SkyElements(true,true,true,true,dev.vitrail.pack.source.ShaderProperties.CloudSetting.DEFAULT),null,SkyPackPlan.empty(),ParticlePackPlan.empty(),null);
    }
    /**
     * The program Iris serves a chunk layer with (SodiumPrograms.Pass): gbuffers_terrain_solid for
     * the solid layer, gbuffers_terrain_cutout for both cutout layers, gbuffers_water for the
     * translucent ones, each walked through the fallback tree (terrain, textured_lit, textured,
     * basic). Photon and Mellow ship a solid variant, Eclipse a cutout one.
     */
    static String terrainProgram(OpenedPack pack, String prefix, String layer) throws IOException {
        var pass = switch (layer) {
            case "solid" -> dev.vitrail.pack.program.TerrainPass.SOLID;
            case "cutout", "cutout_mipped" -> dev.vitrail.pack.program.TerrainPass.CUTOUT;
            case "translucent", "tripwire" -> dev.vitrail.pack.program.TerrainPass.TRANSLUCENT;
            default -> throw new IllegalArgumentException("Unknown terrain layer: " + layer);
        };
        for (String candidate : dev.vitrail.pack.model.ProgramFallbacks.chain(pass.program()))
            if (pack.source().file(prefix + candidate + ".vsh").isPresent() && pack.source().file(prefix + candidate + ".fsh").isPresent())
                return candidate;
        throw new IOException("No terrain program for the " + layer + " layer (" + pass.program()
                + " and its fallbacks are missing in '" + prefix + "')");
    }

    /** Both DH programs complete in the dimension's folder, the only shape the native DH passes accept. */
    static boolean hasDistantPrograms(OpenedPack pack, String dimension) throws IOException {
        // Same place rule as TargetPlan: an existing dimension directory replaces the root.
        boolean present = !dimension.equals(dev.vitrail.pack.program.ProgramSet.ROOT)
                && pack.source().topLevelDirectories().contains(dimension);
        String prefix = present ? dimension + "/" : "";
        for (String program : List.of("dh_terrain", "dh_water"))
            for (String stage : List.of(".vsh", ".fsh"))
                if (pack.source().file(prefix + program + stage).isEmpty()) return false;
        return true;
    }

    public static WorldPackPlan prepare(OpenedPack pack, String dimension) throws IOException {
        return prepare(pack,dimension,false);
    }
    public static WorldPackPlan prepare(OpenedPack pack, String dimension, boolean distant) throws IOException {
        // The translator and the sampler classification read the pack's image and buffer
        // directives from these registries; they hold the pack being prepared, never a previous one.
        var registryDefines=pack.settings().globalDefines(pack.options());
        dev.vitrail.pack.texture.CustomImages.install(pack.properties().imageDirectives(registryDefines));
        dev.vitrail.pack.texture.CustomStorage.install(pack.properties().bufferObjects(registryDefines));
        if (distant && !hasDistantPrograms(pack, dimension)) {
            // Iris keeps such a pack and leaves the LODs to DH's own drawing; so does the native composite here.
            org.slf4j.LoggerFactory.getLogger("VSDH").warn("{} has no dh_terrain/dh_water programs for '{}': using it without pack DH programs",
                    pack.packPath().getFileName(), dimension);
            distant=false;
        }
        if (distant) pack=new OpenedPack(pack.packPath(),pack.source(),pack.options(),pack.properties(),pack.settings().withDistantHorizons());
        var chain=ShaderChainPlan.prepare(pack,dimension);
        var prefix=chain.targets().place().isEmpty()?"":chain.targets().place()+"/";
        var defines=pack.settings().globalDefines(pack.options());
        var alphas=pack.properties().alphaTests(defines);
        Map<String,PackProgramSource.Prepared> terrain=new LinkedHashMap<>();
        for (String layer:List.of("solid","cutout_mipped","cutout","translucent","tripwire")) {
            String program=terrainProgram(pack,prefix,layer);
            AlphaTest alpha=alphas.getOrDefault(program, switch(layer) {
                case "cutout_mipped"->AlphaTest.CUTOUT;
                case "cutout","tripwire"->new AlphaTest(AlphaTest.Function.GREATER,.1f);
                default->AlphaTest.OFF;
            });
            terrain.put(layer,PackProgramSource.prepareTerrain(pack,prefix+program,alpha));
        }
        var parsed=BlockIds.read(pack.source(),defines);
        // Like Iris, a partly unreadable entry is reported and the rest of the table is kept.
        for (String problem : parsed.problems())
            org.slf4j.LoggerFactory.getLogger("VSDH").warn("block.properties of {} ({}): {}", pack.packPath().getFileName(), dimension, problem);
        var expressions=PackExpressions.read(pack);
        var packTextures=PackTextures.read(pack.properties(),defines,pack.source());
        var shadow=ShadowPackPlan.prepare(pack,prefix,chain.targets().shadowCeiling(),expressions.resolvedNames(),distant,packTextures,
                message->org.slf4j.LoggerFactory.getLogger("VSDH").warn(message));
        var dhClouds=pack.properties().dhClouds(defines);
        boolean dhCloudsDrawn=dhClouds!=dev.vitrail.pack.source.ShaderProperties.CloudSetting.OFF
                &&!(dhClouds==dev.vitrail.pack.source.ShaderProperties.CloudSetting.DEFAULT
                    &&pack.properties().skyElements(defines).clouds()==dev.vitrail.pack.source.ShaderProperties.CloudSetting.OFF);
        var dh=distant?DistantPackPlan.prepare(pack,prefix,dhCloudsDrawn):null;
        var skyPrograms=SkyPackPlan.prepare(pack,prefix,chain);
        var particles=ParticlePackPlan.prepare(pack,prefix,chain);
        var entities=EntityPackPlan.prepare(pack,prefix,chain,expressions.resolvedNames(),shadow!=null,dh!=null,
                message->org.slf4j.LoggerFactory.getLogger("VSDH").warn(message));
        PackTexturePlan textures;
        try {
            textures=PackTexturePlan.read(pack,chain,terrain,shadow,dh,skyPrograms,particles,entities,packTextures);
        } catch (IOException failure) {
            if (entities==null) throw failure;
            textures=PackTexturePlan.read(pack,chain,terrain,shadow,dh,skyPrograms,particles,null,packTextures);
            org.slf4j.LoggerFactory.getLogger("VSDH").warn("Keeping standard opaque entity rendering in {}: {}",dimension,failure.getMessage());
            entities=null;
        }
        return new WorldPackPlan(chain,terrain,parsed.entries(),expressions,ShaderItemMaterials.read(pack),
                PackDirectives.read(pack.source(),pack.settings(),dimension),pack.properties().oldHandLight(defines),
                shadow,textures,pack.properties().skyElements(defines),dh,skyPrograms,particles,entities);
    }
}

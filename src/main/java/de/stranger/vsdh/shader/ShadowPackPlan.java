package de.stranger.vsdh.shader;

import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.DrawBuffers;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The pack's actual shadow program and output slots; no dummy shader is substituted. */
public record ShadowPackPlan(Map<String,PackProgramSource.Prepared> programs,List<Integer> outputs) {
    public ShadowPackPlan { programs=Map.copyOf(programs);outputs=List.copyOf(outputs); }
    public static ShadowPackPlan prepare(OpenedPack pack,String prefix,int ceiling) throws IOException {
        String path=prefix+"shadow";
        if(pack.source().file(path+".vsh").isEmpty())return null;
        var alpha=pack.properties().alphaTests(pack.settings().globalDefines(pack.options()))
                .getOrDefault("shadow",new AlphaTest(AlphaTest.Function.GREATER,.1f));
        var program=PackProgramSource.prepareTerrain(pack,path,alpha);
        var fragment=pack.source().file(path+".fsh").orElseThrow(()->new IOException("Missing shadow fragment program"));
        var outputs=DrawBuffers.shadowColours(DrawBuffers.parse(new IncludeExpander(pack.source(),pack.settings()).expand(fragment)),ceiling);
        var programs=new LinkedHashMap<String,PackProgramSource.Prepared>();
        for(String layer:List.of("solid","cutout_mipped","cutout","translucent","tripwire"))programs.put(layer,program);
        return new ShadowPackPlan(programs,outputs);
    }
}

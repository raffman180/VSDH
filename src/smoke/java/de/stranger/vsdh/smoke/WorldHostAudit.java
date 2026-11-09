package de.stranger.vsdh.smoke;

import com.google.gson.GsonBuilder;
import de.stranger.vsdh.shader.*;
import dev.vitrail.pack.source.OpenedPack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Reports concrete remaining world-host requirements, without pretending compilation implies runtime support. */
public final class WorldHostAudit {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Expected external pack ZIP and output JSON");
        List<Map<String,Object>> dimensions=new ArrayList<>();
        try(var pack=OpenedPack.open(Path.of(args[0]),Map.of(),"MEDIUM")) {
            for(String dimension:List.of("world0","world-1","world1")) {
                var plan=WorldPackPlan.prepare(pack,dimension);
                var missing=new TreeSet<String>();
                plan.terrain().values().forEach(p->missing.addAll(WorldUniforms.missing(new Std140Layout(p.translated().uniforms()))));
                plan.chain().stages().values().forEach(passes->passes.forEach(p->missing.addAll(WorldUniforms.missing(new Std140Layout(p.program().translated().uniforms())))));
                var resources=new TreeSet<>(plan.chain().externalSamplers());
                plan.terrain().values().forEach(p->{
                    var active=new HashSet<Integer>();p.compiled().stages().values().forEach(bytes->active.addAll(SpirvInterface.bindings(bytes)));
                    p.translated().samplers().forEach(s->{if(active.contains(p.compiled().bindings().get(s.name())))resources.add(s.name());});
                });
                var entry=new LinkedHashMap<String,Object>();entry.put("dimension",dimension);entry.put("terrainVariantsCompiled",plan.terrain().size());
                entry.put("missingWorldUniforms",missing);entry.put("requiredResources",resources);
                entry.put("completeWorldRendererSupported",false);dimensions.add(entry);
            }
        }
        var output=Path.of(args[1]);Files.createDirectories(output.getParent());
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("profile","MEDIUM","fullShaderPackWorldRendering",false,"dimensions",dimensions)));
        for(var entry:dimensions)System.out.println(entry.get("dimension")+": compiled "+entry.get("terrainVariantsCompiled")+" terrain variants; missing "+((Set<?>)entry.get("missingWorldUniforms")).size()+" world values. Full renderer remains unsupported.");
    }
}

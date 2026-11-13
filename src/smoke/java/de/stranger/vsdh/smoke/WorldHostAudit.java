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
        if(args.length<3||args.length>4)throw new IllegalArgumentException("Expected external pack ZIP, output JSON and DH flag");
        List<Map<String,Object>> dimensions=new ArrayList<>();
        try(var pack=OpenedPack.open(Path.of(args[0]),Map.of(),args.length>3?args[3]:"MEDIUM")) {
            // The Pack Options page reads the same opening; report what it would list.
            var options=de.stranger.vsdh.shader.PackOptionsView.read(pack,Path.of(args[0]).getFileName().toString(),args.length>3?args[3]:"MEDIUM","de_de");
            System.out.println("world options index: total="+pack.options().count()+" offered="+pack.options().all().stream().filter(pack.options()::offers).count()+" screens="+pack.properties().screenLayout().size()+" rootTokens="+pack.properties().screenLayout().getOrDefault("",List.of()));
            System.out.println("world options page: "+options.blocks().stream().mapToInt(b->b.entries().size()).sum()+" options in "+options.blocks().size()+" groups");
            for(String dimension:List.of("world0","world-1","world1")) {
                var plan=WorldPackPlan.prepare(pack,dimension,Boolean.parseBoolean(args[2]));
                var missing=new TreeSet<String>();
                var declared=new TreeSet<String>();
                plan.terrain().values().forEach(p->{missing.addAll(WorldUniforms.missing(p,plan));declared.addAll(WorldUniforms.missing(new Std140Layout(p.translated().uniforms())));});
                plan.chain().stages().values().forEach(passes->passes.forEach(pass->{
                    var programs=new ArrayList<de.stranger.vsdh.shader.PackProgramSource.Prepared>();
                    if(pass.draws())programs.add(pass.program());
                    pass.computes().forEach(c->programs.add(c.program().asProgram()));
                    for(var p:programs){missing.addAll(WorldUniforms.missing(p,plan));declared.addAll(WorldUniforms.missing(new Std140Layout(p.translated().uniforms())));}
                }));
                if(plan.shadow()!=null)plan.shadow().programs().values().forEach(p->{missing.addAll(WorldUniforms.missing(p,plan));declared.addAll(WorldUniforms.missing(new Std140Layout(p.translated().uniforms())));});
                if(plan.distant()!=null)plan.distant().programs().forEach(p->missing.addAll(WorldUniforms.missing(p,plan)));
                plan.skyPrograms().programs().values().forEach(p->missing.addAll(WorldUniforms.missing(p,plan)));
                plan.particles().programs().values().forEach(p->missing.addAll(WorldUniforms.missing(p,plan)));
                declared.removeAll(plan.expressions().resolvedNames());
                var resources=new TreeSet<>(plan.chain().externalSamplers());
                var geometry=new ArrayList<>(plan.terrain().values());
                if(plan.distant()!=null)geometry.addAll(plan.distant().programs());
                geometry.addAll(plan.skyPrograms().programs().values());
                geometry.addAll(plan.particles().programs().values());
                geometry.forEach(p->{
                    var active=new HashSet<Integer>();p.compiled().stages().values().forEach(bytes->active.addAll(SpirvInterface.bindings(bytes)));
                    p.translated().samplers().forEach(s->{if(active.contains(p.compiled().bindings().get(s.name())))resources.add(s.name());});
                });
                var entry=new LinkedHashMap<String,Object>();entry.put("dimension",dimension);entry.put("terrainVariantsCompiled",plan.terrain().size());
                entry.put("skyVariantsCompiled",plan.skyPrograms().programs().size());
                entry.put("particleWeatherVariantsCompiled",plan.particles().programs().size());
                entry.put("missingWorldUniforms",missing);entry.put("requiredResources",resources);
                entry.put("unimplementedDeclaredWorldUniforms",declared);
                entry.put("customExpressionProblems",plan.expressions().problems());
                entry.put("resolvedCustomUniforms",new TreeSet<>(plan.expressions().resolvedNames()));
                entry.put("itemMaterialEntries",plan.items().size());
                entry.put("preparedTextureBindings",new TreeSet<>(plan.textures().bindings().keySet()));
                entry.put("vanillaCloudDirective",plan.sky().clouds().name());
                entry.put("distantPrograms",plan.distant()==null?List.of():plan.distant().programs().stream().map(PackProgramSource.Prepared::path).toList());
                entry.put("completeWorldRendererSupported",false);dimensions.add(entry);
            }
        }
        var output=Path.of(args[1]);Files.createDirectories(output.getParent());
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("profile","MEDIUM","fullShaderPackWorldRendering",false,"dimensions",dimensions)));
        for(var entry:dimensions)System.out.println(entry.get("dimension")+": compiled "+entry.get("terrainVariantsCompiled")+" terrain variants; missing "+((Set<?>)entry.get("missingWorldUniforms")).size()+" world values. Full renderer remains unsupported.");
    }
}

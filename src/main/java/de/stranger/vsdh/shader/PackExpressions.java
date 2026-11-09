package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.ShaderProperties.CustomUniform;
import dev.vitrail.uniform.*;
import dev.vitrail.uniform.expr.CustomUniforms;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.Pattern;

/** Pack-authored expression graphs, prepared without a world and evaluated once per world frame. */
public final class PackExpressions {
    private static final Pattern BIOME = Pattern.compile("\\bBIOME_[A-Z0-9_]+\\b");
    private static final Map<String, UniformShape> INPUTS;
    static {
        var inputs = new LinkedHashMap<String, UniformShape>();
        for (String name : List.of("frameTime", "frameTimeCounter", "rainStrength", "wetness", "eyeAltitude",
                "blindness", "darknessFactor", "darknessLightFactor", "nightVision", "sunAngle", "screenBrightness",
                "cloudHeight", "viewWidth", "viewHeight", "aspectRatio", "near", "far")) inputs.put(name, UniformShape.FLOAT);
        for (String name : List.of("frameCounter", "worldTime", "worldDay", "isEyeInWater", "moonPhase", "biome",
                "biome_precipitation", "heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2")) inputs.put(name, UniformShape.INT);
        for (String name : List.of("cameraPosition", "previousCameraPosition", "eyePosition", "relativeEyePosition",
                "playerLookVector", "cameraPositionFract", "previousCameraPositionFract", "fogColor", "skyColor")) inputs.put(name, UniformShape.VEC3);
        inputs.put("cameraPositionInt", UniformShape.IVEC3); inputs.put("previousCameraPositionInt", UniformShape.IVEC3);
        inputs.put("eyeBrightness", UniformShape.IVEC2); inputs.put("eyeBrightnessSmooth", UniformShape.IVEC2);
        INPUTS = Map.copyOf(inputs);
    }

    private PackExpressions() {}

    public record Plan(List<CustomUniform> declarations, Map<String, Integer> biomeSymbols,
                       Set<String> resolvedNames, List<String> problems) {
        public Plan {
            declarations=List.copyOf(declarations); biomeSymbols=Map.copyOf(biomeSymbols);
            resolvedNames=Set.copyOf(resolvedNames); problems=List.copyOf(problems);
        }
        public Runtime runtime() { return new Runtime(declarations, biomeSymbols); }
        public int biomeId(String namespace, String path) {
            // Vanilla symbols use the path; explicitly namespaced mod biomes use their namespace.
            String symbol="BIOME_"+(namespace.equals("minecraft")?"":namespace+"_")+path;
            return biomeSymbols.getOrDefault(symbol.toUpperCase(Locale.ROOT),-1);
        }
    }

    public static Plan read(OpenedPack pack) {
        return prepare(pack.properties().customUniforms(pack.settings().globalDefines(pack.options())));
    }
    public static Plan empty() { return prepare(List.of()); }
    public static Plan prepare(List<CustomUniform> declarations) {
        var names=new TreeSet<String>();
        declarations.forEach(d->{var matcher=BIOME.matcher(d.expression());while(matcher.find())names.add(matcher.group());});
        var biomes=new LinkedHashMap<String,Integer>();
        for(String name:names) biomes.put(name,biomes.size());
        var runtime=new Runtime(declarations,biomes);
        return new Plan(declarations,biomes,runtime.names(),runtime.problems());
    }

    public static final class Runtime {
        private final CustomUniforms graph;
        private final Map<String,UniformSource> sources = new LinkedHashMap<>();
        private final Map<String,UniformShape> shapes = new LinkedHashMap<>();
        private final List<String> problems;
        private final Val value=new Val();
        private final float[][] floats={new float[0],new float[1],new float[2],new float[3],new float[4]};
        private final int[] integer=new int[1];

        private Runtime(List<CustomUniform> declarations, Map<String,Integer> biomes) {
            var inputs=UniformCatalog.builder();
            INPUTS.forEach((name,shape)->inputs.add(name,shape,(frame,out)->frame.read(name,out)));
            biomes.forEach((name,id)->inputs.add(name,UniformShape.INT,(frame,out)->out.set(id)));
            var builder=CustomUniforms.builder();
            declarations.forEach(d->builder.declare(d.name(),d.type(),d.expression(),d.exposed()));
            var found=new ArrayList<String>(); graph=builder.build(inputs.build(),found); problems=List.copyOf(found);
            for(String name:graph.exposed()) { sources.put(name,graph.source(name)); shapes.put(name,graph.shape(name)); }
        }
        public Set<String> names() { return Set.copyOf(sources.keySet()); }
        public List<String> problems() { return problems; }
        public void update(ExpressionFrame frame,float dt) {
            graph.update(frame,dt);
            var errors=graph.drainProblems();
            if(!errors.isEmpty()) throw new IllegalStateException("Pack expression evaluation failed: "+errors);
            for(var entry:sources.entrySet()) {
                entry.getValue().read(frame,value);
                for(int component=0;component<value.rank();component++)
                    if(!Float.isFinite(value.f(component)))
                        throw new IllegalStateException("Non-finite custom uniform: "+entry.getKey()+" component "+component);
            }
        }
        public void read(String name, ExpressionFrame frame, Val destination) {
            var source=sources.get(name);
            if(source==null) throw new IllegalArgumentException("Unresolved custom uniform: "+name);
            source.read(frame,destination);
        }
        public boolean write(Std140Layout.Member member,Std140Layout layout,ByteBuffer destination) {
            var source=sources.get(member.name()); if(source==null)return false;
            var shape=shapes.get(member.name());
            if(member.arrayLength()!=0 || shape!=UniformShape.of(member.type()))
                throw new IllegalArgumentException("Custom uniform type mismatch: "+member.name()+" / "+member.type());
            source.read(null,value); // Derived values were already captured once this frame.
            if(shape==UniformShape.INT) {
                integer[0]=value.i(0); layout.putInts(destination,member.name(),0,integer);
            } else {
                var values=floats[shape.rank()];
                for(int i=0;i<values.length;i++)values[i]=value.f(i);
                layout.putFloats(destination,member.name(),0,values);
            }
            return true;
        }
    }
}

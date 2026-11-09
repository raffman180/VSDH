package de.stranger.vsdh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import dev.vitrail.uniform.ExpressionFrame;
import dev.vitrail.uniform.Val;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.Set;

/** One actual world/camera snapshot per frame. Unknown values are rejected before activation. */
public final class WorldUniforms implements ExpressionFrame {
    private static final Set<String> KNOWN=Set.of("of_ModelViewMatrix","of_ProjectionMatrix","of_ModelViewProjectionMatrix",
            "of_NormalMatrix","of_TextureMatrix","of_DepthConv","gbufferModelView","gbufferModelViewInverse",
            "gbufferProjection","gbufferProjectionInverse","gbufferPreviousModelView","gbufferPreviousProjection",
            "cameraPosition","previousCameraPosition","fogColor","skyColor","viewWidth","viewHeight","aspectRatio",
            "near","far","frameCounter","frameTime","frameTimeCounter","worldTime","worldDay","rainStrength","eyeAltitude",
            "alphaTestRef","renderStage");
    private static final float[] IDENTITY={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    private static final float[] QUAD={2,0,0,0,0,2,0,0,0,0,0,0,-1,-1,0,1};
    private static final float[] NORMAL_ID={1,0,0,0,1,0,0,0,1};
    private static final float[] LIGHT={1/256f,0,0,0,0,1/256f,0,0,0,0,1/256f,0,1/32f,1/32f,1/32f,1};
    private static final float[] DEPTH={.5f,.5f,1,0};
    public final WorldMatrices matrices=new WorldMatrices();
    private final float[] view=new float[16], projection=new float[16], mvp=new float[16], normal=new float[9];
    private final float[] inverseView=new float[16], inverseProjection=new float[16], previousView=new float[16], previousProjection=new float[16];
    private final float[] camera=new float[3], previousCamera=new float[3], fog=new float[3], sky=new float[3];
    private final float[] scalar=new float[1]; private final int[] integer=new int[1];
    private final Matrix4f scratch=new Matrix4f(); private final Matrix3f normalScratch=new Matrix3f();
    private final BuiltinWorldValues builtins=new BuiltinWorldValues();
    private final WorldPackPlan plan;
    private final PackWorldValues packValues;
    private PackExpressions.Runtime expressions;
    private Object previousLevel;
    private int width,height,worldTime,worldDay,frame;
    private float rain,far,frameTime,elapsed;
    private long lastNanos;

    public WorldUniforms(WorldPackPlan plan) {
        this.plan=plan;packValues=new PackWorldValues(plan);expressions=plan.expressions().runtime();
    }

    public static Set<String> missing(Std140Layout layout) {
        var missing=new java.util.TreeSet<String>();
        for (var member:layout.members()) if (!known(member.name())) missing.add(member.name());
        return Set.copyOf(missing);
    }
    public static Set<String> missing(PackProgramSource.Prepared program) {
        return missing(program,Set.of());
    }
    private static boolean known(String name) {
        return KNOWN.contains(name)||BuiltinWorldValues.NAMES.contains(name)||PackWorldValues.NAMES.contains(name);
    }
    public static Set<String> missing(PackProgramSource.Prepared program,Set<String> customNames) {
        var active=UniformUsage.names(program.compiled(),new Std140Layout(program.translated().uniforms()));
        var missing=new java.util.TreeSet<>(active);missing.removeIf(WorldUniforms::known);missing.removeAll(customNames);return Set.copyOf(missing);
    }
    public static void validate(WorldPackPlan plan) {
        var missing=new java.util.TreeSet<String>();
        plan.terrain().values().forEach(p->missing.addAll(missing(p,plan.expressions().resolvedNames())));
        plan.chain().stages().values().forEach(passes->passes.forEach(p->missing.addAll(missing(p.program(),plan.expressions().resolvedNames()))));
        if (!missing.isEmpty()) throw new IllegalArgumentException("World uniforms not implemented: "+missing);
    }
    public void capture(Camera camera, Matrix4f view, Matrix4f legacyProjection, float partialTick, int width, int height) {
        Minecraft mc=Minecraft.getInstance(); var pos=camera.getPosition();
        if(previousLevel!=mc.level) {
            previousLevel=mc.level;packValues.reset();expressions=plan.expressions().runtime();lastNanos=0;
        }
        matrices.begin(mc.level,view,legacyProjection,pos.x,pos.y,pos.z,width,height);
        builtins.capture(camera,matrices,partialTick);
        this.width=width; this.height=height; this.far=mc.gameRenderer.getRenderDistance();
        matrices.view.get(this.view); matrices.projection.get(projection);
        scratch.set(matrices.projection).mul(matrices.view).get(mvp);
        matrices.inverseView.get(inverseView); matrices.inverseProjection.get(inverseProjection);
        matrices.previousView.get(previousView); matrices.previousProjection.get(previousProjection);
        matrices.view.normal(normalScratch).get(normal);
        this.camera[0]=(float)pos.x; this.camera[1]=(float)pos.y; this.camera[2]=(float)pos.z;
        previousCamera[0]=(float)matrices.previousX(); previousCamera[1]=(float)matrices.previousY(); previousCamera[2]=(float)matrices.previousZ();
        float[] fogColor=RenderSystem.getShaderFogColor(); System.arraycopy(fogColor,0,fog,0,3);
        var skyColor=mc.level.getSkyColor(pos,partialTick); sky[0]=(float)skyColor.x;sky[1]=(float)skyColor.y;sky[2]=(float)skyColor.z;
        worldTime=(int)Math.floorMod(mc.level.getDayTime(),24000); worldDay=(int)Math.floorMod(Math.floorDiv(mc.level.getDayTime(),24000),Integer.MAX_VALUE);
        rain=mc.level.getRainLevel(partialTick);
        long now=System.nanoTime(); frameTime=lastNanos==0?0:Math.min(1,(now-lastNanos)*1e-9f); lastNanos=now;
        elapsed=(elapsed+frameTime)%3600;
        packValues.capture(camera,builtins,rain,frameTime);
        expressions.update(this,frameTime);
    }
    public void write(NativeGraphicsProgram pass, boolean fullscreen, int renderStage, float alphaRef) {
        var layout=pass.layout(); var buffer=pass.uniforms();
        for (var member:pass.activeUniforms()) {
            if(expressions.write(member,layout,buffer)||packValues.write(member,layout,buffer))continue;
            if(builtins.write(member,layout,buffer))continue;
            String name=member.name(); float[] matrix=switch(name) {
                case "of_ModelViewMatrix"->fullscreen?IDENTITY:view;
                case "of_ProjectionMatrix","of_ModelViewProjectionMatrix"->fullscreen?QUAD:name.equals("of_ProjectionMatrix")?projection:mvp;
                case "of_NormalMatrix"->fullscreen?NORMAL_ID:normal;
                case "gbufferModelView"->view; case "gbufferModelViewInverse"->inverseView;
                case "gbufferProjection"->projection; case "gbufferProjectionInverse"->inverseProjection;
                case "gbufferPreviousModelView"->previousView; case "gbufferPreviousProjection"->previousProjection;
                case "of_DepthConv"->DEPTH; case "cameraPosition"->camera; case "previousCameraPosition"->previousCamera;
                case "fogColor"->fog; case "skyColor"->sky; default->null;
            };
            if (matrix!=null) { layout.putFloats(buffer,name,0,matrix); continue; }
            if (name.equals("of_TextureMatrix")) {
                for (int i=0;i<member.arrayLength();i++) layout.putFloats(buffer,name,i,i==1?LIGHT:IDENTITY);
                continue;
            }
            if (member.type().equals("int")) {
                integer[0]=switch(name) {case "worldTime"->worldTime;case "worldDay"->worldDay;case "frameCounter"->frame;case "renderStage"->renderStage;default->throw new IllegalArgumentException("Unknown integer world uniform "+name);};
                layout.putInts(buffer,name,0,integer); continue;
            }
            scalar[0]=switch(name) {
                case "viewWidth"->width;case "viewHeight"->height;case "aspectRatio"->(float)width/height;
                case "near"->.05f;case "far"->far;case "frameTime"->frameTime;case "frameTimeCounter"->elapsed;
                case "rainStrength"->rain;case "eyeAltitude"->camera[1];case "alphaTestRef"->alphaRef;
                default->throw new IllegalArgumentException("Unknown world uniform "+name);
            };
            layout.putFloats(buffer,name,0,scalar);
        }
    }
    public void commit() { matrices.commit();frame=(frame+1)%720720; }
    public void reset() { matrices.reset();lastNanos=0; }
    @Override public long frameCounter() { return frame; }
    @Override public void read(String name,Val out) {
        if(builtins.read(name,out)||packValues.read(name,out))return;
        switch(name) {
            case "frameCounter"->out.set(frame);case "worldTime"->out.set(worldTime);case "worldDay"->out.set(worldDay);
            case "frameTime"->out.set(frameTime);case "frameTimeCounter"->out.set(elapsed);
            case "rainStrength"->out.set(rain);case "eyeAltitude"->out.set(camera[1]);
            case "viewWidth"->out.set((float)width);case "viewHeight"->out.set((float)height);
            case "aspectRatio"->out.set((float)width/height);case "near"->out.set(.05f);case "far"->out.set(far);
            case "cameraPosition"->out.set(camera[0],camera[1],camera[2]);
            case "previousCameraPosition"->out.set(previousCamera[0],previousCamera[1],previousCamera[2]);
            case "fogColor"->out.set(fog[0],fog[1],fog[2]);case "skyColor"->out.set(sky[0],sky[1],sky[2]);
            default->throw new IllegalArgumentException("Unknown expression snapshot value: "+name);
        }
    }
}

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
    private static final Set<String> DISTANT=Set.of("dhProjection","dhProjectionInverse","dhPreviousProjection","dhNearPlane","dhFarPlane","dhRenderDistance");
    private final Matrix4f distantProjection=new Matrix4f(), distantView=new Matrix4f();
    private final float[] dhProjection=new float[16],dhInverse=new float[16],dhPrevious=new float[16],
            dhView=new float[16],dhMvp=new float[16],dhNormal=new float[9];
    private boolean dhHistory;
    private float dhNear,dhFar;
    private int dhDistance;
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
    private final Matrix4f legacyCameraProjection=new Matrix4f();
    private final BuiltinWorldValues builtins=new BuiltinWorldValues();
    public final ShadowCamera shadowCamera=new ShadowCamera();
    private final float[] shadowView=new float[16],shadowProjection=new float[16],shadowInverseView=new float[16],shadowInverseProjection=new float[16],shadowMvp=new float[16],shadowNormal=new float[9];
    private final Val scratchValue=new Val();
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
        return KNOWN.contains(name)||BuiltinWorldValues.NAMES.contains(name)||PackWorldValues.NAMES.contains(name)||ShadowCamera.NAMES.contains(name);
    }
    public static Set<String> missing(PackProgramSource.Prepared program,Set<String> customNames) {
        var active=UniformUsage.names(program.compiled(),new Std140Layout(program.translated().uniforms()));
        var missing=new java.util.TreeSet<>(active);missing.removeIf(WorldUniforms::known);missing.removeAll(customNames);
        // Entity zero is the explicit no-entity draw context for chunk/shadow terrain, not a world default.
        if(program.translated().inputs().vulkanTerrain())missing.remove("entityId");
        return Set.copyOf(missing);
    }
    public static Set<String> missing(PackProgramSource.Prepared program,WorldPackPlan plan) {
        var missing=new java.util.TreeSet<>(missing(program,plan.expressions().resolvedNames()));
        if(plan.distant()!=null)missing.removeAll(DISTANT);
        return Set.copyOf(missing);
    }
    public static void validate(WorldPackPlan plan) {
        var missing=new java.util.TreeSet<String>();
        plan.terrain().values().forEach(p->missing.addAll(missing(p,plan)));
        plan.chain().stages().values().forEach(passes->passes.forEach(p->missing.addAll(missing(p.program(),plan))));
        if(plan.shadow()!=null)plan.shadow().programs().values().forEach(p->missing.addAll(missing(p,plan)));
        if(plan.distant()!=null) {
            plan.distant().programs().forEach(p->missing.addAll(missing(p,plan)));
        }
        if (!missing.isEmpty()) throw new IllegalArgumentException("World uniforms not implemented: "+missing);
    }
    public void capture(Camera camera, Matrix4f view, Matrix4f renderedProjection, float partialTick, int width, int height) {
        Minecraft mc=Minecraft.getInstance(); var pos=camera.getPosition();
        if(previousLevel!=mc.level) {
            previousLevel=mc.level;packValues.reset();expressions=plan.expressions().runtime();lastNanos=0;
        }
        ForwardDepthProjection.toLegacy(renderedProjection,legacyCameraProjection);
        matrices.begin(mc.level,view,legacyCameraProjection,pos.x,pos.y,pos.z,width,height);
        builtins.capture(camera,matrices,partialTick);
        this.width=width; this.height=height; this.far=mc.gameRenderer.getRenderDistance();
        builtins.read("sunAngle",scratchValue);
        shadowCamera.capture(plan.directives(),scratchValue.f(0),pos.x,pos.y,pos.z,this.far);
        shadowCamera.view.get(shadowView);shadowCamera.projection.get(shadowProjection);
        shadowCamera.inverseView.get(shadowInverseView);shadowCamera.inverseProjection.get(shadowInverseProjection);
        scratch.set(shadowCamera.projection).mul(shadowCamera.view).get(shadowMvp);
        shadowCamera.view.normal(normalScratch).get(shadowNormal);
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
        if(plan.distant()!=null) {
            // Provide the same clip contract even if DH has no buffers to submit on this frame.
            var near=com.seibel.distanthorizons.core.util.RenderUtil.getNearClipPlaneInBlocks();
            if(com.seibel.distanthorizons.core.util.RenderUtil.getHeightBasedNearClipOverrideBlockDistance()==-1)near=Math.min(near,7.5f);
            var far=com.seibel.distanthorizons.core.util.RenderUtil.getFarClipPlaneDistanceInBlocks();
            distantView.set(matrices.view);
            distantProjection.set(legacyCameraProjection)
                    .m22((far+near)/(near-far)).m32(2*far*near/(near-far));
            captureDistantMatrices(near,far);
        }
    }
    public void write(NativeGraphicsProgram pass, boolean fullscreen, int renderStage, float alphaRef) {
        write(pass,fullscreen,false,false,renderStage,alphaRef);
    }
    public void writeShadow(NativeGraphicsProgram pass) {write(pass,false,true,false,0,.1f);}
    public void writeDistant(NativeGraphicsProgram pass, boolean water) {
        write(pass,false,false,true,water?17:8,0);
    }
    public void captureDistant(com.braffolk.dhvulkan.core.data.RenderUniforms frame) {
        copyDistantMatrix(frame.dhProjectionMatrix,distantProjection);
        copyDistantMatrix(frame.dhModelViewMatrix,distantView);
        captureDistantMatrices(distantProjection.perspectiveNear(),frame.farClipPlane);
    }
    private static void copyDistantMatrix(com.seibel.distanthorizons.core.util.math.DhMat4f source,Matrix4f destination) {
        destination.set(source.m00,source.m10,source.m20,source.m30,
                source.m01,source.m11,source.m21,source.m31,
                source.m02,source.m12,source.m22,source.m32,
                source.m03,source.m13,source.m23,source.m33);
    }
    private void captureDistantMatrices(float near,float far) {
        if(!(near>0)||!(far>near)||!distantProjection.isFinite()||!distantView.isFinite())
            throw new IllegalArgumentException("Invalid DH clip range or matrices: "+near+".."+far);
        distantProjection.m01(-distantProjection.m01()).m11(-distantProjection.m11())
                .m21(-distantProjection.m21()).m31(-distantProjection.m31());
        distantProjection.get(dhProjection);
        distantProjection.invert(scratch).get(dhInverse);
        distantView.get(dhView);
        scratch.set(distantProjection).mul(distantView).get(dhMvp);
        distantView.normal(normalScratch).get(dhNormal);
        if(!dhHistory)System.arraycopy(dhProjection,0,dhPrevious,0,16);
        dhNear=near;dhFar=far;
        dhDistance=com.braffolk.dhvulkan.core.DhConfigHelper.lodChunkRenderDistanceRadius()*16;
    }

    private void write(NativeGraphicsProgram pass,boolean fullscreen,boolean shadow,boolean distant,int renderStage,float alphaRef) {
        var layout=pass.layout(); var buffer=pass.uniforms();
        for (var member:pass.activeUniforms()) {
            if(expressions.write(member,layout,buffer)||packValues.write(member,layout,buffer))continue;
            if(builtins.write(member,layout,buffer))continue;
            String name=member.name(); float[] matrix=switch(name) {
                case "of_ModelViewMatrix"->fullscreen?IDENTITY:shadow?shadowView:distant?dhView:view;
                case "of_ProjectionMatrix","of_ModelViewProjectionMatrix"->fullscreen?QUAD:name.equals("of_ProjectionMatrix")?(shadow?shadowProjection:distant?dhProjection:projection):(shadow?shadowMvp:distant?dhMvp:mvp);
                case "of_NormalMatrix"->fullscreen?NORMAL_ID:shadow?shadowNormal:distant?dhNormal:normal;
                case "dhProjection"->dhProjection;case "dhProjectionInverse"->dhInverse;case "dhPreviousProjection"->dhPrevious;
                case "shadowModelView"->shadowView;case "shadowProjection"->shadowProjection;
                case "shadowModelViewInverse"->shadowInverseView;case "shadowProjectionInverse"->shadowInverseProjection;
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
                integer[0]=switch(name) {case "worldTime"->worldTime;case "worldDay"->worldDay;case "frameCounter"->frame;case "renderStage"->renderStage;case "dhRenderDistance"->dhDistance;
                    case "entityId"->{if(fullscreen)throw new IllegalArgumentException("Entity context is unavailable in fullscreen passes");yield 0;}
                    default->throw new IllegalArgumentException("Unknown integer world uniform "+name);};
                layout.putInts(buffer,name,0,integer); continue;
            }
            scalar[0]=switch(name) {
                case "viewWidth"->width;case "viewHeight"->height;case "aspectRatio"->(float)width/height;
                case "near"->.05f;case "far"->far;case "frameTime"->frameTime;case "frameTimeCounter"->elapsed;
                case "rainStrength"->rain;case "eyeAltitude"->camera[1];case "alphaTestRef"->alphaRef;case "dhNearPlane"->dhNear;case "dhFarPlane"->dhFar;
                default->throw new IllegalArgumentException("Unknown world uniform "+name);
            };
            layout.putFloats(buffer,name,0,scalar);
        }
    }
    public void commit() {
        matrices.commit();
        if(plan.distant()!=null){System.arraycopy(dhProjection,0,dhPrevious,0,16);dhHistory=true;}
        frame=(frame+1)%720720;
    }
    public void reset() { matrices.reset();lastNanos=0;dhHistory=false; }
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

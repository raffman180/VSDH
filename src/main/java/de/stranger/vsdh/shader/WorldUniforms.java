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
    private enum DrawSpace { WORLD, FULLSCREEN, SHADOW, DISTANT, DRAW }

    private static final Set<String> KNOWN=Set.of("of_ModelViewMatrix","of_ProjectionMatrix","of_ModelViewProjectionMatrix",
            "of_NormalMatrix","of_TextureMatrix","of_DepthConv","of_GlintAlpha","gbufferModelView","gbufferModelViewInverse",
            "gbufferProjection","gbufferProjectionInverse","gbufferPreviousModelView","gbufferPreviousProjection",
            "cameraPosition","previousCameraPosition","fogColor","skyColor","viewWidth","viewHeight","aspectRatio",
            "near","far","farPlane","frameCounter","frameTime","frameTimeCounter","worldTime","worldDay","rainStrength","eyeAltitude",
            "alphaTestRef","renderStage","blockEntityId",
            // Iris offers these three to every pack, with or without its DH programs.
            "dhNearPlane","dhFarPlane","dhRenderDistance");
    private static final Set<String> DISTANT=Set.of("dhProjection","dhProjectionInverse","dhPreviousProjection");
    private final Matrix4f distantProjection=new Matrix4f(), distantView=new Matrix4f();
    private final float[] dhProjection=new float[16],dhInverse=new float[16],dhPrevious=new float[16],
            dhView=new float[16],dhMvp=new float[16],dhNormal=new float[9];
    private boolean dhHistory;
    // Iris's values while DH has not reported clip planes yet.
    private float dhNear=.01f,dhFar=.01f;
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
    private final Matrix4f localProjection=new Matrix4f();
    private final float[] localTextureMatrix = new float[16];
    private final float[] localView=new float[16],localProjectionValues=new float[16],localMvp=new float[16],
            localNormal=new float[9],passColour=new float[4];
    private final BuiltinWorldValues builtins=new BuiltinWorldValues();
    private final IrisStandardValues standard=new IrisStandardValues();
    public final ShadowCamera shadowCamera=new ShadowCamera();
    private final float[] shadowView=new float[16],shadowProjection=new float[16],shadowInverseView=new float[16],shadowInverseProjection=new float[16],shadowMvp=new float[16],shadowNormal=new float[9];
    private final Val scratchValue=new Val();
    private final WorldPackPlan plan;
    private final PackWorldValues packValues;
    private PackExpressions.Runtime expressions;
    private Object previousLevel;
    private int width,height,worldTime,worldDay,frame;
    private float rain,far,farPlane,frameTime,elapsed;
    private long lastNanos;

    public WorldUniforms(WorldPackPlan plan) {
        this.plan=plan;packValues=new PackWorldValues(plan);expressions=plan.expressions().runtime();
        zeroFilled=unprovided(plan);
    }
    private final Set<String> zeroFilled;
    private static void zero(Std140Layout.Member member,java.nio.ByteBuffer destination) {
        int bytes=member.arrayLength()>0?member.arrayLength()*member.arrayStride():member.size();
        for(int i=0;i<bytes;i++)destination.put(member.offset()+i,(byte)0);
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
        return KNOWN.contains(name)||BuiltinWorldValues.NAMES.contains(name)||IrisStandardValues.NAMES.contains(name)
                ||PackWorldValues.NAMES.contains(name)||ShadowCamera.NAMES.contains(name);
    }
    public static Set<String> missing(PackProgramSource.Prepared program,Set<String> customNames) {
        var active=UniformUsage.names(program.compiled(),new Std140Layout(program.translated().uniforms()));
        var missing=new java.util.TreeSet<>(active);missing.removeIf(WorldUniforms::known);missing.removeAll(customNames);
        // Entity zero is the explicit no-entity draw context for chunk/shadow terrain, not a world default.
        if(program.translated().inputs().vulkanTerrain())missing.remove("entityId");
        if(program.translated().inputs()==dev.vitrail.glsl.VertexInputs.SKY)missing.remove("of_PassColour");
        return Set.copyOf(missing);
    }
    static Set<String> missing(PackProgramSource.Prepared program, Set<String> customNames, boolean distant) {
        var missing = new java.util.TreeSet<>(missing(program, customNames));
        if (distant) missing.removeAll(DISTANT);
        return Set.copyOf(missing);
    }
    public static Set<String> missing(PackProgramSource.Prepared program,WorldPackPlan plan) {
        return missing(program, plan.expressions().resolvedNames(), plan.distant() != null);
    }
    /**
     * Uniforms some program of the plan reads that no provider, pack formula or DH program supplies.
     * They read as zero, which is what an unset GL uniform holds under Iris; a pack formula Iris
     * cannot resolve either, such as a vector times a scalar, ends the same way there.
     */
    public static void validate(WorldPackPlan plan) {
        var missing=unprovided(plan);
        if (!missing.isEmpty()) org.slf4j.LoggerFactory.getLogger("VSDH-Shaders")
                .warn("Pack reads uniforms VSDH does not provide; they read as zero like unset uniforms under Iris: {}", missing);
    }
    static Set<String> unprovided(WorldPackPlan plan) {
        var missing=new java.util.TreeSet<String>();
        plan.terrain().values().forEach(p->missing.addAll(missing(p,plan)));
        plan.chain().stages().values().forEach(passes->passes.forEach(p->{
            if(p.draws())missing.addAll(missing(p.program(),plan));
            p.computes().forEach(c->missing.addAll(missing(c.program().asProgram(),plan)));
        }));
        plan.chain().setup().forEach(c->missing.addAll(missing(c.program().asProgram(),plan)));
        plan.chain().shadowComputes().forEach(c->missing.addAll(missing(c.program().asProgram(),plan)));
        if(plan.shadow()!=null) {
            plan.shadow().programs().values().forEach(p->missing.addAll(missing(p,plan)));
            if(plan.shadow().entities()!=null)missing.addAll(missing(plan.shadow().entities(),plan));
            if(plan.shadow().blocks()!=null)missing.addAll(missing(plan.shadow().blocks(),plan));
        }
        if(plan.distant()!=null) {
            plan.distant().programs().forEach(p->missing.addAll(missing(p,plan)));
        }
        plan.skyPrograms().programs().values().forEach(p->missing.addAll(missing(p,plan)));
        plan.particles().programs().values().forEach(p->missing.addAll(missing(p,plan)));
        if(plan.entities()!=null)plan.entities().programs().forEach(p->missing.addAll(missing(p,plan)));
        return Set.copyOf(missing);
    }
    /** Block-state IDs of the installed pack, as Iris reports them for the selected block. */
    public void blockMaterials(java.util.function.ToIntFunction<net.minecraft.world.level.block.state.BlockState> materials) {
        standard.materials(materials);
    }
    private net.minecraft.world.phys.Vec3 skyPosition=net.minecraft.world.phys.Vec3.ZERO; private float skyTick; private boolean skyValid;
    private float[] sky() {
        if(!skyValid) {
            var level=Minecraft.getInstance().level;
            if(level!=null){var c=level.getSkyColor(skyPosition,skyTick);sky[0]=(float)c.x;sky[1]=(float)c.y;sky[2]=(float)c.z;}
            skyValid=true;
        }
        return sky;
    }
    public void capture(Camera camera, Matrix4f view, Matrix4f renderedProjection, float partialTick, int width, int height) {
        Minecraft mc=Minecraft.getInstance(); var pos=camera.getPosition();
        captureSerial++;
        if(written.size()>512){written.clear();dynamicMembers.clear();}
        if(previousLevel!=mc.level) {
            previousLevel=mc.level;packValues.reset();expressions=plan.expressions().runtime();lastNanos=0;
        }
        ForwardDepthProjection.toLegacy(renderedProjection,legacyCameraProjection);
        if(UNIFORM_DUMP!=null&&frame%240==0)org.slf4j.LoggerFactory.getLogger("VSDH-Shaders").info(
                "PROJECTION_DUMP jomlProbeM32={} rendered m22={} m23={} m32={} m33={} legacyNear={} legacyFar={}",new Matrix4f().perspective(1f,1f,.05f,256f).m32(),renderedProjection.m22(),
                renderedProjection.m23(),renderedProjection.m32(),renderedProjection.m33(),
                legacyCameraProjection.perspectiveNear(),legacyCameraProjection.perspectiveFar());
        matrices.begin(mc.level,view,legacyCameraProjection,pos.x,pos.y,pos.z,width,height);
        builtins.capture(camera,matrices,partialTick,plan.directives().sunPathRotation());
        this.width=width; this.height=height; this.far=mc.gameRenderer.getRenderDistance();
        farPlane=legacyCameraProjection.perspectiveFar();
        // The near plane of the depth the packs actually read. Reforged's perspective arrives with an
        // effective 0.1 here, and packs linearising depth with `near` must use the same value.
        float projectionNear=legacyCameraProjection.perspectiveNear();
        cameraNear=projectionNear>0&&Float.isFinite(projectionNear)?projectionNear:.05f;
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
        skyPosition=pos; skyTick=partialTick; skyValid=false; // biome-blended, so only computed when a pack reads it
        worldTime=(int)Math.floorMod(mc.level.getDayTime(),24000); worldDay=(int)Math.floorMod(Math.floorDiv(mc.level.getDayTime(),24000),Integer.MAX_VALUE);
        rain=mc.level.getRainLevel(partialTick);
        long now=System.nanoTime(); frameTime=lastNanos==0?0:Math.min(1,(now-lastNanos)*1e-9f); lastNanos=now;
        elapsed=(elapsed+frameTime)%3600;
        standard.capture(pos,partialTick,frameTime,rain,builtins.blindness(),builtins.sunAngle(),builtins.waterDensity());
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
        } else {
            captureDistantScalars();
        }
    }
    /** Same DH clip planes and LOD distance as the DH programs read, for packs without those programs. */
    private void captureDistantScalars() {
        if(!de.stranger.vsdh.Vsdh.distantHorizons())return; // optional mod: keep the defaults
        try {
            var near=com.seibel.distanthorizons.core.util.RenderUtil.getNearClipPlaneInBlocks();
            if(com.seibel.distanthorizons.core.util.RenderUtil.getHeightBasedNearClipOverrideBlockDistance()==-1)near=Math.min(near,7.5f);
            var far=com.seibel.distanthorizons.core.util.RenderUtil.getFarClipPlaneDistanceInBlocks();
            if(near>0&&far>near&&Float.isFinite(far)){dhNear=near;dhFar=far;}
            dhDistance=com.braffolk.dhvulkan.core.DhConfigHelper.lodChunkRenderDistanceRadius()*16;
        } catch(RuntimeException|LinkageError notReady) {
            // DH has not finished its setup: keep the last reported values.
        }
    }
    public void write(NativeUniformProgram pass, boolean fullscreen, int renderStage, float alphaRef) {
        write(pass,fullscreen?DrawSpace.FULLSCREEN:DrawSpace.WORLD,renderStage,alphaRef);
    }
    public void writeShadow(NativeGraphicsProgram pass) {write(pass,DrawSpace.SHADOW,0,.1f);}
    void writeShadowCaster(NativeGraphicsProgram pass, boolean blocks) {
        write(pass, DrawSpace.SHADOW, (blocks ? dev.vitrail.pack.model.RenderStage.BLOCK_ENTITIES
                : dev.vitrail.pack.model.RenderStage.ENTITIES).ordinal(), .1f);
    }
    public void writeDistant(NativeGraphicsProgram pass, boolean water) {
        write(pass,DrawSpace.DISTANT,water?17:8,0);
    }
    public void captureDistant(com.braffolk.dhvulkan.core.data.RenderUniforms frame) {
        captureSerial++; // DH matrices arrive mid-frame
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
        distantProjection.get(dhProjection);
        distantProjection.invert(scratch).get(dhInverse);
        distantView.get(dhView);
        scratch.set(distantProjection).mul(distantView).get(dhMvp);
        distantView.normal(normalScratch).get(dhNormal);
        if(!dhHistory)System.arraycopy(dhProjection,0,dhPrevious,0,16);
        dhNear=near;dhFar=far;
        dhDistance=com.braffolk.dhvulkan.core.DhConfigHelper.lodChunkRenderDistanceRadius()*16;
    }

    void writeDraw(NativeGraphicsProgram pass,Matrix4f modelView,Matrix4f projection,dev.vitrail.pack.model.RenderStage stage) {
        ForwardDepthProjection.toLegacy(projection,localProjection);
        modelView.get(localView);
        RenderSystem.getTextureMatrix().get(localTextureMatrix);
        localProjection.get(localProjectionValues);
        scratch.set(localProjection).mul(modelView).get(localMvp);
        modelView.normal(normalScratch).get(localNormal);
        var color=net.vulkanmod.vulkan.VRenderSystem.getShaderColor();
        for(int i=0;i<4;i++)passColour[i]=color.getFloat(i*4);
        write(pass,DrawSpace.DRAW,stage.ordinal(),0);
    }

    /** -Dvsdh.uniformDump=<program suffix>: log that program's written globals every 240 frames. */
    private static final String UNIFORM_DUMP=System.getProperty("vsdh.uniformDump");
    private void dump(NativeUniformProgram pass) {
        if(UNIFORM_DUMP==null||frame%240!=0||!pass.programName().endsWith(UNIFORM_DUMP))return;
        var text=new StringBuilder("UNIFORM_DUMP ").append(pass.programName()).append(':');
        var buffer=pass.uniforms();
        for(var member:pass.activeUniforms()) {
            boolean integer=member.type().startsWith("int")||member.type().startsWith("ivec")||member.type().startsWith("uint")
                    ||member.type().startsWith("uvec")||member.type().equals("bool");
            int count=Math.min(16,Math.max(1,member.size()/4));
            text.append(' ').append(member.name()).append('=');
            for(int i=0;i<count;i++) {
                int at=member.offset()+i*4;
                if(i>0)text.append(',');
                text.append(integer?Integer.toString(buffer.getInt(at)):Float.toString(buffer.getFloat(at)));
            }
        }
        org.slf4j.LoggerFactory.getLogger("VSDH-Shaders").info(text.toString());
    }
    /** Members whose value can differ between two draws of one program within a frame. */
    private static final Set<String> PER_DRAW=Set.of("of_ModelViewMatrix","of_ProjectionMatrix","of_ModelViewProjectionMatrix",
            "of_NormalMatrix","of_PassColour","of_TextureMatrix","renderStage","alphaTestRef","of_GlintAlpha","entityId",
            "blockEntityId","entityColor","currentRenderedItemId","gtextureSize","blendFunc","fogStart","fogEnd","fogDensity",
            "fogMode","fogShape","fogColor");
    private final java.util.IdentityHashMap<NativeUniformProgram,long[]> written=new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<NativeUniformProgram,java.util.List<Std140Layout.Member>> dynamicMembers=new java.util.IdentityHashMap<>();
    private long captureSerial;
    private float cameraNear=.05f;
    private void write(NativeUniformProgram pass,DrawSpace space,int renderStage,float alphaRef) {
        float[] drawView=switch(space) {
            case WORLD->view;case FULLSCREEN->IDENTITY;case SHADOW->shadowView;case DISTANT->dhView;case DRAW->localView;
        };
        float[] drawProjection=switch(space) {
            case WORLD->projection;case FULLSCREEN->QUAD;case SHADOW->shadowProjection;case DISTANT->dhProjection;case DRAW->localProjectionValues;
        };
        float[] drawMvp=switch(space) {
            case WORLD->mvp;case FULLSCREEN->QUAD;case SHADOW->shadowMvp;case DISTANT->dhMvp;case DRAW->localMvp;
        };
        float[] drawNormal=switch(space) {
            case WORLD->normal;case FULLSCREEN->NORMAL_ID;case SHADOW->shadowNormal;case DISTANT->dhNormal;case DRAW->localNormal;
        };
        var layout=pass.layout(); var buffer=pass.uniforms();
        // A program drawn many times in one frame (terrain layers, particles, entities, DH batches)
        // writes its frame-constant members once per frame and space; later draws refresh only what
        // can change between draws. This was a quarter of the render thread's time at uncapped FPS.
        long[] state=written.get(pass);
        boolean full=state==null||state[0]!=captureSerial||state[1]!=space.ordinal();
        if(full) {
            if(state==null){state=new long[2];written.put(pass,state);}
            state[0]=captureSerial;state[1]=space.ordinal();
        }
        var members=full?pass.activeUniforms():dynamicMembers.computeIfAbsent(pass,
                p->p.activeUniforms().stream().filter(m->PER_DRAW.contains(m.name())).toList());
        for (var member:members) {
            if(expressions.write(member,layout,buffer)||packValues.write(member,layout,buffer))continue;
            if(builtins.write(member,layout,buffer))continue;
            if(standard.write(member,layout,buffer,space==DrawSpace.FULLSCREEN))continue;
            String name=member.name();
            if(zeroFilled.contains(name)){zero(member,buffer);continue;} float[] matrix=switch(name) {
                case "of_ModelViewMatrix"->drawView;
                case "of_ProjectionMatrix"->drawProjection;
                case "of_ModelViewProjectionMatrix"->drawMvp;
                case "of_NormalMatrix"->drawNormal;
                case "of_PassColour"->passColour;
                case "dhProjection"->dhProjection;case "dhProjectionInverse"->dhInverse;case "dhPreviousProjection"->dhPrevious;
                case "shadowModelView"->shadowView;case "shadowProjection"->shadowProjection;
                case "shadowModelViewInverse"->shadowInverseView;case "shadowProjectionInverse"->shadowInverseProjection;
                case "gbufferModelView"->view; case "gbufferModelViewInverse"->inverseView;
                case "gbufferProjection"->projection; case "gbufferProjectionInverse"->inverseProjection;
                case "gbufferPreviousModelView"->previousView; case "gbufferPreviousProjection"->previousProjection;
                case "of_DepthConv"->DEPTH; case "cameraPosition"->camera; case "previousCameraPosition"->previousCamera;
                case "fogColor"->fog; case "skyColor"->sky(); default->null;
            };
            if (matrix!=null) { layout.putFloats(buffer,name,0,matrix); continue; }
            if (name.equals("of_TextureMatrix")) {
                for (int i=0;i<member.arrayLength();i++) layout.putFloats(buffer,name,i,i==1?LIGHT:i==0&&space==DrawSpace.DRAW?localTextureMatrix:IDENTITY);
                continue;
            }
            if (member.type().equals("int")) {
                integer[0]=switch(name) {case "worldTime"->worldTime;case "worldDay"->worldDay;case "frameCounter"->frame;case "renderStage"->renderStage;case "dhRenderDistance"->dhDistance;
                    case "blockEntityId" -> -1; // Outside a block-entity mesh, matching Iris's default.
                    case "entityId"->{if(space==DrawSpace.FULLSCREEN)throw new IllegalArgumentException("Entity context is unavailable in fullscreen passes");yield 0;}
                    default->throw new IllegalArgumentException("Unknown integer world uniform "+name);};
                layout.putInts(buffer,name,0,integer); continue;
            }
            scalar[0]=switch(name) {
                case "viewWidth"->width;case "viewHeight"->height;case "aspectRatio"->(float)width/height;
                case "of_GlintAlpha"->RenderSystem.getShaderGlintAlpha();
                case "near"->cameraNear;case "far"->far;case "farPlane"->farPlane;case "frameTime"->frameTime;case "frameTimeCounter"->elapsed;
                case "rainStrength"->rain;case "eyeAltitude"->camera[1];case "alphaTestRef"->alphaRef;case "dhNearPlane"->dhNear;case "dhFarPlane"->dhFar;
                default->throw new IllegalArgumentException("Unknown world uniform "+name);
            };
            layout.putFloats(buffer,name,0,scalar);
        }
        if(UNIFORM_DUMP!=null)dump(pass);
    }
    public void commit() {
        matrices.commit();
        if(plan.distant()!=null){System.arraycopy(dhProjection,0,dhPrevious,0,16);dhHistory=true;}
        frame=(frame+1)%720720;
    }
    public void reset() { matrices.reset();lastNanos=0;dhHistory=false;written.clear();dynamicMembers.clear(); }
    @Override public long frameCounter() { return frame; }
    @Override public void read(String name,Val out) {
        if(builtins.read(name,out)||standard.read(name,out)||packValues.read(name,out))return;
        switch(name) {
            case "frameCounter"->out.set(frame);case "worldTime"->out.set(worldTime);case "worldDay"->out.set(worldDay);
            case "frameTime"->out.set(frameTime);case "frameTimeCounter"->out.set(elapsed);
            case "rainStrength"->out.set(rain);case "eyeAltitude"->out.set(camera[1]);
            case "viewWidth"->out.set((float)width);case "viewHeight"->out.set((float)height);
            case "aspectRatio"->out.set((float)width/height);case "near"->out.set(cameraNear);case "far"->out.set(far);
            case "farPlane" -> out.set(farPlane);
            case "dhNearPlane" -> out.set(dhNear); case "dhFarPlane" -> out.set(dhFar);
            case "dhRenderDistance" -> out.set(dhDistance);
            case "gbufferProjection" -> out.set(matrices.projection);
            case "gbufferProjectionInverse" -> out.set(matrices.inverseProjection);
            case "gbufferModelView" -> out.set(matrices.view);
            case "gbufferModelViewInverse" -> out.set(matrices.inverseView);
            case "gbufferPreviousProjection" -> out.set(matrices.previousProjection);
            case "gbufferPreviousModelView" -> out.set(matrices.previousView);
            case "shadowModelView" -> out.set(shadowCamera.view);
            case "shadowModelViewInverse" -> out.set(shadowCamera.inverseView);
            case "shadowProjection" -> out.set(shadowCamera.projection);
            case "shadowProjectionInverse" -> out.set(shadowCamera.inverseProjection);
            case "cameraPosition"->out.set(camera[0],camera[1],camera[2]);
            case "previousCameraPosition"->out.set(previousCamera[0],previousCamera[1],previousCamera[2]);
            case "fogColor"->out.set(fog[0],fog[1],fog[2]);case "skyColor"->{float[] c=sky();out.set(c[0],c[1],c[2]);}
            default->throw new IllegalArgumentException("Unknown expression snapshot value: "+name);
        }
    }
}

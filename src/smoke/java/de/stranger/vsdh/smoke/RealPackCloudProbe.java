package de.stranger.vsdh.smoke;

import de.stranger.vsdh.shader.*;
import dev.vitrail.glsl.ProgramTranslator.TranslatedProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.target.TargetSchedule.Side;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.ImageUtil;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import static org.lwjgl.vulkan.VK10.*;

/** In-memory instrumentation of the actual translated shader. Never packaged as a replacement pack. */
final class RealPackCloudProbe {
    private static PackProgramSource.Prepared probe;
    static void prepare(WorldPackPlan plan) {
        var original=plan.chain().stages().get(ShaderChainPlan.Stage.DEFERRED).stream().filter(p->p.name().equals("deferred1")).findFirst().orElseThrow().program();
        var translated=original.translated();var fragment=translated.stages().get(ProgramStage.FRAGMENT);
        String point="vec3 playerPos = ViewToPlayer(viewPos.xyz);";
        if(!fragment.text().contains(point))throw new AssertionError("Cloud probe insertion point changed");
        String instrumented=fragment.text().replace(point,point+"\nofFragData0 = vec4(normalize(playerPos).y, float(cloudAlt1i), cloudHeightM, cameraPosition.y); return;\n");
        var stages=new HashMap<>(translated.stages());
        stages.put(ProgramStage.FRAGMENT,new TranslatedUnit(fragment.entry(),fragment.stage(),instrumented,fragment.notes(),fragment.drawBuffers(),fragment.blockMembers(),fragment.samplers()));
        var debug=new TranslatedProgram(stages,translated.uniforms(),translated.samplers(),translated.sampled(),translated.synthesized(),translated.inputs());
        probe=new PackProgramSource.Prepared("cloud-coordinate-probe",debug,VulkanProgramCompiler.compile("cloud-coordinate-probe",debug));
    }
    static void run(WorldShaderSession session) {
        var output=session.presentationImage();
        var framebuffer=Framebuffer.builder(output.width,output.height,1,false).setColorFormats(VK_FORMAT_R32G32B32A32_SFLOAT).build();
        var pass=RenderPass.builder(framebuffer).setLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).build();
        var bytes=MemoryUtil.memAlloc(16);
        try(var scope=Renderer.getInstance().temporaryRenderTarget();var program=new NativeFullscreenPass(probe.path(),probe.translated(),probe.compiled())) {
            program.prepare(pass);
            var source=session.chain().program("deferred1");
            if(program.uniforms().capacity()!=source.uniforms().capacity())throw new AssertionError("Cloud probe block layout changed");
            var from=source.uniforms().duplicate();from.clear();var to=program.uniforms().duplicate();to.clear();to.put(from);
            for(String sampler:program.requiredSamplers())program.bindImage(sampler,source.boundImage(sampler));
            program.render(framebuffer,pass);
            var target=framebuffer.getColorAttachment();
            // A pixel low on the screen; the pass stores rows bottom-up (GL orientation).
            int row=target.isRowsBottomUp()?output.height-1-output.height*4/5:output.height*4/5;
            ImageUtil.downloadTextureRegion(target,MemoryUtil.memAddress(bytes),output.width/2,row,1,1,VK_IMAGE_ASPECT_COLOR_BIT,16);
            LoggerFactory.getLogger("VSDH-Smoke").info("REAL PACK CLOUD GPU VALUES: bottomRayY={}, cloudAltitude={}, derivedCloudHeight={}, cameraY={}",bytes.getFloat(0),bytes.getFloat(4),bytes.getFloat(8),bytes.getFloat(12));
            if(!(bytes.getFloat(0)<-.5f)||Math.abs(bytes.getFloat(4)-192)>.001f||Math.abs(bytes.getFloat(8)-192)>.001f)
                throw new AssertionError("Actual pack reconstructs the down-looking scene incorrectly");
        } finally {pass.cleanUp();framebuffer.cleanUp();MemoryUtil.memFree(bytes);}
    }
}

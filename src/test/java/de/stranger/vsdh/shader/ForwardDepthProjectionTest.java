package de.stranger.vsdh.shader;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForwardDepthProjectionTest {
    @Test void forwardVulkanProjectionMatchesLegacyBeforeNativeEpilogue() {
        var vulkan=new Matrix4f().setPerspective(1.2f,16/9f,.05f,256,true);
        var expected=new Matrix4f().setPerspective(1.2f,16/9f,.05f,256,false);
        var legacy=ForwardDepthProjection.toLegacy(vulkan,new Matrix4f());
        assertTrue(legacy.equals(expected,1e-6f));
        for(float z:new float[]{-.05f,-1,-64,-256}) {
            var a=vulkan.transform(new Vector4f(.02f,.03f,z,1));
            var b=legacy.transform(new Vector4f(.02f,.03f,z,1));
            assertEquals(a.z/a.w,(b.z/b.w)*.5f+.5f,1e-6);
        }
    }
    @Test void conversionPreservesComposedBobAndSupportsInPlaceOperation() {
        var bob=new Matrix4f().translate(.02f,-.1f,.04f).rotateZ(.06f);
        var source=new Matrix4f().setPerspective(1.2f,1.7f,.05f,256,true).mul(bob);
        var expected=new Matrix4f().setPerspective(1.2f,1.7f,.05f,256,false).mul(bob);
        ForwardDepthProjection.toLegacy(source,source);assertTrue(source.equals(expected,1e-6f));
    }
    @Test void finiteSkyDepthStaysInFrontWhereJomlInfiniteEpsilonReversesIt() {
        var infinite=ForwardDepthProjection.toLegacy(new Matrix4f().setPerspective(1.2f,1.7f,.05f,Float.POSITIVE_INFINITY,true),new Matrix4f());
        var finite=ForwardDepthProjection.toLegacy(new Matrix4f().setPerspective(1.2f,1.7f,.05f,256,true),new Matrix4f());
        var bad=new Vector4f(0,.6f,1,1).mul(infinite.invert());bad.div(bad.w);
        var good=new Vector4f(0,.6f,1,1).mul(finite.invert());good.div(good.w);
        assertTrue(bad.z>0,"Reproduce the actual infinite-far background reversal");
        assertTrue(good.z<0,"A finite sky sample is in front of the camera");
    }
}

package de.stranger.vsdh.shader;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldMatricesTest {
    @Test void supportsReforgedInfiniteFarProjectionWithoutNonFiniteValues() {
        var state=new WorldMatrices();var projection=new Matrix4f().perspective(1,16f/9,.05f,Float.POSITIVE_INFINITY);
        state.begin(this,new Matrix4f(),projection,0,70,0,1280,720);
        assertTrue(state.projection.isFinite());assertTrue(state.inverseProjection.isFinite());
        var clip=state.projection.transform(new Vector4f(0,0,-10000,1));
        float depth=(clip.z/clip.w)*.5f+.5f;assertTrue(Float.isFinite(depth)&&depth<=1&&depth>.99);
    }
    @Test void projectedWorldPointKeepsGlOrientationAndDepthRoundTrip() {
        var state=new WorldMatrices();var projection=new Matrix4f().perspective((float)Math.toRadians(70),16f/9,.05f,512);
        state.begin(this,new Matrix4f(),projection,0,80,0,1280,720);
        var clip=state.projection.transform(new Vector4f(0,1,-4,1));clip.div(clip.w);
        // GL orientation as under Iris: eye-space up is NDC up (larger texture V, gbufferProjection[1][1] > 0).
        assertTrue(clip.y>0,"Eye-space up must map to a larger sampled image V");
        assertTrue(state.projection.m11()>0,"gbufferProjection[1][1] stays positive (packs derive their FOV from it)");
        float nativeDepth=clip.z*.5f+.5f;assertTrue(nativeDepth>0&&nativeDepth<1);
        var eye=state.inverseProjection.transform(new Vector4f(clip.x,clip.y,nativeDepth*2-1,1));eye.div(eye.w);
        assertEquals(1,eye.y,1e-4);assertEquals(-4,eye.z,1e-4);
        assertEquals(projection.m22(),state.projection.m22(),0,"Do not convert the depth twice");
    }
    @Test void historyAdvancesOnlyOnCommitAndResetsForResizeWorldAndTeleport() {
        var state=new WorldMatrices();Object world=new Object();var p=new Matrix4f().perspective(1,1,.05f,512);
        state.begin(world,new Matrix4f(),p,5,70,6,800,600);state.commit();
        var moved=new Matrix4f().rotateY(.3f);
        state.begin(world,moved,p,6,70,7,800,600);
        assertEquals(5,state.previousX());assertEquals(1,state.previousView.m00(),1e-6);state.commit();
        state.begin(world,new Matrix4f(),p,7,70,8,1024,600);
        assertEquals(7,state.previousX());assertEquals(state.view,state.previousView);state.commit();
        Object secondWorld=new Object();
        state.begin(secondWorld,moved,p,8,70,9,1024,600);assertEquals(state.view,state.previousView);state.commit();
        state.begin(secondWorld,moved,p,10000,70,9,1024,600);assertEquals(10000,state.previousX());
    }
    @Test void singularMatricesAndIncompleteFrameSequenceAreRefused() {
        var state=new WorldMatrices();var identity=new Matrix4f();
        assertThrows(IllegalArgumentException.class,()->state.begin(this,identity,new Matrix4f().zero(),0,0,0,800,600));
        state.begin(this,identity,identity,0,0,0,800,600);
        assertThrows(IllegalStateException.class,()->state.begin(this,identity,identity,0,0,0,800,600));
        state.commit();assertThrows(IllegalStateException.class,state::commit);
    }
}

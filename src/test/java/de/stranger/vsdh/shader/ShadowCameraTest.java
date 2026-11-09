package de.stranger.vsdh.shader;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowCameraTest {
    @Test void orthographicBoundariesHaveOneNativeYAndOneDeferredDepthConversion() {
        var camera=new ShadowCamera();camera.capture(.25f,128,-100.05f,156,0,0,0,0,0);
        assertEquals(1,camera.projection.transform(new Vector4f(128,0,0,1)).x,1e-6);
        assertEquals(-1,camera.projection.transform(new Vector4f(0,128,0,1)).y,1e-6);
        assertEquals(-1,camera.projection.transform(new Vector4f(0,0,100.05f,1)).z,1e-6);
        assertEquals(1,camera.projection.transform(new Vector4f(0,0,-156,1)).z,1e-6);
    }
    @Test void inverseMatricesRoundTripAndMidnightUsesTheMoon() {
        var day=new ShadowCamera();var night=new ShadowCamera();
        day.capture(.125f,96,-100,180,2,-25,-3.25,120.625,-7.125);
        night.capture(.625f,96,-100,180,2,-25,-3.25,120.625,-7.125);
        assertTrue(day.view.equals(night.view,1e-6f));
        assertTrue(new Matrix4f(day.view).mul(day.inverseView).equals(new Matrix4f(),1e-5f));
        assertTrue(new Matrix4f(day.projection).mul(day.inverseProjection).equals(new Matrix4f(),1e-5f));
    }
    @Test void gridSnappingKeepsSubBlockPrecisionNearWorldBorder() {
        var near=new ShadowCamera();var border=new ShadowCamera();
        near.capture(.25f,128,-100,156,2,0,-1.875,100.625,1.125);
        border.capture(.25f,128,-100,156,2,0,-29999999.875,100.625,29999999.125);
        assertTrue(near.view.equals(border.view,1e-6f));
    }
    @Test void invalidExtentsAndPlanesAreRejectedInsteadOfCreatingNaNs() {
        var camera=new ShadowCamera();
        assertThrows(IllegalArgumentException.class,()->camera.capture(.25f,0,-100,156,2,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->camera.capture(.25f,128,156,-100,2,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->camera.capture(.25f,128,-100,156,Float.NaN,0,0,0,0));
    }
}

package de.stranger.vsdh.shader;

import dev.vitrail.pack.target.PackDirectives;
import org.joml.Matrix4f;

/** Camera-relative light view, legacy GL depth until the native vertex epilogue, native image Y. */
public final class ShadowCamera {
    public static final java.util.Set<String> NAMES=java.util.Set.of("shadowModelView","shadowModelViewInverse","shadowProjection","shadowProjectionInverse");
    public final Matrix4f view=new Matrix4f(),projection=new Matrix4f(),inverseView=new Matrix4f(),inverseProjection=new Matrix4f();
    public void capture(PackDirectives directives,float sunAngle,double x,double y,double z,float renderDistance) {
        float near=directives.shadowNearPlane()==-1?-renderDistance:directives.shadowNearPlane();
        float far=directives.shadowFarPlane()==-1?renderDistance:directives.shadowFarPlane();
        capture(sunAngle,directives.shadowDistance(),near,far,directives.shadowIntervalSize(),directives.sunPathRotation(),x,y,z);
    }
    public void capture(float sunAngle,float distance,float near,float far,float interval,float rotation,double x,double y,double z) {
        if(!Float.isFinite(sunAngle)||!Float.isFinite(distance)||distance<=0||!Float.isFinite(near)||!Float.isFinite(far)||far<=near
                ||!Float.isFinite(interval)||interval<0||!Float.isFinite(rotation)||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))
            throw new IllegalArgumentException("Invalid shadow camera parameters");
        float lightAngle=sunAngle>.5f?sunAngle-.5f:sunAngle;
        float skyAngle=lightAngle<.25f?lightAngle+.75f:lightAngle-.25f;
        view.identity().rotateX((float)(Math.PI/2)).rotateZ((float)(skyAngle*-2*Math.PI)).rotateX((float)Math.toRadians(rotation));
        if(interval!=0) view.translate((float)(x%interval)-interval/2,(float)(y%interval)-interval/2,(float)(z%interval)-interval/2);
        view.invert(inverseView);
        projection.setOrthoSymmetric(distance*2,distance*2,near,far,false);
        projection.invert(inverseProjection);
    }
}

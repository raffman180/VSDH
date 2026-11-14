package net.vulkanmod.mixin.compatibility.gl;

import org.lwjgl.opengl.GL21C;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.FloatBuffer;

@Mixin(GL21C.class)
public class GL21M {

    @Overwrite(remap = false)
    public static void glUniformMatrix2x3fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") FloatBuffer value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix2x3fv");
    }

    @Overwrite(remap = false)
    public static void glUniformMatrix2x3fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") float[] value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix2x3fv");
    }

    @Overwrite(remap = false)
    public static void glUniformMatrix3x2fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") FloatBuffer value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix3x2fv");
    }

    @Overwrite(remap = false)
    public static void glUniformMatrix3x2fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") float[] value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix3x2fv");
    }

    @Overwrite(remap = false)
    public static void glUniformMatrix2x4fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") FloatBuffer value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix2x4fv");
    }

    @Overwrite(remap = false)
    public static void glUniformMatrix2x4fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") float[] value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix2x4fv");
    }

    @Overwrite(remap = false)
    public static void glUniformMatrix4x2fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") FloatBuffer value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix4x2fv");
    }

    @Overwrite(remap = false)
    public static void glUniformMatrix4x2fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") float[] value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix4x2fv");
    }

    @Overwrite(remap = false)
    public static void glUniformMatrix3x4fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") FloatBuffer value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix3x4fv");
    }

    @Overwrite(remap = false)
    public static void glUniformMatrix3x4fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") float[] value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix3x4fv");
    }

    @Overwrite(remap = false)
    public static void glUniformMatrix4x3fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") FloatBuffer value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix4x3fv");
    }

    @Overwrite(remap = false)
    public static void glUniformMatrix4x3fv(@NativeType("GLint") int location, @NativeType("GLboolean") boolean transpose, @NativeType("GLfloat const *") float[] value) {
        net.vulkanmod.gl.GlEmulationLog.unimplemented("glUniformMatrix4x3fv");
    }
}

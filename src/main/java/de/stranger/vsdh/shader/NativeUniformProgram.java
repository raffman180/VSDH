package de.stranger.vsdh.shader;

import java.nio.ByteBuffer;
import java.util.List;

/** A native pack program whose std140 globals block WorldUniforms fills before each draw or dispatch. */
public interface NativeUniformProgram {
    Std140Layout layout();
    List<Std140Layout.Member> activeUniforms();
    ByteBuffer uniforms();
    /** The pack path of the program, for diagnostics only. */
    default String programName() { return getClass().getSimpleName(); }
}

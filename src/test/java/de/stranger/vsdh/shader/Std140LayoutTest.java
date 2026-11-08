package de.stranger.vsdh.shader;

import dev.vitrail.glsl.TranslatedUnit.Uniform;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Std140LayoutTest {
    @Test void offsetsAgreeWithIndependentSpirvCompiler() {
        var declarations = List.of("float a", "vec2 b", "vec3 c", "float d", "float e[3]",
                "mat3 f", "mat2x3 g[2]", "ivec2 h", "bool i", "dvec3 j", "double k", "dmat2 l");
        List<Uniform> uniforms = new ArrayList<>();
        for (String declaration : declarations) {
            String name = declaration.substring(declaration.indexOf(' ') + 1).replaceAll("\\[.*", "");
            uniforms.add(Uniform.of(name, declaration));
        }
        var layout = new Std140Layout(uniforms);
        String source = "#version 450\nlayout(std140,binding=0) uniform Check {\n"
                + String.join(";\n", declarations) + ";\n};\n"
                + "void main(){gl_Position=vec4(a+b.x+c.x+d+e[0]+f[0][0]+g[0][0][0]+float(h.x)"
                + "+float(i)+float(j.x+k+l[0][0]));}";
        long compiler = Shaderc.shaderc_compiler_initialize();
        long result = 0;
        try {
            result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_glsl_vertex_shader,
                    "std140-test.vsh", "main", 0);
            assertNotEquals(0, result);
            assertEquals(Shaderc.shaderc_compilation_status_success, Shaderc.shaderc_result_get_compilation_status(result),
                    Shaderc.shaderc_result_get_error_message(result));
            ByteBuffer words = Shaderc.shaderc_result_get_bytes(result).duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int compared = 0;
            for (int cursor = 20; cursor < words.limit();) {
                int instruction = words.getInt(cursor);
                int count = instruction >>> 16;
                assertTrue(count > 0);
                if ((instruction & 0xffff) == 72 && words.getInt(cursor + 12) == 35) { // OpMemberDecorate Offset
                    int member = words.getInt(cursor + 8);
                    int offset = words.getInt(cursor + 16);
                    assertEquals(offset, layout.members().get(member).offset(), declarations.get(member));
                    compared++;
                }
                cursor += count * 4;
            }
            assertEquals(declarations.size(), compared);
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    @Test void writesMatrixColumnsAndArrayElementsWithoutChangingPadding() {
        var layout = new Std140Layout(List.of(Uniform.of("matrix", "mat3 matrix[2]"), Uniform.of("flag", "bool flag")));
        var buffer = ByteBuffer.allocate(layout.size()).order(ByteOrder.nativeOrder());
        for (int i = 0; i < buffer.capacity(); i++) buffer.put(i, (byte) 0x5a);
        layout.putFloats(buffer, "matrix", 1, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertEquals(1, buffer.getFloat(48));
        assertEquals(4, buffer.getFloat(64));
        assertEquals(9, buffer.getFloat(88));
        assertEquals(0x5a5a5a5a, buffer.getInt(60));
        assertEquals(0x5a5a5a5a, buffer.getInt(0));
        layout.putInts(buffer, "flag", 0, 1);
        assertEquals(1, buffer.getInt(layout.member("flag").offset()));
        assertThrows(IndexOutOfBoundsException.class, () -> layout.putFloats(buffer, "matrix", 2, 1));
        assertThrows(IllegalArgumentException.class, () -> layout.putFloats(buffer, "flag", 0, 1));
    }

    @Test void rejectsUnresolvedAndOversizedLayouts() {
        assertThrows(IllegalArgumentException.class, () -> new Std140Layout(List.of(Uniform.of("x", "float x[COUNT]"))));
        assertThrows(IllegalArgumentException.class, () -> new Std140Layout(List.of(Uniform.of("x", "mat4 x[20000]"))));
        assertThrows(IllegalArgumentException.class, () -> new Std140Layout(List.of(Uniform.of("x", "float x[2][3]"))));
    }
}

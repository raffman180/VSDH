package de.stranger.vsdh.shader;

import dev.vitrail.glsl.ProgramTranslator.TranslatedProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class VulkanProgramCompilerTest {
    @Test void reorderedVaryingsAreLinkedByNameAndExplicitSlotsArePreserved() {
        var program = new TranslatedProgram(Map.of(
                ProgramStage.VERTEX, unit(ProgramStage.VERTEX, """
                        #version 450
                        out vec2 texcoord;
                        flat out vec4 exposure;
                        layout(location = 5) out float sameName;
                        layout(location = 7) out float otherName;
                        void main() {
                            texcoord = vec2(1.0);
                            exposure = vec4(0.25);
                            sameName = 0.0;
                            otherName = 0.5;
                            gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
                        }
                        """),
                ProgramStage.FRAGMENT, unit(ProgramStage.FRAGMENT, """
                        #version 450
                        flat in vec4 exposure;
                        in vec2 texcoord;
                        layout(location = 7) in float sameName;
                        layout(location = 0) out vec4 color;
                        void main() { color = exposure * vec4(texcoord, sameName, 1.0); }
                        """)), List.of(), List.of(), Set.of(), Map.of(), VertexInputs.WORLD);
        var modules = VulkanProgramCompiler.compile("varying_link", program).stages();
        var vertex = SpirvInterface.stageLocations(modules.get(ProgramStage.VERTEX), SpirvInterface.OUTPUT);
        var fragment = SpirvInterface.stageLocations(modules.get(ProgramStage.FRAGMENT), SpirvInterface.INPUT);

        assertNotEquals(vertex.get("texcoord").value(), vertex.get("exposure").value());
        assertEquals(vertex.get("texcoord").value(), fragment.get("texcoord").value());
        assertEquals(vertex.get("exposure").value(), fragment.get("exposure").value());
        assertEquals(vertex.get("otherName").value(), fragment.get("sameName").value());
    }

    private static TranslatedUnit unit(ProgramStage stage, String source) {
        return new TranslatedUnit("varying_link", stage, source, null, List.of(), List.of(), List.of());
    }
}

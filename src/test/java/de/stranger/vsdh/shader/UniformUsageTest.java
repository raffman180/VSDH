package de.stranger.vsdh.shader;

import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.OpenedPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UniformUsageTest {
    @TempDir Path directory;

    @Test void actualOptimisedShaderKeepsDynamicArrayReadsAndSkipsUnusedFields() throws Exception {
        var prepared=compile("""
                uniform float usedValue;
                uniform vec4 unusedPadding[8];
                uniform vec4 readArray[4];
                uniform int selector;
                void main(){gl_FragColor=vec4(usedValue)+readArray[clamp(selector,0,3)];}
                """);
        var layout=new Std140Layout(prepared.translated().uniforms());
        assertNotNull(layout.member("unusedPadding")); // It is present in the host block.
        assertTrue(layout.member("readArray").offset()>=128);
        var reads=UniformUsage.read(prepared.compiled().stages().get(ProgramStage.FRAGMENT),0,prepared.compiled().bindings().get("OfGlobals"));
        assertFalse(reads.conservative());
        assertEquals(Set.of(layout.member("usedValue").offset(),layout.member("readArray").offset(),layout.member("selector").offset()),reads.offsets());
        var names=UniformUsage.names(prepared.compiled(),layout);
        assertTrue(names.containsAll(Set.of("usedValue","readArray","selector")));
        assertFalse(names.contains("unusedPadding"));
    }

    @Test void stageWithoutUniformReadsHasNoRequiredValues() throws Exception {
        var prepared=compile("uniform float neverRead;\nvoid main(){gl_FragColor=vec4(1.0);}");
        var fragment=prepared.compiled().stages().get(ProgramStage.FRAGMENT);
        assertEquals(Set.of(),UniformUsage.read(fragment,0,0).offsets());
        assertEquals(Set.of(),UniformUsage.read(fragment,0,999).offsets());
        assertThrows(IllegalArgumentException.class,()->UniformUsage.read(new byte[4],0,0));
    }

    @Test void wholeBlockLoadsKeepEveryMemberEvenWithoutDebugNames() {
        var read=UniformUsage.read(fixture(false,false),0,0);
        assertTrue(read.conservative());assertEquals(Set.of(0,16,32),read.offsets());
    }

    @Test void unknownPointerAliasDoesNotHidePotentialReads() {
        var read=UniformUsage.read(fixture(true,false),0,0);
        assertTrue(read.conservative());assertEquals(Set.of(0,16,32),read.offsets());
    }

    @Test void invalidMemberDecorationsAreNotTreatedAsUnusedData() {
        assertThrows(IllegalArgumentException.class,()->UniformUsage.read(fixture(false,true),0,0));
    }

    private PackProgramSource.Prepared compile(String fragment) throws Exception {
        var shaders=directory.resolve("shaders");Files.createDirectories(shaders);
        Files.writeString(shaders.resolve("final.vsh"),"#version 330 compatibility\nvoid main(){gl_Position=ftransform();}\n");
        Files.writeString(shaders.resolve("final.fsh"),"#version 330 compatibility\n"+fragment);
        try(var pack=OpenedPack.open(directory,Map.of(),"")){return PackProgramSource.prepare(pack,"final",VertexInputs.FULLSCREEN);}
    }

    /** Small SPIR-V instruction fixture for conservative paths the GLSL optimiser normally removes. */
    private static byte[] fixture(boolean alias,boolean invalidIndex) {
        var w=new ArrayList<Integer>(List.of(0x07230203,0x00010000,0,32,0));
        op(w,17,1);op(w,14,0,1);op(w,15,4,10,0x6e69616d,0);op(w,16,10,7);
        op(w,71,7,2);op(w,71,9,34,0);op(w,71,9,33,0);
        op(w,72,7,invalidIndex?99:0,35,0);op(w,72,7,1,35,16);op(w,72,7,2,35,32);
        op(w,19,1);op(w,33,2,1);op(w,22,3,32);op(w,21,4,32,1);op(w,32,5,2,3);op(w,43,4,6,1);
        op(w,30,7,3,3,3);op(w,32,8,2,7);op(w,59,8,9,2);
        op(w,54,1,10,0,2);op(w,248,11);
        if(alias){op(w,83,8,14,9);op(w,65,5,16,14,6);op(w,61,3,17,16);}
        else op(w,61,7,15,9);
        op(w,253);op(w,56);
        var bytes=ByteBuffer.allocate(w.size()*4).order(ByteOrder.LITTLE_ENDIAN);w.forEach(bytes::putInt);return bytes.array();
    }
    private static void op(List<Integer> words,int opcode,int... operands){words.add((operands.length+1)<<16|opcode);for(int operand:operands)words.add(operand);}
}

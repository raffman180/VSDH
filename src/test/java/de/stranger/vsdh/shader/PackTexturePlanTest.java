package de.stranger.vsdh.shader;

import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.SamplerPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PackTexturePlanTest {
    @TempDir Path directory;
    private static byte[] png(BufferedImage image) throws IOException {
        var bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"PNG",bytes));return bytes.toByteArray();
    }
    @Test void rgbaAndGrayscalePreserveRawBytesAndTopRowWithoutColorSpaceConversion() throws Exception {
        var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,0x80204080);image.setRGB(1,0,0xff010203);image.setRGB(0,1,0xff040506);image.setRGB(1,1,0xff070809);
        var decoded=PackTexturePlan.decodePng(png(image),false,true);
        assertEquals(2,decoded.width());assertEquals(2,decoded.height());
        assertArrayEquals(new byte[]{32,64,(byte)128,(byte)128,(byte)1,2,3,(byte)255,4,5,6,(byte)255,7,8,9,(byte)255},decoded.rgba());
        var gray=new BufferedImage(1,1,BufferedImage.TYPE_BYTE_GRAY);gray.getRaster().setSample(0,0,0,128);
        assertArrayEquals(new byte[]{(byte)128,(byte)128,(byte)128,(byte)255},PackTexturePlan.decodePng(png(gray),true,false).rgba());
    }
    @Test void highBitDepthAndOversizedHeadersCannotSilentlyChangeDataOrAllocateHugeImages() throws Exception {
        assertThrows(IOException.class,()->PackTexturePlan.decodePng(png(new BufferedImage(1,1,BufferedImage.TYPE_USHORT_GRAY)),false,false));
        var encoded=png(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB));ByteBuffer.wrap(encoded).putInt(16,Integer.MAX_VALUE);
        assertThrows(IOException.class,()->PackTexturePlan.decodePng(encoded,false,false));
    }
    @Test void declaredCompositeOverrideIsAFileBindingAndSurvivesClosingThePack() throws Exception {
        var shaders=Files.createDirectories(directory.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),"texture.composite.colortex3=lookup.png\n");
        Files.write(shaders.resolve("lookup.png"),png(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB)));
        Files.writeString(shaders.resolve("final.vsh"),"#version 330 compatibility\nvoid main(){gl_Position=ftransform();}\n");
        Files.writeString(shaders.resolve("final.fsh"),"#version 330 compatibility\nuniform sampler2D colortex3;void main(){gl_FragColor=texture2D(colortex3,vec2(0.5));}\n");
        PackTexturePlan textures;
        try(var pack=OpenedPack.open(directory,Map.of(),"")) {
            var chain=ShaderChainPlan.prepare(pack,"");var inputs=chain.stages().get(ShaderChainPlan.Stage.FINAL).getFirst().inputs();
            assertTrue(inputs.stream().anyMatch(b->b.sampler().equals("colortex3")&&b.kind()==SamplerPlan.Kind.PACK_TEXTURE));
            textures=PackTexturePlan.read(pack,chain,Map.of(),null);
        }
        assertInstanceOf(PackTexturePlan.Pixels.class,textures.resolve("final","colortex3"));
        assertEquals(4,((PackTexturePlan.Pixels)textures.resolve("final","colortex3")).rgba().length);
    }
    @Test void generatedNoiseIsDeterministicOpaqueAndHasVaryingChannels() {
        var a=PackTexturePlan.noise(32);var b=PackTexturePlan.noise(32);assertArrayEquals(a.rgba(),b.rgba());
        var distinct=new java.util.HashSet<Byte>();
        for(int i=0;i<a.rgba().length;i+=4){distinct.add(a.rgba()[i]);assertEquals(255,Byte.toUnsignedInt(a.rgba()[i+3]));}
        assertTrue(distinct.size()>200);assertThrows(IllegalArgumentException.class,()->PackTexturePlan.noise(0));
    }
}

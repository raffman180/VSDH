package de.stranger.vsdh.shader;

import dev.vitrail.pack.model.PackTexture;
import dev.vitrail.pack.model.TextureStage;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.texture.PackTextures;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;

/** CPU-owned decoded images. No open ZIP paths, GPU handles or render-thread file reads. */
public record PackTexturePlan(Map<String,Asset> bindings) {
    public sealed interface Asset permits Pixels,GameImage {}
    public record GameImage(String location) implements Asset {}
    public record Pixels(int width,int height,byte[] rgba,boolean blur,boolean clamp) implements Asset {
        public Pixels {
            if(width<=0||height<=0||rgba.length!=Math.multiplyExact(Math.multiplyExact(width,height),4))throw new IllegalArgumentException("Invalid decoded image");
            rgba=rgba.clone();
        }
    }
    public PackTexturePlan {bindings=Map.copyOf(bindings);}
    public static PackTexturePlan empty() {return new PackTexturePlan(Map.of());}
    private static String key(String program,String sampler) {return program.substring(program.lastIndexOf('/')+1)+"/"+sampler;}
    public Asset resolve(String program,String sampler) {return bindings.get(key(program,sampler));}

    public static PackTexturePlan read(OpenedPack pack,ShaderChainPlan chain,Map<String,PackProgramSource.Prepared> terrain,ShadowPackPlan shadow) throws IOException {
        return read(pack,chain,terrain,shadow,null,SkyPackPlan.empty());
    }
    public static PackTexturePlan read(OpenedPack pack,ShaderChainPlan chain,Map<String,PackProgramSource.Prepared> terrain,ShadowPackPlan shadow,DistantPackPlan distant,SkyPackPlan sky) throws IOException {
        var defines=pack.settings().globalDefines(pack.options());
        var textures=PackTextures.read(pack.properties(),defines,pack.source());
        var bindings=new LinkedHashMap<String,Asset>();var decoded=new HashMap<String,Asset>();
        var programs=new LinkedHashMap<String,PackProgramSource.Prepared>();
        terrain.values().forEach(p->programs.put(p.path(),p));
        if(distant!=null)distant.programs().forEach(p->programs.put(p.path(),p));
        sky.programs().values().forEach(p->programs.put(p.path(),p));
        if(shadow!=null)shadow.programs().values().forEach(p->programs.put(p.path(),p));
        chain.stages().values().forEach(passes->passes.forEach(p->programs.put(p.program().path(),p.program())));
        for(var source:programs.values()) {
            String name=source.path().substring(source.path().lastIndexOf('/')+1);
            var stage=TextureStage.of(name).orElse(TextureStage.GBUFFERS);
            var active=new HashSet<Integer>();source.compiled().stages().values().forEach(s->active.addAll(SpirvInterface.bindings(s)));
            for(var sampler:source.translated().samplers()) {
                if(!active.contains(source.compiled().bindings().get(sampler.name())))continue;
                var supplied=textures.resolve(stage,sampler.name());
                if(supplied.isPresent()) {
                    var texture=supplied.get();String id=texture.path()+":"+texture.blur()+":"+texture.clamp();
                    Asset asset=decoded.get(id);
                    if(asset==null){asset=decode(pack,texture);decoded.put(id,asset);}
                    bindings.put(key(name,sampler.name()),asset);
                } else if(sampler.name().equals("noisetex")) {
                    Asset noise=decoded.get("<noise>");
                    if(noise==null) {
                        var path=pack.properties().noiseTexturePath(defines);
                        noise=path.isPresent()?png(pack,path.get(),false,false):noise(chainNoiseSize(pack,chain));decoded.put("<noise>",noise);
                    }
                    bindings.put(key(name,sampler.name()),noise);
                }
            }
        }
        return new PackTexturePlan(bindings);
    }
    private static int chainNoiseSize(OpenedPack pack,ShaderChainPlan chain) throws IOException {
        return dev.vitrail.pack.target.PackDirectives.read(pack.source(),pack.settings(),chain.targets().dimension()).noiseTextureResolution();
    }
    private static Asset decode(OpenedPack pack,PackTexture texture) throws IOException {
        if(texture.raw().isPresent())throw new IOException("Native raw pack texture not implemented: "+texture.describe());
        if(texture.gameResource())return new GameImage(texture.path());
        return png(pack,texture.path(),texture.blur(),texture.clamp());
    }
    private static Pixels png(OpenedPack pack,String path,boolean blur,boolean clamp) throws IOException {
        String relative=path.startsWith("/")?path.substring(1):path;
        var file=pack.source().file(relative).orElseThrow(()->new IOException("Missing pack image: "+path));
        if(Files.size(file)>64L*1024*1024)throw new IOException("Encoded pack PNG exceeds 64 MiB");
        return decodePng(Files.readAllBytes(file),blur,clamp);
    }
    public static Pixels decodePng(byte[] encoded,boolean blur,boolean clamp) throws IOException {
        if(encoded.length<24||ByteBuffer.wrap(encoded).getLong()!=0x89504e470d0a1a0aL)throw new IOException("Expected PNG image");
        var header=ByteBuffer.wrap(encoded);int width=header.getInt(16),height=header.getInt(20);
        if(width<=0||height<=0||width>16384||height>16384||(long)width*height*4>256L*1024*1024)throw new IOException("Pack PNG exceeds image budget");
        if(encoded.length<26||Byte.toUnsignedInt(encoded[24])>8)throw new IOException("Native high-bit-depth PNG textures are not implemented");
        var image=ImageIO.read(new ByteArrayInputStream(encoded));if(image==null)throw new IOException("PNG decoder refused the image");
        for(int bits:image.getSampleModel().getSampleSize())if(bits>8)throw new IOException("Native high-bit-depth PNG textures are not implemented");
        var rgba=new byte[Math.multiplyExact(Math.multiplyExact(width,height),4)];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int argb;
            if(encoded[25]==3) argb=image.getRGB(x,y); // Palette entries are already raw PNG RGB bytes.
            else {
                var raster=image.getRaster();int red=raster.getSample(x,y,0),green,blue;
                if(encoded[25]==0||encoded[25]==4) {
                    int bits=raster.getSampleModel().getSampleSize(0);
                    if(bits<8)red=red*255/((1<<bits)-1);green=red;blue=red;
                } else {green=raster.getSample(x,y,1);blue=raster.getSample(x,y,2);}
                var alpha=image.getAlphaRaster();int opacity=alpha==null?255:alpha.getSample(x,y,0);
                argb=(opacity<<24)|(red<<16)|(green<<8)|blue;
            }
            int offset=(y*width+x)*4;
            rgba[offset]=(byte)(argb>>>16);rgba[offset+1]=(byte)(argb>>>8);rgba[offset+2]=(byte)argb;rgba[offset+3]=(byte)(argb>>>24);
        }
        return new Pixels(width,height,rgba,blur,clamp);
    }
    public static Pixels noise(int size) {
        if(size<1||size>4096)throw new IllegalArgumentException("Unsupported native noise resolution: "+size);
        var bytes=new byte[Math.multiplyExact(Math.multiplyExact(size,size),4)];int state=0x6d2b79f5;
        for(int i=0;i<bytes.length;i+=4) {
            for(int c=0;c<3;c++){state^=state<<13;state^=state>>>17;state^=state<<5;bytes[i+c]=(byte)state;}
            bytes[i+3]=(byte)255;
        }
        return new Pixels(size,size,bytes,false,false);
    }
}

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
    public sealed interface Asset permits Pixels,GameImage,Texels,Volume {}
    /**
     * A pack's 3D blob as a real volume: x fastest, then y, then z (the glTexImage3D order), in a
     * Vulkan format of the blob's own channel count where the device samples one (RGB widened to RGBA).
     */
    public record Volume(int width,int height,int depth,int vkFormat,int texelBytes,byte[] data,boolean blur,boolean clamp) implements Asset {
        public Volume {
            if(width<=0||height<=0||depth<=0||texelBytes<=0
                    ||data.length!=Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(width,height),depth),texelBytes))
                throw new IllegalArgumentException("Invalid volume "+width+"x"+height+"x"+depth+" format "+vkFormat+" bytes "+data.length);
            data=data.clone();
        }
    }
    public record GameImage(String location) implements Asset {}
    /** Raw texels in an explicit four-channel Vulkan format: a 2D blob or a volume laid out as an atlas. */
    public record Texels(int width,int height,int vkFormat,byte[] data,boolean blur,boolean clamp) implements Asset {
        public Texels {
            if(width<=0||height<=0||data.length!=Math.multiplyExact(Math.multiplyExact(width,height),texelBytes(vkFormat)))
                throw new IllegalArgumentException("Invalid raw texels "+width+"x"+height+" format "+vkFormat+" bytes "+data.length);
            data=data.clone();
        }
    }
    public record Pixels(int width,int height,byte[] rgba,boolean blur,boolean clamp) implements Asset {
        public Pixels {
            if(width<=0||height<=0||rgba.length!=Math.multiplyExact(Math.multiplyExact(width,height),4))throw new IllegalArgumentException("Invalid decoded image");
            rgba=rgba.clone();
        }
    }
    private static final Map<String, Pixels> MATERIAL_DEFAULTS = Map.of(
            "normals", new Pixels(1, 1, new byte[]{127, 127, (byte) 255, (byte) 255}, false, false),
            "specular", new Pixels(1, 1, new byte[4], false, false));

    static boolean hasMaterialDefault(String sampler) {return MATERIAL_DEFAULTS.containsKey(sampler);}

    public PackTexturePlan {bindings=Map.copyOf(bindings);}
    public static PackTexturePlan empty() {return new PackTexturePlan(Map.of());}
    private static String key(String program,String sampler) {return program.substring(program.lastIndexOf('/')+1)+"/"+sampler;}
    public Asset resolve(String program,String sampler) {return bindings.get(key(program,sampler));}

    public static PackTexturePlan read(OpenedPack pack,ShaderChainPlan chain,Map<String,PackProgramSource.Prepared> terrain,ShadowPackPlan shadow) throws IOException {
        var defines=pack.settings().globalDefines(pack.options());
        return read(pack,chain,terrain,shadow,null,SkyPackPlan.empty(),ParticlePackPlan.empty(),null,
                PackTextures.read(pack.properties(),defines,pack.source()));
    }
    public static PackTexturePlan read(OpenedPack pack,ShaderChainPlan chain,Map<String,PackProgramSource.Prepared> terrain,ShadowPackPlan shadow,DistantPackPlan distant,SkyPackPlan sky,ParticlePackPlan particles,EntityPackPlan entities,PackTextures textures) throws IOException {
        var defines=pack.settings().globalDefines(pack.options());
        var bindings=new LinkedHashMap<String,Asset>();var decoded=new HashMap<String,Asset>();
        var programs=new LinkedHashSet<PackProgramSource.Prepared>();
        terrain.values().forEach(programs::add);
        if(distant!=null)distant.programs().forEach(programs::add);
        sky.programs().values().forEach(programs::add);
        particles.programs().values().forEach(programs::add);
        if(entities!=null)entities.programs().forEach(programs::add);
        if(shadow!=null) {
            programs.addAll(shadow.programs().values());
            if(shadow.entities()!=null)programs.add(shadow.entities());
            if(shadow.blocks()!=null)programs.add(shadow.blocks());
        }
        chain.stages().values().forEach(passes->passes.forEach(p->{
            if(p.draws())programs.add(p.program());
            p.computes().forEach(c->programs.add(c.program().asProgram()));
        }));
        chain.setup().forEach(c->programs.add(c.program().asProgram()));
        chain.shadowComputes().forEach(c->programs.add(c.program().asProgram()));
        for(var source:programs) {
            String name=source.path().substring(source.path().lastIndexOf('/')+1);
            var stage=TextureStage.of(name).orElse(TextureStage.GBUFFERS);
            var active=new HashSet<Integer>();source.compiled().stages().values().forEach(s->active.addAll(SpirvInterface.bindings(s)));
            for(var sampler:source.translated().samplers()) {
                if(!active.contains(source.compiled().bindings().get(sampler.name())))continue;
                String behind=dev.vitrail.pack.target.SamplerPlan.behind(sampler.name());
                if(!behind.equals(sampler.name())) {
                    // The translation moved a sampler3D the pack ships onto a flat atlas under this name.
                    Asset volume=decoded.get("<volume>"+behind);
                    if(volume==null){volume=volume(pack,textures,behind);decoded.put("<volume>"+behind,volume);}
                    bindings.put(key(name,sampler.name()),volume);
                    continue;
                }
                var supplied=textures.resolve(stage,sampler.name());
                if(supplied.isPresent()) {
                    var texture=supplied.get();String id=texture.path()+":"+texture.blur()+":"+texture.clamp();
                    Asset asset=decoded.get(id);
                    if(asset==null){asset=decode(pack,texture);decoded.put(id,asset);}
                    bindings.put(key(name,sampler.name()),asset);
                } else if (sampler.type().equals("sampler2D") && hasMaterialDefault(sampler.name())) {
                    bindings.put(key(name, sampler.name()), MATERIAL_DEFAULTS.get(sampler.name()));
                } else if(sampler.name().equals("noisetex")) {
                    Asset noise=decoded.get("<noise>");
                    if(noise==null) {
                        var path=pack.properties().noiseTexturePath(defines);
                        if(path.isPresent()) {
                            // Iris reads the .mcmeta beside a custom noise texture (BSL: blur=true).
                            boolean[] filtering=mcmeta(pack,path.get());
                            noise=png(pack,path.get(),filtering[0],filtering[1]);
                        } else noise=noise(chainNoiseSize(pack,chain));
                        decoded.put("<noise>",noise);
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
    /** Same first declaration PackTextures.volumes() lays out, so the atlas matches the translated lookups. */
    private static Asset volume(OpenedPack pack,PackTextures textures,String sampler) throws IOException {
        for(var texture:textures.supplied()) {
            if(!texture.sampler().equals(sampler))continue;
            var raw=texture.raw().filter(dev.vitrail.pack.texture.VolumeAtlas::serves);
            if(raw.isEmpty())continue;
            var atlas=dev.vitrail.pack.texture.VolumeAtlas.of(raw.get(),texture.clamp());
            if(dev.vitrail.glsl.GlslTranslator.nativeVolumes())return nativeVolume(pack,texture,raw.get());
            if(!atlas.fits())throw new IOException("Pack volume atlas is too large: "+texture.describe());
            byte[] blob=blob(pack,texture,raw.get());
            return new Texels(atlas.atlasWidth(),atlas.atlasHeight(),fourChannels(raw.get().pixelType()),atlas.spread(blob),texture.blur(),true);
        }
        throw new IOException("No pack volume behind translated sampler "+sampler);
    }
    /**
     * The blob as it ships, one, two or four channels a texel; three are widened to four with a unit
     * alpha (few devices sample a three-channel format). Sampling a short format reads nought for a
     * missing colour and one for a missing alpha in Vulkan as in GL.
     */
    private static Asset nativeVolume(OpenedPack pack,PackTexture texture,PackTexture.Raw raw) throws IOException {
        int components=raw.pixelFormat().components();
        int channel=channelBytes(raw.pixelType());
        if(channel<0||components<1||components>4)throw new IOException("Native volume format not implemented: "+texture.describe());
        long texels=(long)raw.sizeX()*raw.sizeY()*raw.sizeZ();
        var limits=volumeLimit();
        if(raw.sizeX()>limits||raw.sizeY()>limits||raw.sizeZ()>limits)throw new IOException("Pack volume exceeds device limits: "+texture.describe());
        byte[] blob=blob(pack,texture,raw);
        int stored=components==3?4:components;
        if(texels*stored*channel>256L*1024*1024)throw new IOException("Pack volume exceeds 256 MiB: "+texture.describe());
        byte[] data;
        if(stored==components) data=java.util.Arrays.copyOf(blob,Math.toIntExact(texels*components*channel));
        else {
            data=new byte[Math.toIntExact(texels*4*channel)];
            byte[] one=one(raw.pixelType());
            for(int t=0;t<texels;t++) {
                System.arraycopy(blob,t*3*channel,data,t*4*channel,3*channel);
                System.arraycopy(one,0,data,t*4*channel+3*channel,channel);
            }
        }
        return new Volume(raw.sizeX(),raw.sizeY(),raw.sizeZ(),volumeFormat(raw.pixelType(),stored),stored*channel,data,texture.blur(),texture.clamp());
    }
    private static int volumeLimit() {
        var properties=net.vulkanmod.vulkan.device.DeviceManager.deviceProperties;
        return properties==null?2048:properties.limits().maxImageDimension3D();
    }
    static int volumeFormat(dev.vitrail.pack.model.PixelType type,int channels) throws IOException {
        if(channels==4)return fourChannels(type);
        boolean two=channels==2;
        return switch(type) {
            case UNSIGNED_BYTE->two?org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UNORM:org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
            case BYTE->two?org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_SNORM:org.lwjgl.vulkan.VK10.VK_FORMAT_R8_SNORM;
            case UNSIGNED_SHORT->two?org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_UNORM:org.lwjgl.vulkan.VK10.VK_FORMAT_R16_UNORM;
            case SHORT->two?org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SNORM:org.lwjgl.vulkan.VK10.VK_FORMAT_R16_SNORM;
            case HALF_FLOAT->two?org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SFLOAT:org.lwjgl.vulkan.VK10.VK_FORMAT_R16_SFLOAT;
            case FLOAT->two?org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT:org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
            default->throw new IOException("Native raw pixel type "+type+" not implemented");
        };
    }
    private static byte[] blob(OpenedPack pack,PackTexture texture,PackTexture.Raw raw) throws IOException {
        String relative=texture.path().startsWith("/")?texture.path().substring(1):texture.path();
        var file=pack.source().file(relative).orElseThrow(()->new IOException("Missing pack data texture: "+texture.path()));
        if(raw.bytes()>256L*1024*1024)throw new IOException("Pack data texture exceeds 256 MiB: "+texture.describe());
        byte[] blob=Files.readAllBytes(file);
        if(blob.length<raw.bytes())throw new IOException("Pack data texture holds "+blob.length+" bytes, "+raw.bytes()+" declared: "+texture.describe());
        return blob;
    }
    /** A 2D blob widened to four channels as GL reads a short format: missing colour 0, missing alpha 1. */
    private static Asset raw2d(OpenedPack pack,PackTexture texture,PackTexture.Raw raw) throws IOException {
        if(raw.shape()!=PackTexture.Shape.TEXTURE_2D)throw new IOException("Native "+raw.shape()+" pack texture not implemented: "+texture.describe());
        var format=raw.pixelFormat();
        int components=switch(format){case RED->1;case RG->2;case RGB->3;case RGBA->4;
            default->throw new IOException("Native raw pixel format "+format+" not implemented: "+texture.describe());};
        int channel=channelBytes(raw.pixelType());
        if(channel<0)throw new IOException("Native raw pixel type "+raw.pixelType()+" not implemented: "+texture.describe());
        byte[] blob=blob(pack,texture,raw);
        int width=raw.sizeX(),height=Math.max(1,raw.sizeY());
        byte[] one=one(raw.pixelType());
        byte[] out=new byte[Math.multiplyExact(Math.multiplyExact(width,height),4*channel)];
        for(int texel=0;texel<width*height;texel++) {
            System.arraycopy(blob,texel*components*channel,out,texel*4*channel,components*channel);
            if(components<4)System.arraycopy(one,0,out,texel*4*channel+3*channel,channel);
        }
        return new Texels(width,height,fourChannels(raw.pixelType()),out,texture.blur(),texture.clamp());
    }
    private static int channelBytes(dev.vitrail.pack.model.PixelType type) {
        return switch(type){case UNSIGNED_BYTE,BYTE->1;case UNSIGNED_SHORT,SHORT,HALF_FLOAT->2;case FLOAT->4;default->-1;};
    }
    private static byte[] one(dev.vitrail.pack.model.PixelType type) {
        return switch(type) {
            case UNSIGNED_BYTE->new byte[]{(byte)0xff};case BYTE->new byte[]{0x7f};
            case UNSIGNED_SHORT->new byte[]{(byte)0xff,(byte)0xff};case SHORT->new byte[]{(byte)0xff,0x7f};
            case HALF_FLOAT->new byte[]{0x00,0x3c};case FLOAT->new byte[]{0x00,0x00,(byte)0x80,0x3f};
            default->throw new IllegalArgumentException("No unit value for "+type);
        };
    }
    private static int fourChannels(dev.vitrail.pack.model.PixelType type) throws IOException {
        return switch(type) {
            case UNSIGNED_BYTE->org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
            case BYTE->org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SNORM;
            case UNSIGNED_SHORT->org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UNORM;
            case SHORT->org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SNORM;
            case HALF_FLOAT->org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            case FLOAT->org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
            default->throw new IOException("Native raw pixel type "+type+" not implemented");
        };
    }
    static int texelBytes(int vkFormat) {
        return switch(vkFormat) {
            case org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM,org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SNORM->4;
            case org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UNORM,org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SNORM,
                 org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT->8;
            case org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT->16;
            default->throw new IllegalArgumentException("Unsupported raw texel format "+vkFormat);
        };
    }
    private static Asset decode(OpenedPack pack,PackTexture texture) throws IOException {
        if(texture.raw().isPresent())return raw2d(pack,texture,texture.raw().get());
        if(texture.gameResource())return new GameImage(texture.path());
        return png(pack,texture.path(),texture.blur(),texture.clamp());
    }
    /** blur and clamp from {@code <file>.mcmeta}, false where absent, as Minecraft's texture metadata. */
    private static boolean[] mcmeta(OpenedPack pack,String path) throws IOException {
        String relative=(path.startsWith("/")?path.substring(1):path)+".mcmeta";
        var file=pack.source().file(relative);
        if(file.isEmpty())return new boolean[]{false,false};
        String text=Files.readString(file.get());
        return new boolean[]{text.matches("(?s).*\"blur\"\\s*:\\s*true.*"),text.matches("(?s).*\"clamp\"\\s*:\\s*true.*")};
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
        // Iris's generated noise texture filters linearly and repeats (targets/backed/NoiseTexture):
        // packs such as Bliss add the fractional part to the coordinate and rely on the interpolation.
        return new Pixels(size,size,bytes,true,false);
    }
}

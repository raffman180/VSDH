package net.vulkanmod.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

public class CustomVertexFormat {

    public static final VertexFormatElement ELEMENT_POSITION = new VertexFormatElement(0, 0,VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.POSITION, 4);
    public static final VertexFormatElement ELEMENT_COLOR = new VertexFormatElement(1, 0, VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.COLOR, 4);
    public static final VertexFormatElement ELEMENT_UV0 = new VertexFormatElement(2, 0, VertexFormatElement.Type.USHORT, VertexFormatElement.Usage.UV, 2);
    public static final VertexFormatElement ELEMENT_UV2 = new VertexFormatElement(3, 2, VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.UV, 2);
    public static final VertexFormatElement ELEMENT_EXTERNAL_LOD_POSITION = new VertexFormatElement(0, 0, VertexFormatElement.Type.USHORT, VertexFormatElement.Usage.POSITION, 4);
    public static final VertexFormatElement ELEMENT_EXTERNAL_LOD_PADDING = new VertexFormatElement(2, 0, VertexFormatElement.Type.BYTE, VertexFormatElement.Usage.NORMAL, 4);

    public static final VertexFormat COMPRESSED_TERRAIN = VertexFormat.builder()
            .add("Position", ELEMENT_POSITION)
            .add("Color", ELEMENT_COLOR)
            .add("UV0", ELEMENT_UV0)
            .add("UV2", ELEMENT_UV2)
            .build();

    /** Explicitly matched by dev.vitrail.glsl.VulkanTerrainVertex (44 bytes). */
    public static final VertexFormat SHADER_TERRAIN = VertexFormat.builder()
            .add("vs_Position", ELEMENT_POSITION)
            .add("vs_Color", ELEMENT_COLOR)
            .add("vs_UV0", ELEMENT_UV0)
            .add("vs_Normal", integer(3))
            .add("vs_Tangent", integer(4))
            .add("vs_Material", integer(5))
            .add("vs_Fluid", integer(6))
            .add("vs_MidUV", new VertexFormatElement(7, 3, VertexFormatElement.Type.USHORT, VertexFormatElement.Usage.UV, 2))
            .add("vs_MidBlock", integer(8))
            .add("vs_TintAO", new VertexFormatElement(9, 0, VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.COLOR, 4))
            .build();

    private static VertexFormatElement integer(int id) {
        return new VertexFormatElement(id, 0, VertexFormatElement.Type.INT, VertexFormatElement.Usage.GENERIC, 1);
    }

    public static boolean compressedPosition(VertexFormat format) {
        return format == COMPRESSED_TERRAIN || format == SHADER_TERRAIN;
    }

    public static final VertexFormat EXTERNAL_LOD = VertexFormat.builder()
            .add("Position", ELEMENT_EXTERNAL_LOD_POSITION)
            .add("Color", ELEMENT_COLOR)
            .add("Padding", ELEMENT_EXTERNAL_LOD_PADDING)
            .build();

    public static final VertexFormat NONE = VertexFormat.builder().build();
}

package com.braffolk.dhvulkan.api;

import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class DhRegistrationTest {
    @Test void dhCanRegisterAndRemoveItsCloudGroupBeforeTerrainRenderingStarts() {
        // Regression: rejecting unsupported geometry at registration time broke
        // DH world construction, before the disabled render pass was even used.
        IDhApiRenderableBoxGroup group = (IDhApiRenderableBoxGroup) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDhApiRenderableBoxGroup.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getId")) return 41L;
                    throw new UnsupportedOperationException(method.getName());
                });
        var backend = (com.braffolk.dhvulkan.core.VulkanBackend) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.braffolk.dhvulkan.core.VulkanBackend.class},
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        var renderer = new VkRenderApiDefinition.VkGenericRenderer(backend);
        renderer.add(group);
        assertSame(group, renderer.remove(41L));
        renderer.add(group);
        renderer.close();
        assertNull(renderer.remove(41L));
    }

    @Test void genericBoxesBecomeTwelveLodQuadsInDhsVertexLayout() {
        var container = new VkGenericObjectVboContainer();
        var box = new com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox(
                new com.seibel.distanthorizons.api.objects.math.DhApiVec3d(-10, 200, 30),
                new com.seibel.distanthorizons.api.objects.math.DhApiVec3d(118, 232, 158),
                new java.awt.Color(250, 240, 230, 255),
                com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial.UNKNOWN);
        container.updateVertexData(java.util.List.of(box));
        var segments = container.pendingSegments();
        assertEquals(1, segments.size());
        var segment = segments.get(0);
        assertEquals(12, segment.quads());
        assertEquals(-10, segment.x()); assertEquals(200, segment.y()); assertEquals(30, segment.z());
        var data = segment.plain();
        assertEquals(12 * 4 * VkGenericObjectVboContainer.VERTEX_BYTES, data.remaining());
        int maxX = 0;
        for (int v = 0; v < 48; v++) {
            int at = v * 16;
            maxX = Math.max(maxX, Short.toUnsignedInt(data.getShort(at)));
            assertEquals(0xFF, Short.toUnsignedInt(data.getShort(at + 6)), "sky 15 | block 15 << 4");
            assertEquals(250, Byte.toUnsignedInt(data.get(at + 8)));
            int face = data.getInt(at + 12) >> 8 & 255;
            assertEquals(v / 8, face, "faces in DH order, each written in both windings");
        }
        assertEquals(128, maxX);
        // The second winding is the first one reversed.
        assertEquals(data.getShort(0), data.getShort(7 * 16));
        assertEquals(data.getShort(2), data.getShort(7 * 16 + 2));
        container.close();
    }
}

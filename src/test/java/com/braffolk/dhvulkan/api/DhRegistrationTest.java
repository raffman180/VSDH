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
        var renderer = new VkRenderApiDefinition.VkGenericRenderer();
        renderer.add(group);
        assertSame(group, renderer.remove(41L));
        renderer.add(group);
        renderer.close();
        assertNull(renderer.remove(41L));
    }
}

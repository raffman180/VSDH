package com.braffolk.dhvulkan.api;

import com.braffolk.dhvulkan.core.VulkanBackend;
import com.seibel.distanthorizons.core.render.EDhDepthRange;
import com.seibel.distanthorizons.core.render.EDhRenderDepth;

/** DH 3.3.0 and 3.3.1 used internal types for the Vulkan depth convention. */
public final class LegacyVkRenderApiDefinition extends VkRenderApiDefinition {
    public LegacyVkRenderApiDefinition(VulkanBackend backend) {
        super(backend);
    }

    @Override
    public EDhRenderDepth getRenderDepth() {
        return EDhRenderDepth.FORWARD_Z;
    }

    @Override
    public EDhDepthRange getDepthRange() {
        return EDhDepthRange.NEG_ONE_TO_POS_ONE;
    }
}

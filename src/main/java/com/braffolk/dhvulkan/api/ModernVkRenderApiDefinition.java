package com.braffolk.dhvulkan.api;

import com.braffolk.dhvulkan.core.VulkanBackend;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;

final class ModernVkRenderApiDefinition extends VkRenderApiDefinition {
    ModernVkRenderApiDefinition(VulkanBackend backend) {
        super(backend);
    }

    @Override
    public EDhApiDepthDirection getDepthDirection() {
        return EDhApiDepthDirection.FORWARD_Z;
    }

    @Override
    public EDhApiDepthRange getDepthRange() {
        return EDhApiDepthRange.NEG_ONE_TO_POS_ONE;
    }
}

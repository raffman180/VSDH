package com.braffolk.dhvulkan.api;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;

/** Per-container transforms are uploaded by VulkanRenderContext immediately before drawing. */
public final class VkLodContainerUniformWrapper implements ILodContainerUniformBufferWrapper {
    @Override public void tryUpload(LodBufferContainer container) { /* no independent GPU allocation */ }
    @Override public void close() { /* VulkanRenderContext owns the uniform storage */ }
}

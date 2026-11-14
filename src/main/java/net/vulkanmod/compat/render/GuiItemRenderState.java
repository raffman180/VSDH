package net.vulkanmod.compat.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.opengl.GL11;

public final class GuiItemRenderState {
    // Render thread only. Plain fields: these are read on every buffer flush, and a ThreadLocal that is
    // set and removed per draw allocates a map entry each time.
    private static RenderStateSnapshot deferredDrawState;
    private static int tooltipOverlayDepth;

    private GuiItemRenderState() {
    }

    public static void prepareDeferredDraw() {
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }

        if (deferredDrawState == null) {
            deferredDrawState = new RenderStateSnapshot();
        }

        prepare();
    }

    public static boolean hasDeferredDrawState() {
        return deferredDrawState != null;
    }

    public static void beginTooltipOverlay() {
        tooltipOverlayDepth++;
    }

    public static void endTooltipOverlay() {
        if (tooltipOverlayDepth > 0) {
            tooltipOverlayDepth--;
        }
    }

    public static boolean isTooltipOverlayActive() {
        return tooltipOverlayDepth > 0;
    }

    public static void restoreDeferredDrawState() {
        RenderStateSnapshot snapshot = deferredDrawState;
        deferredDrawState = null;

        if (snapshot != null) {
            snapshot.restore();
        }
    }

    /** Drops a pending boundary without restoring it: for callers that restore an enclosing snapshot themselves. */
    public static void discardDeferredDrawState() {
        deferredDrawState = null;
    }

    /** Frame boundary: nothing of the last frame may stay pending. */
    public static void resetFrame() {
        deferredDrawState = null;
        tooltipOverlayDepth = 0;
    }

    public static void prepare() {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.colorMask(true, true, true, true);

        if (isTooltipOverlayActive()) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
        } else {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
        }

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);
    }
}

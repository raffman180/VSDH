package net.vulkanmod.compat.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.opengl.GL11;

public final class GuiEntityRenderState {
    public static final int FULL_BRIGHT_LIGHT = 15728880;
    // Render thread only; see GuiItemRenderState for why these are plain fields.
    private static RenderStateSnapshot deferredDrawState;
    private static int hudEntityPreviewDepth;
    private static int worldPassDepth;

    private GuiEntityRenderState() {
    }

    /**
     * An entity drawn for the GUI: by a screen (inventory player model, a mod's preview) or inside a HUD preview
     * scope. Entities of the world itself are never previews, also while a screen is open above the world: the
     * pause menu must not put every model part of the world pass into the GUI entity state.
     */
    public static boolean isGuiEntityPreview() {
        return isHudEntityPreviewActive() || (worldPassDepth == 0 && Minecraft.getInstance().screen != null);
    }

    public static boolean isGuiEntityPreview(int packedLight) {
        return isGuiEntityPreview() && packedLight == 15728880;
    }

    /** GameRenderer.renderLevel is running (world, entities, hand); counted, since portal/mirror mods nest it. */
    /** True while GameRenderer.renderLevel runs (also nested). */
    public static boolean inWorldPass() {
        return worldPassDepth > 0;
    }

    public static void beginWorldPass() {
        worldPassDepth++;
    }

    public static void endWorldPass() {
        if (worldPassDepth > 0) {
            worldPassDepth--;
        }
    }

    public static void beginHudEntityPreview() {
        hudEntityPreviewDepth++;
    }

    public static void endHudEntityPreview() {
        if (hudEntityPreviewDepth > 0) {
            hudEntityPreviewDepth--;
        }
    }

    public static boolean isHudEntityPreviewActive() {
        return hudEntityPreviewDepth > 0;
    }

    public static void prepare() {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        RenderSystem.disableCull();
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);
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

    /** Frame boundary: nothing of the last frame may stay pending or keep the world-pass flag set. */
    public static void resetFrame() {
        deferredDrawState = null;
        hudEntityPreviewDepth = 0;
        worldPassDepth = 0;
    }
}

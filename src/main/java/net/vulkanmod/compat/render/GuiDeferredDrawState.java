package net.vulkanmod.compat.render;

/**
 * The GUI item / GUI entity state boundary as one unit. It opens when such geometry is buffered and closes when
 * the buffer is flushed. The close used to hang on vanilla's {@code BufferSource.endBatch} alone; a mod that
 * replaces the buffer source with a subclass overriding those methods (ImmediatelyFast) never reached it, so
 * the prepared state leaked and the first snapshot stayed pending forever. {@code GuiGraphics.flush} is the
 * point every buffer source passes through, and the frame start drops whatever is left. Render thread only.
 */
public final class GuiDeferredDrawState {
    private GuiDeferredDrawState() {
    }

    public static void prepareIfPending() {
        if (GuiEntityRenderState.hasDeferredDrawState()) {
            GuiEntityRenderState.prepareDeferredDraw();
        }

        if (GuiItemRenderState.hasDeferredDrawState()) {
            GuiItemRenderState.prepareDeferredDraw();
        }
    }

    /** Restores and clears; a second call is a no-op. */
    public static void restore() {
        GuiItemRenderState.restoreDeferredDrawState();
        GuiEntityRenderState.restoreDeferredDrawState();
    }

    /** No restore: the saved state belongs to a frame that is over. */
    public static void resetFrame() {
        GuiItemRenderState.resetFrame();
        GuiEntityRenderState.resetFrame();
    }
}

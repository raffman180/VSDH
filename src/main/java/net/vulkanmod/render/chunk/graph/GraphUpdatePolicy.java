package net.vulkanmod.render.chunk.graph;

/**
 * When the visible-section set has to be walked again because the camera changed.
 * <p>
 * The set is culled against the frustum of the frame that last walked it. The renderer used to walk again only after
 * the camera had turned more than 2 degrees on one axis or moved half a block, with a frustum that has no margin
 * (VFrustum.offsetToFullyIncludeCameraCube stops as soon as the camera cube intersects). Between two walks every
 * section that lay wholly outside the old frustum stayed undrawn although the turned camera already saw it: at 1440p
 * and FOV 70 a strip of up to about 90 pixels at the leading screen edge showed sky or Distant Horizons' LODs and was
 * filled in steps of 2 degrees ("it drags behind when I turn", 15.11.2026); block entities are tested against the
 * same stale frustum. Sodium walks again on any change of position, pitch, yaw or projection, vanilla applies the
 * frustum of every frame; so does this policy by default.
 * <p>
 * {@code -Dvsdh.exactCullUpdates=false} restores the thresholds.
 */
public final class GraphUpdatePolicy {
    private GraphUpdatePolicy() {
    }

    public static final boolean EXACT = !"false".equalsIgnoreCase(System.getProperty("vsdh.exactCullUpdates"));

    /** The old thresholds: more than this many degrees on one axis, or at least this distance squared in blocks. */
    public static final float ROTATION_THRESHOLD_DEGREES = 2.0f;
    public static final float MOVEMENT_THRESHOLD_SQUARED = 0.25f;

    /**
     * @param exact               walk on any change (the default) instead of the thresholds
     * @param pitchDelta          absolute change of the camera pitch since the last walk, degrees
     * @param yawDelta            absolute change of the camera yaw since the last walk, degrees
     * @param distanceSquared     squared camera movement since the last walk, blocks
     * @param cameraSectionChanged the camera entered another section
     * @param frustumChanged      the culling frustum's matrix differs from the one of the last walk (rotation or
     *                            field of view); only looked at in exact mode
     */
    public static boolean cameraChanged(boolean exact, float pitchDelta, float yawDelta, float distanceSquared,
                                        boolean cameraSectionChanged, boolean frustumChanged) {
        if (cameraSectionChanged) return true;
        if (exact) return frustumChanged || pitchDelta != 0.0f || yawDelta != 0.0f || distanceSquared != 0.0f;
        return pitchDelta > ROTATION_THRESHOLD_DEGREES || yawDelta > ROTATION_THRESHOLD_DEGREES
                || distanceSquared >= MOVEMENT_THRESHOLD_SQUARED;
    }
}

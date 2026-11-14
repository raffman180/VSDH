/* VSDH addition to the VulkanMod Reforged chunk renderer; LGPL-3.0-only. See NOTICE. */
package net.vulkanmod.render.optimization;

/**
 * While many sections the camera sees were never built (world join, shader pack switch, dimension change), build and
 * upload more per frame so the near ground is complete sooner. With the normal budget (8 schedules, 5 uploads per frame
 * at the player's settings) a pack switch left the view without its near ground for 3-4 s and Distant Horizons' far
 * water showed through. The visible frames of that window may take longer; the steady state is unchanged.
 * {@code -Dvsdh.chunkCatchUp=false} turns it off. Render thread only.
 */
public final class ChunkCatchUp {
    private ChunkCatchUp() {}

    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("vsdh.chunkCatchUp"));
    /** Visible never-built sections from which the larger budget applies. */
    public static final int THRESHOLD = 24;
    public static final int UPLOADS_PER_FRAME = 32;
    public static final int REBUILD_SCHEDULES_PER_FRAME = 32;
    public static final long UPLOAD_TIME_BUDGET_NANOS = 5_000_000L;
    public static final long UPLOAD_BYTE_BUDGET = 16L * 1024 * 1024;
    public static final long REBUILD_TIME_BUDGET_NANOS = 3_000_000L;

    /** Short gaps happen all the time while walking; only a gap a player notices is worth a log line. */
    private static final long LOGGED_GAP_NANOS = 1_000_000_000L;
    private static final boolean LOG_EVERY_GAP = Boolean.getBoolean("vsdh.chunkDiag");
    private static boolean active, missing;
    private static long missingSince;

    /** Called after each visibility update with the number of visible sections that were never built. */
    public static void observe(int visibleUncompiled) {
        active = decide(ENABLED, visibleUncompiled);
        boolean nowMissing = visibleUncompiled >= THRESHOLD;
        if (nowMissing != missing) {
            // One line per long gap (join, pack switch): how long the view lacked built sections.
            long now = System.nanoTime();
            if (nowMissing) missingSince = now;
            else if (now - missingSince >= LOGGED_GAP_NANOS || LOG_EVERY_GAP)
                org.slf4j.LoggerFactory.getLogger("VulkanMod").info("Visible sections complete after {} ms (catch-up {})",
                        (now - missingSince) / 1_000_000, ENABLED ? "on" : "off");
            missing = nowMissing;
        }
    }

    static boolean decide(boolean enabled, int visibleUncompiled) { return enabled && visibleUncompiled >= THRESHOLD; }

    public static boolean active() { return active; }

    public static void reset() { active = false; missing = false; }
}

/* VSDH addition to the VulkanMod Reforged chunk renderer; LGPL-3.0-only. See NOTICE. */
package net.vulkanmod.render.optimization;

/**
 * Builds sections that only the shadow light sees. Sections are compiled once the camera's visibility walk reaches
 * them, so a hill, tree or roof behind or beside the player cast no shadow into the view until the player had looked
 * at it (or the one-per-frame near prebuild got there): after a world join, a pack switch or a turn the shadows
 * arrived seconds late. While a pack draws a shadow map, sections inside its caster volume that wait for a build are
 * therefore started too, after the camera's own ones: nothing while a visible section still waits, only with what is
 * left of the frame's schedule time, and never into the last {@link #QUEUE_RESERVE} worker slots.
 * Opt-in: {@code -Dvsdh.shadowCasterBuilds=true} turns it on, {@code -Dvsdh.shadowCasterBuildsPerFrame=N} changes the count.
 * Render thread only.
 */
public final class ShadowCasterBuilds {
    private ShadowCasterBuilds() {}

    /**
     * Off by default since 15.11.2026 (evening): never measured in game, and the same day brought an FPS and hitch
     * report. Its costs per frame while on: the scan below (never-built air sections stay dirty for ever, so each of
     * them runs the caster test, two set lookups and a chunk lookup on every pass, not only "flag reads"), up to
     * {@link #PER_FRAME} region copies on the render thread, and every section it builds is drawn into the shadow
     * map from then on although the camera never saw it. {@code -Dvsdh.shadowCasterBuilds=true} turns it on.
     */
    public static final boolean ENABLED = "true".equalsIgnoreCase(System.getProperty("vsdh.shadowCasterBuilds"));
    /** Starts per frame. Each start of a non-empty section copies its region on the render thread (about 0.2 ms). */
    public static final int PER_FRAME = Math.clamp(Integer.getInteger("vsdh.shadowCasterBuildsPerFrame", 4), 1, 16);
    /** Grid sections looked at per frame; a 33 x 24 x 33 grid is covered in 13 frames. */
    public static final int SCAN = 2048;
    /** Worker queue slots left to the camera's sections. */
    public static final int QUEUE_RESERVE = ChunkSchedulingPolicy.MAX_QUEUED_BUILD_TASKS / 2;
    /** The frame's whole schedule time, shared with the camera's sections (the normal budget, see ChunkCatchUp). */
    public static final long TIME_BUDGET_NANOS = ChunkCatchUp.REBUILD_TIME_BUDGET_NANOS;

    /**
     * Sections the shadow pass may start this frame.
     *
     * @param cameraReady visible sections still waiting after the camera's scheduling
     * @param cameraScheduleNanos time the camera's scheduling took this frame
     * @param queuedTasks build tasks waiting for a worker
     */
    public static int budget(boolean enabled, int perFrame, int cameraReady, long cameraScheduleNanos, int queuedTasks) {
        if (!enabled || cameraReady > 0 || timeLeft(cameraScheduleNanos) <= 0) return 0;
        int free = ChunkSchedulingPolicy.MAX_QUEUED_BUILD_TASKS - QUEUE_RESERVE - queuedTasks;
        return Math.max(0, Math.min(perFrame, free));
    }

    public static long timeLeft(long cameraScheduleNanos) {
        return TIME_BUDGET_NANOS - Math.max(0, cameraScheduleNanos);
    }

    /** Whether a section box (camera-relative minimum corner, 16 wide) lies wholly outside the cube of half extent reach. */
    public static boolean outOfReach(float reach, float minX, float minY, float minZ) {
        return minX + 16 < -reach || minX > reach || minY + 16 < -reach || minY > reach || minZ + 16 < -reach || minZ > reach;
    }

    /** The nearest few candidates of a scan, nearest first; equal distances keep their scan order. No allocation after construction. */
    public static final class Picks<T> {
        private final Object[] items;
        private final double[] distances;
        private int size;

        public Picks(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("capacity");
            this.items = new Object[capacity];
            this.distances = new double[capacity];
        }

        public void offer(T item, double distance) {
            int at;
            if (this.size < this.items.length) at = this.size++;
            else if (distance >= this.distances[this.size - 1]) return;
            else at = this.size - 1;
            while (at > 0 && this.distances[at - 1] > distance) {
                this.items[at] = this.items[at - 1];
                this.distances[at] = this.distances[at - 1];
                at--;
            }
            this.items[at] = item;
            this.distances[at] = distance;
        }

        public int size() { return this.size; }

        @SuppressWarnings("unchecked")
        public T get(int index) {
            if (index < 0 || index >= this.size) throw new IndexOutOfBoundsException(index);
            return (T) this.items[index];
        }

        /** Empties the list and drops its references. */
        public void clear() {
            java.util.Arrays.fill(this.items, 0, this.size, null);
            this.size = 0;
        }
    }
}

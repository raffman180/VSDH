/* VSDH addition to the VulkanMod Reforged chunk renderer; LGPL-3.0-only. See NOTICE. */
package net.vulkanmod.render.chunk;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The sections a light pass can draw at all: non-empty and attached to an area, grouped by area.
 * <p>
 * The shadow pass used to walk every section of the grid each frame (ten thousand and more) to find them.
 * The answer only changes when a section is compiled, emptied or moved, so it is kept until one of those
 * happens ({@link #changed()}) and walked as flat arrays, with the section origins beside it so a rejected
 * section is never touched. Areas keep the order of their first section in the grid and sections their
 * grid order within the area: the queues come out exactly as the full walk filled them.
 * Render thread only, apart from {@link #changed()}.
 */
final class ShadowSectionList {
    private static final AtomicInteger EPOCH = new AtomicInteger();

    /** A section became empty or non-empty, or changed its place or area. Any thread. */
    static void changed() { EPOCH.incrementAndGet(); }

    RenderSection[] sections = new RenderSection[0];
    int[] x = new int[0], y = new int[0], z = new int[0];
    /** Run {@code r} covers {@code [runStart[r], runStart[r + 1])} and belongs to {@code runArea[r]}. */
    ChunkArea[] runArea = new ChunkArea[0];
    int[] runStart = new int[1];
    int runs, size;

    private SectionGrid builtGrid;
    private int builtEpoch;
    private int[] slotOfArea = new int[0], counts = new int[0];
    long rebuilds;

    void refresh(SectionGrid grid) {
        // Read before the walk: a change that lands during it is seen as a new epoch next frame.
        int epoch = EPOCH.get();
        if (grid == builtGrid && epoch == builtEpoch) return;
        builtGrid = grid;
        builtEpoch = epoch;
        rebuilds++;
        int areas = grid.chunkAreaManager.size;
        if (slotOfArea.length < areas) { slotOfArea = new int[areas]; counts = new int[areas + 1]; runArea = new ChunkArea[areas]; runStart = new int[areas + 1]; }
        Arrays.fill(slotOfArea, 0, areas, -1);
        Arrays.fill(runArea, null);
        int found = 0;
        runs = 0;
        // First walk: one run per area in order of its first drawable section, and the run sizes.
        for (RenderSection section : grid.sections) {
            var area = section.getChunkArea();
            if (area == null || section.isCompletelyEmpty()) continue;
            int slot = slotOfArea[area.index];
            if (slot < 0) { slot = runs++; slotOfArea[area.index] = slot; runArea[slot] = area; counts[slot] = 0; }
            counts[slot]++;
            found++;
        }
        if (sections.length < found) {
            int capacity = Math.max(found, sections.length * 2);
            sections = new RenderSection[capacity];
            x = new int[capacity]; y = new int[capacity]; z = new int[capacity];
        } else Arrays.fill(sections, found, Math.max(found, size), null);
        int start = 0;
        for (int r = 0; r < runs; r++) { runStart[r] = start; start += counts[r]; counts[r] = runStart[r]; }
        runStart[runs] = start;
        // Second walk: fill each run in grid order. A section that changed since the first walk is skipped
        // or left out; the epoch has moved then and the next frame builds the list again.
        int filled = 0;
        for (RenderSection section : grid.sections) {
            var area = section.getChunkArea();
            if (area == null || section.isCompletelyEmpty()) continue;
            int slot = slotOfArea[area.index];
            if (slot < 0 || counts[slot] >= runStart[slot + 1]) continue;
            int at = counts[slot]++;
            sections[at] = section;
            x[at] = section.xOffset; y[at] = section.yOffset; z[at] = section.zOffset;
            filled++;
        }
        if (filled != found) {
            // Changed underneath the two walks: compact the holes away rather than draw a null.
            int write = 0;
            for (int r = 0; r < runs; r++) {
                int from = runStart[r], to = counts[r];
                runStart[r] = write;
                for (int i = from; i < to; i++) { sections[write] = sections[i]; x[write] = x[i]; y[write] = y[i]; z[write] = z[i]; write++; }
            }
            runStart[runs] = write;
            Arrays.fill(sections, write, found, null);
            found = write;
        }
        size = found;
    }

    void clear() {
        builtGrid = null;
        Arrays.fill(sections, null);
        Arrays.fill(runArea, null);
        runs = 0; size = 0;
    }
}

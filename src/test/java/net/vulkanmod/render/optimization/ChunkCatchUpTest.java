package net.vulkanmod.render.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkCatchUpTest {
    @Test
    void appliesOnlyFromTheThresholdAndWhenEnabled() {
        assertFalse(ChunkCatchUp.decide(true, ChunkCatchUp.THRESHOLD - 1));
        assertTrue(ChunkCatchUp.decide(true, ChunkCatchUp.THRESHOLD));
        assertFalse(ChunkCatchUp.decide(false, 10_000));
    }

    @Test
    void neverLowersTheNormalBudget() {
        assertTrue(ChunkCatchUp.UPLOADS_PER_FRAME >= 5 && ChunkCatchUp.REBUILD_SCHEDULES_PER_FRAME >= 8);
        // The staging buffer holds 30 MiB per frame and is shared with textures and indirect draws.
        assertTrue(ChunkCatchUp.UPLOAD_BYTE_BUDGET <= 16L * 1024 * 1024);
    }
}

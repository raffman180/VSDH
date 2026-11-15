package net.vulkanmod.render.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSchedulingPolicyTest {
    /** Sodium 0.6.13 (1.21.1), ChunkBuilder.getOptimalThreadCount: clamp(max(p / 3, p - 6), 1, 10). */
    @Test
    void sodiumCountFollowsItsFormula() {
        int[][] expected = {{1, 1}, {2, 1}, {4, 1}, {6, 2}, {8, 2}, {10, 4}, {12, 6}, {16, 10}, {24, 10}, {32, 10}};
        for (int[] pair : expected)
            assertEquals(pair[1], ChunkSchedulingPolicy.sodiumBuilderThreadCount(pair[0]), pair[0] + " processors");
        assertEquals(1, ChunkSchedulingPolicy.sodiumBuilderThreadCount(0));
    }

    @Test
    void automaticCountOnAServerIsNeverAboveSodiums() {
        // processors -> {uncapped (half), capped}
        int[][] expected = {{2, 1, 1}, {4, 2, 1}, {6, 3, 2}, {8, 4, 2}, {10, 5, 4}, {12, 6, 6}, {16, 8, 8}, {32, 8, 8}};
        for (int[] row : expected) {
            assertEquals(row[1], ChunkSchedulingPolicy.chooseBuilderThreadCount(row[0], false, false), row[0] + " uncapped");
            assertEquals(row[2], ChunkSchedulingPolicy.chooseBuilderThreadCount(row[0], false, true), row[0] + " capped");
        }
    }

    @Test
    void theCapNeverRaisesACountAndLeavesSinglePlayerAlone() {
        for (int processors = 1; processors <= 128; processors++) {
            for (boolean integrated : new boolean[] {false, true}) {
                int uncapped = ChunkSchedulingPolicy.chooseBuilderThreadCount(processors, integrated, false);
                int capped = ChunkSchedulingPolicy.chooseBuilderThreadCount(processors, integrated, true);
                assertTrue(capped >= 1 && capped <= uncapped, processors + " processors");
                assertTrue(capped <= ChunkSchedulingPolicy.sodiumBuilderThreadCount(processors), processors + " processors");
                assertTrue(capped <= ChunkSchedulingPolicy.MAX_BUILDER_THREADS);
                // A quarter of the processors is already at or below Sodium's count on every CPU.
                if (integrated) assertEquals(uncapped, capped, processors + " processors with the integrated server");
            }
        }
    }

    @Test
    void aCountSetByHandIsNotCapped() {
        assertEquals(4, ChunkSchedulingPolicy.resolveBuilderThreadCount(4, 4, false));
        assertEquals(ChunkSchedulingPolicy.MAX_BUILDER_THREADS, ChunkSchedulingPolicy.resolveBuilderThreadCount(64, 4, true));
        assertEquals(ChunkSchedulingPolicy.chooseBuilderThreadCount(8, true),
                ChunkSchedulingPolicy.resolveBuilderThreadCount(0, 8, true));
    }
}

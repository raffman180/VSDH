package de.stranger.vsdh.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowBudgetTest {
    private static final long MIB = 1024L * 1024;

    @Test
    void smallCardsKeepTheOldLimit() {
        assertEquals(512 * MIB, NativeWorldShadows.shadowBudgetBytes(2048));
        assertEquals(682 * MIB, NativeWorldShadows.shadowBudgetBytes(4096));
    }

    @Test
    void twelveGigabyteCardFitsAnEightThousandMap() {
        // 8192² with two D32 planes and two 4-byte colour targets: 16 bytes per texel = 1 GiB.
        long jurgensCinematic = 8192L * 8192 * 16;
        assertTrue(NativeWorldShadows.shadowBudgetBytes(12282) >= jurgensCinematic);
    }

    @Test
    void freeMemoryLimitsTheShareButNeverBelowTheOldLimit() {
        // 12 GB card with plenty free: the share (about 2 GiB) applies.
        assertEquals(NativeWorldShadows.shadowBudgetBytes(12282), NativeWorldShadows.shadowBudgetBytes(12282, 8192 * MIB));
        // Only 1 GiB free: three quarters of it.
        assertEquals(768 * MIB, NativeWorldShadows.shadowBudgetBytes(12282, 1024 * MIB));
        // Almost nothing free: the old fixed limit still holds.
        assertEquals(512 * MIB, NativeWorldShadows.shadowBudgetBytes(12282, 100 * MIB));
    }

    @Test
    void largeCardsAreCapped() {
        assertEquals(3072 * MIB, NativeWorldShadows.shadowBudgetBytes(24576));
        assertEquals(3072 * MIB, NativeWorldShadows.shadowBudgetBytes(81920));
    }
}

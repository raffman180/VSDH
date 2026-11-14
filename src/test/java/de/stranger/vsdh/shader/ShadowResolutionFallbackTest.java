package de.stranger.vsdh.shader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowResolutionFallbackTest {
    @Test
    void offersThePacksSmallerValuesLargestFirst() {
        // Jurgens: const int shadowMapResolution = 3072; // [1024 1536 2048 3072 4096 6144 8192]
        var listed = List.of("1024", "1536", "2048", "3072", "4096", "6144", "8192");
        assertEquals(List.of(6144, 4096, 3072, 2048, 1536, 1024), ShadowResolutionFallback.smallerResolutions(listed, 8192));
    }

    @Test
    void ignoresValuesThatAreNoResolutionAndDuplicates() {
        var listed = List.of("512.0", "abc", "512", "-1", "2048");
        assertEquals(List.of(512), ShadowResolutionFallback.smallerResolutions(listed, 1024));
    }

    @Test
    void nothingBelowTheSmallestValue() {
        assertEquals(List.of(), ShadowResolutionFallback.smallerResolutions(List.of("1024", "2048"), 1024));
        assertEquals(List.of(), ShadowResolutionFallback.smallerResolutions(List.of(), 4096));
    }

    @Test
    void packPreparedBeforeTheDeviceIsCheckedAgainOnce() {
        long gib = 1L << 30;
        assertEquals(true, ShadowResolutionFallback.needsRecheck(false, gib, gib / 2));
        assertEquals(false, ShadowResolutionFallback.needsRecheck(false, gib, gib));      // fits
        assertEquals(false, ShadowResolutionFallback.needsRecheck(false, gib, 0));        // device still unknown
        assertEquals(false, ShadowResolutionFallback.needsRecheck(true, gib, gib / 2));   // already sized: no loop
    }
}

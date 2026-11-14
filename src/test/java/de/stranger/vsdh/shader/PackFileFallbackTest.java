package de.stranger.vsdh.shader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PackFileFallbackTest {
    @Test
    void newerVersionReplacesTheMissingFile() {
        var files = List.of("Eclipse-Shader-Unstable.zip", "Jurgens-Shader-v1.0.1.zip", "Solas Shader V3.9.zip");
        assertEquals("Jurgens-Shader-v1.0.1.zip", PackFileFallback.pick("Jurgens-Shader-v1.0.zip", files));
    }

    @Test
    void picksTheNewestOfSeveralVersions() {
        var files = List.of("ComplementaryReimagined_r5.9.2.zip", "ComplementaryReimagined_r5.10.zip", "ComplementaryReimagined_r5.9.3.zip");
        assertEquals("ComplementaryReimagined_r5.10.zip", PackFileFallback.pick("ComplementaryReimagined_r5.9.1.zip", files));
    }

    @Test
    void otherPacksAreNeverChosen() {
        var files = List.of("ComplementaryUnbound_r5.9.3.zip", "BSL_v10.1.zip");
        assertNull(PackFileFallback.pick("ComplementaryReimagined_r5.9.3.zip", files));
        assertNull(PackFileFallback.pick("Solas Shader V3.9.zip", List.of("Solas Shader V3.9.zip")));
    }

    @Test
    void familyDropsExtensionAndVersion() {
        assertEquals("jurgens-shader", PackFileFallback.family("Jurgens-Shader-v1.0.1.zip"));
        assertEquals("solas shader", PackFileFallback.family("Solas Shader V3.9.zip"));
        assertEquals("eclipse-shader-unstable", PackFileFallback.family("Eclipse-Shader-Unstable.zip"));
    }
}

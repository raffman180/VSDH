package de.stranger.vsdh.shader;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PackPreparationKeyTest {
    private static PackPreparationKey key(long size, long modified, String profile, Map<String, String> options, boolean distant) {
        return new PackPreparationKey("Pack.zip", size, modified, profile, options, distant, false, false);
    }

    @Test
    void sameFileProfileAndOptionsAreOnePreparation() {
        var options = new HashMap<String, String>();
        options.put("EXPOSURE", "0.6");
        var first = key(100, 5, "HIGH", options, true);
        options.put("EXPOSURE", "0.8"); // the key keeps its own copy
        assertEquals(first, key(100, 5, "HIGH", Map.of("EXPOSURE", "0.6"), true));
        assertEquals(first.hashCode(), key(100, 5, "HIGH", Map.of("EXPOSURE", "0.6"), true).hashCode());
    }

    @Test
    void anyDifferenceThatChangesTheResultIsAnotherPreparation() {
        var base = key(100, 5, "HIGH", Map.of(), true);
        assertNotEquals(base, key(101, 5, "HIGH", Map.of(), true)); // file replaced
        assertNotEquals(base, key(100, 6, "HIGH", Map.of(), true)); // file edited
        assertNotEquals(base, key(100, 5, "LOW", Map.of(), true));
        assertNotEquals(base, key(100, 5, "HIGH", Map.of("A", "1"), true));
        assertNotEquals(base, key(100, 5, "HIGH", Map.of(), false));
        assertNotEquals(base, new PackPreparationKey("Pack.zip", 100, 5, "HIGH", Map.of(), true, true, false));
    }
}

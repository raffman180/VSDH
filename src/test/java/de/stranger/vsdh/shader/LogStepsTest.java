package de.stranger.vsdh.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogStepsTest {
    @Test
    void onlyPowersOfTenAreLogged() {
        int logged = 0;
        for (long count = 1; count <= 100_000; count++) if (LogSteps.powerOfTen(count)) logged++;
        assertEquals(6, logged, "1, 10, 100, 1000, 10000, 100000");
        assertTrue(LogSteps.powerOfTen(1));
        assertTrue(LogSteps.powerOfTen(1_000_000_000_000L));
        assertFalse(LogSteps.powerOfTen(0));
        assertFalse(LogSteps.powerOfTen(-10));
        assertFalse(LogSteps.powerOfTen(20));
        assertFalse(LogSteps.powerOfTen(110));
    }
}

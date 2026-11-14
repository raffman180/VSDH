package net.vulkanmod.config.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowModeTest {
    @Test
    void fullscreenIsExclusiveAndThereIsNoBorderlessMode() {
        assertEquals(WindowMode.EXCLUSIVE_FULLSCREEN, WindowMode.of(true));
        assertEquals(WindowMode.WINDOWED, WindowMode.of(false));
        assertArrayEquals(new WindowMode[]{WindowMode.WINDOWED, WindowMode.EXCLUSIVE_FULLSCREEN}, WindowMode.values());
    }
}

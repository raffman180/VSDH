package net.vulkanmod.config.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowModeTest {
    @Test
    void fullscreenFlagAndKindGiveTheMode() {
        assertEquals(WindowMode.WINDOWED, WindowMode.of(false, false));
        assertEquals(WindowMode.WINDOWED, WindowMode.of(false, true));
        assertEquals(WindowMode.EXCLUSIVE_FULLSCREEN, WindowMode.of(true, false));
        assertEquals(WindowMode.BORDERLESS, WindowMode.of(true, true));
    }
}

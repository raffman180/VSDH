package net.vulkanmod.config.video;

/**
 * A window or exclusive fullscreen. The borderless ("windowed fullscreen") mode was removed on 15.11.2026: its
 * presentation was not smooth enough on the player's machine, while exclusive fullscreen was.
 */
public enum WindowMode {
    WINDOWED,
    EXCLUSIVE_FULLSCREEN;

    public static WindowMode of(boolean fullscreen) {
        return fullscreen ? EXCLUSIVE_FULLSCREEN : WINDOWED;
    }

    public static String getComponentName(WindowMode windowMode) {
        return switch (windowMode) {
            case WINDOWED -> "vulkanmod.options.windowMode.windowed";
            case EXCLUSIVE_FULLSCREEN -> "options.fullscreen";
        };
    }
}

package net.vulkanmod.config.video;

/**
 * A window, a borderless window over the monitor, or exclusive fullscreen. Minecraft's own fullscreen flag says
 * whether the game is fullscreen; Config.borderlessFullscreen says which kind.
 */
public enum WindowMode {
    WINDOWED,
    BORDERLESS,
    EXCLUSIVE_FULLSCREEN;

    public static WindowMode of(boolean fullscreen, boolean borderless) {
        if (!fullscreen) return WINDOWED;
        return borderless ? BORDERLESS : EXCLUSIVE_FULLSCREEN;
    }

    public static String getComponentName(WindowMode windowMode) {
        return switch (windowMode) {
            case WINDOWED -> "vulkanmod.options.windowMode.windowed";
            case BORDERLESS -> "vulkanmod.options.windowMode.windowedFullscreen";
            case EXCLUSIVE_FULLSCREEN -> "vsdh.options.windowMode.exclusive";
        };
    }
}

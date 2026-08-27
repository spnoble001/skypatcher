package perfectpathc.dev.modules.impl.utils;

/** Shared production preview switch owned by SkyPatcher's parent UI. */
public final class GlobalRenderHelp {
    private static volatile boolean enabled = true;

    private GlobalRenderHelp() { }

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean value) { enabled = value; }
}

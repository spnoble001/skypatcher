package perfectpathc.dev.modules.impl.utils;

/** Shared spatial-assistance choice for the selected SkyPatcher executor. */
public final class GlobalSpatialAssistance {
    private static volatile boolean enabled = true;
    private GlobalSpatialAssistance() { }
    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean value) { enabled = value; }
}

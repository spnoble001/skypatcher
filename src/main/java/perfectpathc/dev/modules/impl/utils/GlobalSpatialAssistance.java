package perfectpathc.dev.modules.impl.utils;

/** Shared spatial-assistance choice for the selected SkyPatcher executor. */
public final class GlobalSpatialAssistance {
    private static volatile boolean enabled = true;
    private static volatile boolean creativeReach;
    private GlobalSpatialAssistance() { }
    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean value) { enabled = value; }
    public static boolean isCreativeReachEnabled() { return enabled && creativeReach; }
    public static void setCreativeReachEnabled(boolean value) { creativeReach = value; }
    public static double maximumPhysicalReach() {
        return isCreativeReachEnabled() ? 5.0D : 4.5D;
    }
}

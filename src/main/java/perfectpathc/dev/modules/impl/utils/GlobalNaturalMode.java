package perfectpathc.dev.modules.impl.utils;

/** Shared Natural-mode choice for either 1D or 3D SkyPatcher execution. */
public final class GlobalNaturalMode {
    private static volatile boolean enabled;
    private GlobalNaturalMode() { }
    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean value) { enabled = value; }
}

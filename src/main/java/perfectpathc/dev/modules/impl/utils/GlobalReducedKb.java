package perfectpathc.dev.modules.impl.utils;

/** One shared toggle for the packet-only knockback/friction adapter. */
public final class GlobalReducedKb {
    private static volatile boolean enabled = true;

    private GlobalReducedKb() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }
}

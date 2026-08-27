package perfectpathc.dev.modules.impl.utils;

/**
 * One production-facing rate shared by every Patch placement/click executor.
 * It is intentionally independent from any module's enabled state.
 */
public final class GlobalCps {
    public static final float MINIMUM = 1.0F;
    public static final float MAXIMUM = 20.0F;
    private static volatile float value = 12.0F;

    private GlobalCps() {
    }

    public static float get() {
        return value;
    }

    public static void set(float requested) {
        value = Math.max(MINIMUM, Math.min(MAXIMUM, Math.round(requested)));
    }
}

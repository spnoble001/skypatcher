package perfectpathc.dev.modules.runtime;

/**
 * Stable contract implemented by an embedded module's entry point.
 *
 * <p>The base mod owns discovery and lifecycle; dynamically loaded modules
 * must not declare Forge {@code @Mod} entry points.</p>
 */
public interface IModuleEntry {
    /** Called once after the entry class has been instantiated. */
    void initialize();

    /**
     * Releases this entry before the embedded runtime is suspended or its
     * class loader is discarded. Implementations must be safe to call more
     * than once.
     */
    void dsg();

    /** Generic, numeric command channel between the fixed bootstrap and core.bin. */
    Object invokeCommand(int commandId, Object... args);
}

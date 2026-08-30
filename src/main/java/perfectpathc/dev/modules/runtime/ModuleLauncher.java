package perfectpathc.dev.modules.runtime;

/** Instantiates the sole opaque entry point declared by an embedded container. */
public final class ModuleLauncher {
    private ModuleLauncher() {
    }

    public static IEmbeddedRuntime launchRuntime(EmbeddedModuleClassLoader loader)
            throws ReflectiveOperationException {
        if (loader == null) throw new IllegalArgumentException("loader cannot be null");
        String entryClassName = loader.getEntryPointClassName();
        Class<?> entryClass = Class.forName(entryClassName, true, loader);
        if (!IEmbeddedRuntime.class.isAssignableFrom(entryClass)) {
            throw new IllegalArgumentException("Embedded entry point has an invalid contract");
        }
        return (IEmbeddedRuntime) entryClass.getDeclaredConstructor().newInstance();
    }
}

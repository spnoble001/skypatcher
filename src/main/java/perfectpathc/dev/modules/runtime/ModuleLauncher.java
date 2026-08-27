package perfectpathc.dev.modules.runtime;

/** Instantiates an explicit entry point from an embedded module loader. */
public final class ModuleLauncher {
    private ModuleLauncher() {
    }

    public static IModuleEntry launch(EmbeddedModuleClassLoader loader, String entryClassName)
            throws ReflectiveOperationException {
        if (loader == null) throw new IllegalArgumentException("loader cannot be null");
        if (entryClassName == null || entryClassName.length() == 0) {
            throw new IllegalArgumentException("entryClassName cannot be empty");
        }

        Class<?> entryClass = Class.forName(entryClassName, true, loader);
        if (!IModuleEntry.class.isAssignableFrom(entryClass)) {
            throw new IllegalArgumentException(entryClassName + " does not implement IModuleEntry");
        }
        IModuleEntry entry = (IModuleEntry) entryClass.getDeclaredConstructor().newInstance();
        entry.initialize();
        return entry;
    }
}

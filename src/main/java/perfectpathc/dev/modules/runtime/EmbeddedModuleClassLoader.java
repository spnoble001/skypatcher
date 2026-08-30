package perfectpathc.dev.modules.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Parent-first class loader for already-decompressed embedded module classes.
 *
 * <p>Only classes explicitly present in the supplied map are defined here;
 * Minecraft, Forge, Java and base-mod types resolve through the parent loader.
 * Class data is released by {@link #close()} when the loader is no longer used.
 */
public final class EmbeddedModuleClassLoader extends ClassLoader implements AutoCloseable {
    private Map<String, byte[]> classBytes;
    private final String entryPointClassName;
    private boolean closed;

    public EmbeddedModuleClassLoader(ClassLoader parent, Map<String, byte[]> classBytes,
                                     String entryPointClassName) {
        super(parent);
        if (classBytes == null) throw new IllegalArgumentException("classBytes cannot be null");
        if (entryPointClassName == null || entryPointClassName.length() == 0) {
            throw new IllegalArgumentException("entryPointClassName cannot be empty");
        }
        Map<String, byte[]> copy = new HashMap<String, byte[]>(classBytes.size());
        for (Map.Entry<String, byte[]> entry : classBytes.entrySet()) {
            String name = entry.getKey();
            byte[] bytes = entry.getValue();
            if (name == null || bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("Invalid embedded class entry");
            }
            copy.put(name, bytes.clone());
        }
        this.classBytes = copy;
        this.entryPointClassName = entryPointClassName;
    }

    public String getEntryPointClassName() {
        return this.entryPointClassName;
    }

    @Override
    protected synchronized Class<?> findClass(String binaryName) throws ClassNotFoundException {
        if (this.closed) throw new ClassNotFoundException(binaryName + " (loader closed)");

        byte[] bytes = this.classBytes.get(binaryName);
        if (bytes == null) throw new ClassNotFoundException(binaryName);

        int packageEnd = binaryName.lastIndexOf('.');
        if (packageEnd > 0) {
            String packageName = binaryName.substring(0, packageEnd);
            if (getPackage(packageName) == null) {
                definePackage(packageName, null, null, null, null, null, null, null);
            }
        }

        try {
            return defineClass(binaryName, bytes, 0, bytes.length);
        } catch (ClassFormatError exception) {
            throw new ClassNotFoundException("Invalid bytecode for " + binaryName, exception);
        } catch (LinkageError exception) {
            throw new ClassNotFoundException("Could not link " + binaryName, exception);
        }
    }

    public synchronized Map<String, Integer> getEmbeddedClassSizes() {
        if (this.closed) return Collections.emptyMap();
        Map<String, Integer> sizes = new HashMap<String, Integer>(this.classBytes.size());
        for (Map.Entry<String, byte[]> entry : this.classBytes.entrySet()) {
            sizes.put(entry.getKey(), entry.getValue().length);
        }
        return Collections.unmodifiableMap(sizes);
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        this.classBytes.clear();
        this.classBytes = Collections.emptyMap();
    }
}

package perfectpathc.dev.modules.runtime;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Reads a GZIP-compressed, versioned module container from the base JAR. */
public final class ModuleRegistry {
    private static final int MAGIC = 0x50504D31; // "PPM1"
    private static final int VERSION = 2;
    private static final int MAX_CLASSES = 512;
    private static final int MAX_CLASS_BYTES = 2 * 1024 * 1024;

    private ModuleRegistry() {
    }

    /**
     * Container payload, after GZIP decompression:
     *
     * <pre>
     * int magic (PPM1), int version, UTF payloadEntryClass, int classCount
     * repeat classCount times: UTF binaryClassName, int bytecodeLength, byte[] bytecode
     * </pre>
     */
    public static EmbeddedModuleClassLoader load(Class<?> resourceAnchor, String resourcePath)
            throws IOException {
        if (resourceAnchor == null) throw new IllegalArgumentException("resourceAnchor cannot be null");
        if (resourcePath == null || resourcePath.length() == 0) {
            throw new IllegalArgumentException("resourcePath cannot be empty");
        }

        InputStream resource = resourceAnchor.getResourceAsStream(resourcePath);
        if (resource == null) throw new IOException("Embedded module resource not found: " + resourcePath);
        try {
            Container container = readContainer(resource);
            return new EmbeddedModuleClassLoader(resourceAnchor.getClassLoader(), container.classes,
                    container.entryPointClassName);
        } finally {
            resource.close();
        }
    }

    /** Loads an already-authorized in-memory container without a resource lookup. */
    public static EmbeddedModuleClassLoader loadFromBuffer(Class<?> resourceAnchor, byte[] container)
            throws IOException {
        if (resourceAnchor == null) throw new IllegalArgumentException("resourceAnchor cannot be null");
        if (container == null || container.length == 0) {
            throw new IllegalArgumentException("container cannot be empty");
        }
        Container parsed = readContainer(new ByteArrayInputStream(container));
        return new EmbeddedModuleClassLoader(resourceAnchor.getClassLoader(), parsed.classes,
                parsed.entryPointClassName);
    }

    /**
     * Discards the in-memory module container associated with a dormant
     * runtime. The registry deliberately keeps no global loader reference.
     */
    public static void unload(EmbeddedModuleClassLoader loader) {
        if (loader != null) loader.close();
    }

    private static Container readContainer(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(new BufferedInputStream(new GZIPInputStream(input)));
        try {
            if (data.readInt() != MAGIC) throw new IOException("Invalid embedded module container");
            if (data.readInt() != VERSION) throw new IOException("Unsupported module container version");
            String entryPointClassName = data.readUTF();
            if (!isBinaryClassName(entryPointClassName)) {
                throw new IOException("Invalid embedded payload entry point");
            }
            int count = data.readInt();
            if (count < 0 || count > MAX_CLASSES) throw new IOException("Invalid module class count: " + count);

            Map<String, byte[]> classes = new HashMap<String, byte[]>(count);
            for (int index = 0; index < count; index++) {
                String binaryName = data.readUTF();
                int length = data.readInt();
                if (!isBinaryClassName(binaryName) || length <= 0 || length > MAX_CLASS_BYTES) {
                    throw new IOException("Invalid class entry in module container");
                }
                byte[] bytecode = new byte[length];
                data.readFully(bytecode);
                if (classes.put(binaryName, bytecode) != null) {
                    throw new IOException("Duplicate embedded class: " + binaryName);
                }
            }
            if (!classes.containsKey(entryPointClassName)) {
                throw new IOException("Embedded payload entry point is missing");
            }
            return new Container(classes, entryPointClassName);
        } finally {
            data.close();
        }
    }

    private static boolean isBinaryClassName(String name) {
        if (name == null || name.length() == 0 || name.charAt(0) == '.' || name.endsWith(".")) return false;
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (!(character == '.' || character == '$' || character == '_' || Character.isLetterOrDigit(character))) {
                return false;
            }
        }
        return true;
    }

    private static final class Container {
        private final Map<String, byte[]> classes;
        private final String entryPointClassName;

        private Container(Map<String, byte[]> classes, String entryPointClassName) {
            this.classes = classes;
            this.entryPointClassName = entryPointClassName;
        }
    }
}

package perfectpathc.dev.modules.runtime;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Reads a GZIP-compressed, versioned module container from the base JAR. */
public final class ModuleRegistry {
    private static final int MAGIC = 0x50504D31; // "PPM1"
    private static final int VERSION = 1;
    private static final int MAX_CLASSES = 512;
    private static final int MAX_CLASS_BYTES = 2 * 1024 * 1024;

    private ModuleRegistry() {
    }

    /**
     * Container payload, after GZIP decompression:
     *
     * <pre>
     * int magic (PPM1), int version, int classCount
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
            return new EmbeddedModuleClassLoader(resourceAnchor.getClassLoader(), readContainer(resource));
        } finally {
            resource.close();
        }
    }

    /**
     * Discards the in-memory module container associated with a dormant
     * runtime. The registry deliberately keeps no global loader reference.
     */
    public static void unload(EmbeddedModuleClassLoader loader) {
        if (loader != null) loader.close();
    }

    private static Map<String, byte[]> readContainer(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(new BufferedInputStream(new GZIPInputStream(input)));
        try {
            if (data.readInt() != MAGIC) throw new IOException("Invalid embedded module container");
            if (data.readInt() != VERSION) throw new IOException("Unsupported module container version");
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
            return classes;
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
}

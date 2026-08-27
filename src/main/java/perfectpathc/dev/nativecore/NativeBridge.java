package perfectpathc.dev.nativecore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/** Fixed bootstrap JNI boundary. Payload semantics live in core.bin/Rust. */
public final class NativeBridge {
    static {
        load();
    }

    private NativeBridge() {
    }

    public static native long invoke(int opcode, long[] args);

    /** Rust-owned pearl trajectory decision. Each sample packs medium-at-start
     *  (bit 0: water) and solid ray hit after the Euler step (bit 1). */
    public static native int simulateSmartPearlGateNative(double x, double y, double z,
            double velocityX, double velocityY, double velocityZ, double playerY,
            float minimumGain, int mode, long[] samples);

    private static void load() {
        String configured = System.getProperty("perfectpatch.native.path");
        if (configured != null && configured.trim().length() != 0) {
            try { System.load(new File(configured).getAbsolutePath()); } catch (Throwable ignored) { }
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean windows = os.contains("win");
        if ((!windows && !os.contains("linux")) || !arch.contains("64")) return;
        String name = windows ? "perfectpatch_native.dll" : "libperfectpatch_native.so";
        InputStream input = NativeBridge.class.getResourceAsStream("/natives/"
                + (windows ? "windows" : "linux") + "/x86_64/" + name);
        if (input == null) return;
        try {
            File target = Files.createTempFile("perfectpatch-native-", windows ? ".dll" : ".so").toFile();
            target.deleteOnExit();
            OutputStream output = Files.newOutputStream(target.toPath());
            try {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0;) output.write(buffer, 0, read);
            } finally {
                output.close();
            }
            System.load(target.getAbsolutePath());
        } catch (Throwable ignored) {
        } finally {
            try { input.close(); } catch (IOException ignored) { }
        }
    }
}

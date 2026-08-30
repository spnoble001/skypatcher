package perfectpathc.dev.modules.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * JNI entry point for batched numerical routines.
 */
public final class NativeBridge {
    public static final int NATIVE_ACTIVE = 1;
    public static final int NATIVE_QUIESCING = 2;
    public static final int NATIVE_DORMANT = 3;
    public static final int THROW_POT_ACTION_SELECT = 1;
    public static final int THROW_POT_ACTION_SEND_SLOT = 2;
    public static final int THROW_POT_ACTION_USE = 4;
    public static final int THROW_POT_ACTION_RESTORE = 8;
    public static final int THROW_POT_ACTION_FINISHED = 16;

    private static volatile boolean nativeAvailable;
    private static volatile int nativeLifecycle;
    private static volatile String loadFailure;

    static {
        try {
            nativeLifecycle = (int) rawInvoke(2, new long[0]);
            nativeAvailable = nativeLifecycle == NATIVE_ACTIVE;
        } catch (Throwable throwable) {
            nativeLifecycle = 0;
            nativeAvailable = false;
            loadFailure = throwable.getClass().getSimpleName();
        }
    }

    private NativeBridge() {
    }

    public static boolean isNativeAvailable() {
        return nativeAvailable && nativeLifecycle == NATIVE_ACTIVE;
    }

    public static int nativeLifecycle() {
        return nativeLifecycle;
    }

    /** Irreversibly disables JNI for this JVM session: drain in-flight native
     *  calls, wipe Rust state, and UnregisterNatives on this class. The .so/.dll
     *  stays mapped (HotSpot pins System.load libs to the ClassLoader). */
    public static synchronized boolean quiesceNativeCore() {
        if (!nativeAvailable || nativeLifecycle != NATIVE_ACTIVE) {
            return nativeLifecycle == NATIVE_DORMANT;
        }
        nativeLifecycle = NATIVE_QUIESCING;
        nativeAvailable = false;
        try {
            boolean wiped = rawInvoke(1, new long[0]) != 0L;
            nativeLifecycle = NATIVE_DORMANT;
            return wiped;
        } catch (UnsatisfiedLinkError linkError) {
            // Already unregistered by a concurrent quiesce — treat as dormant.
            nativeLifecycle = NATIVE_DORMANT;
            return true;
        } catch (Throwable throwable) {
            loadFailure = "Native quiesce failed: " + throwable.getClass().getSimpleName();
            nativeLifecycle = NATIVE_DORMANT;
            return false;
        }
    }

    /** Sole safe dispatcher exposed to Java callers. */
    public static long invoke(int opcode, long[] args) {
        if (!isNativeAvailable()) return 0L;
        return rawInvoke(opcode, args);
    }

    private static long rawInvoke(int opcode, long[] args) {
        return perfectpathc.dev.nativecore.NativeBridge.invoke(opcode, args);
    }

    private static long d(int opcode) { return rawInvoke(opcode, new long[0]); }

    /** -1=denied, 0=retained/unowned, positive=displaced owner ID. */
    public static int acquireRightClick(int requester, int priority) { if (!requireNative()) return 0; return (int) d(16, requester, priority); }
    public static void releaseRightClick(int requester) { if (!requireNative()) return; d(17, requester, 0L); }
    public static boolean isRightClickOwner(int requester) { if (!requireNative()) return false; return d(18, requester, 0L) != 0L; }
    public static int rightClickOwner() { if (!requireNative()) return 0; return (int) d(19, 0L, 0L); }
    public static int rightClickPriority() { if (!requireNative()) return 0; return (int) d(20, 0L, 0L); }
    private static long d(int opcode, long first, long second) {
        return rawInvoke(opcode, new long[] { first, second });
    }

    /** Minecraft 1.8.9 BlockPos: signed X/Z (26 bits), signed Y (12 bits). */
    public static long packBlockPos(int x, int y, int z) { if (!requireNative()) return Long.MIN_VALUE; return d(32, x, y, z); }
    /** Component: 0=X, 1=Y, 2=Z. */
    public static int unpackBlockPosComponent(long packed, int component) { if (!requireNative()) return 0; return (int) d(33, packed, component); }
    /** Bits 0-3 face, 4-7 action, 8-15 confirmation/timeout ticks. */
    public static int packBlockFlags(int face, int action, int ticks) { if (!requireNative()) return 0; return (int) d(34, face, action, ticks); }
    public static int routeInputTokens(int firstHash, int firstLength,
                                       int secondHash, int secondLength,
                                       boolean trailingToken) {
        if (!requireNative()) return 0;
        long first = ((long) firstHash & 0xFFFFFFFFL) | ((long) firstLength << 32);
        long second = ((long) secondHash & 0xFFFFFFFFL) | ((long) secondLength << 32);
        return (int) d(224, first, second, trailingToken ? 1L : 0L);
    }
    private static long d(int opcode, long first, long second, long third) {
        return rawInvoke(opcode, new long[] { first, second, third });
    }

    public static void resetPatchCrumbState() { if (!requireNative()) return; d(48); }
    public static boolean expirePatchCrumb(long nowMs) { if (!requireNative()) return false; return d(57, nowMs, 0L) != 0L; }
    public static int resolvePatchCrumbImpactY(int baseY, int obsidianMask, int solidMask) {
        if (!requireNative()) return 0;
        return (int) d(62, baseY, obsidianMask, solidMask);
    }
    public static int preparePatchCrumbTntCapture(boolean sandMode,
            double x, double y, double z, double motionX, double motionY, double motionZ,
            boolean sandBelow, long nowMs) {
        if (!requireNative()) return 0;
        return (int) d(221, sandMode ? 1L : 0L, Double.doubleToRawLongBits(x),
                Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(z),
                Double.doubleToRawLongBits(motionX), Double.doubleToRawLongBits(motionY),
                Double.doubleToRawLongBits(motionZ), sandBelow ? 1L : 0L, nowMs, 0L);
    }
    public static void completePatchCrumbTntCapture() { if (!requireNative()) return; d(49); }
    public static long patchCrumbTntSnapshotPacked() {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(50);
    }
    public static void beginPatchCrumbPatchScan(long nowMs) { if (!requireNative()) return; d(58, nowMs, 0L); }
    public static boolean shouldScanPatchCrumbGuide(int currentTick, long revision,
            long playerCell, int patchSide, int intervalTicks) {
        if (!requireNative()) return false;
        long control = ((long) currentTick & 0xFFFFFFFFL)
                | ((long) patchSide & 0xFFFFL) << 32
                | ((long) intervalTicks & 0xFFFFL) << 48;
        return d(203, control, revision, playerCell) != 0L;
    }
    public static void submitPatchCrumbScanLane(long origin, long obsidianMask,
            long blockingMask, long expiresAtMs) {
        if (!requireNative()) return;
        d(214, origin, obsidianMask, blockingMask, expiresAtMs);
    }
    public static void clearPatchCrumbActivePatches() { if (!requireNative()) return; d(51); }
    public static void syncPatchCrumbsToSimpleAssist() { if (!requireNative()) return; d(53); }
    public static void expirePatchCrumbLines(long nowMs) { if (!requireNative()) return; d(59, nowMs, 0L); }
    public static int patchCrumbLineCount() { if (!requireNative()) return 0; return (int) d(54); }
    public static long patchCrumbLineOrigin(int index) { if (!requireNative()) return Long.MIN_VALUE; return d(60, index, 0L); }
    public static long patchCrumbLineTerminal(int index) { if (!requireNative()) return Long.MIN_VALUE; return d(61, index, 0L); }
    public static void resolvePatchCrumbDirection(int directionMode, boolean xCollision,
                                                  boolean zCollision) {
        if (!requireNative()) return;
        d(202, directionMode, xCollision ? 1L : 0L, zCollision ? 1L : 0L);
    }
    public static void capturePatchCrumb(double x, double y, double z, long nowMs,
                                          long detectionCooldownMs) {
        if (!requireNative()) return;
        d(215, Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y),
                Double.doubleToRawLongBits(z), nowMs, detectionCooldownMs, 0L);
    }
    public static boolean isPatchCrumbActive(long nowMs) { if (!requireNative()) return false; return d(200, nowMs, 0L) != 0L; }
    public static long patchCrumbPackedPos(long nowMs) { if (!requireNative()) return Long.MIN_VALUE; return d(201, nowMs, 0L); }
    public static long patchCrumbRevision() { if (!requireNative()) return Long.MIN_VALUE; return d(55); }
    public static int patchCrumbFlags() { if (!requireNative()) return 0; return (int) d(56); }
    private static long d(int opcode, long first, long second, long third, long fourth) {
        return invoke(opcode, new long[] { first, second, third, fourth });
    }

    public static void resetPrePatchScan() { if (!requireNative()) return; d(192); }
    /** Triggers offline license validation/claim generation in the native layer. */
    public static boolean isSimpleAssistLicensed() { return requireNative() && d(239) != 0L; }
    /** Fail-open recovery for a stalled optional render scan. */
    public static boolean watchdogPrePatch(long now) {
        if (!requireNative()) return false;
        return d(225, now, 0L, 0L) != 0L;
    }
    public static long beginPrePatchTickAndScan(long now, double playerX,
                                                double playerY, double playerZ,
                                                int centerX, int centerY, int centerZ,
                                                boolean eastWest) {
        if (!requireNative()) return Long.MIN_VALUE;
        long center = ((long) centerX & 0x1FFFFFL)
                | (((long) centerY & 0x1FFFFFL) << 21)
                | (((long) centerZ & 0x1FFFFFL) << 42)
                | (eastWest ? Long.MIN_VALUE : 0L);
        return d(219, now, Double.doubleToRawLongBits(playerX),
                Double.doubleToRawLongBits(playerY), Double.doubleToRawLongBits(playerZ),
                center, 0L);
    }
    public static void finishPrePatchTick(int centerX, int centerY, int centerZ,
                                          long position, int face,
                                          int minimum, int maximum) {
        if (!requireNative()) return;
        long center = ((long) centerX & 0x1FFFFFL)
                | (((long) centerY & 0x1FFFFFL) << 21)
                | (((long) centerZ & 0x1FFFFFL) << 42);
        long limits = ((long) minimum & 0xFFFFFFFFL)
                | ((long) maximum << 32);
        d(220, center, position, face, limits);
    }
    public static boolean hasPrePatchVisibleAnchor(long position, int face) {
        if (!requireNative()) return false;
        return d(195, position, face) != 0L;
    }
    public static boolean matchesPrePatchIntent(long position, int face) {
        if (!requireNative()) return false;
        return d(232, position, face) != 0L;
    }
    public static long prePatchRenderCounts() {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(193);
    }
    /**
     * Cardinal face currently selected by PrePatch's live intent, or {@code -1}
     * when no placement is actionable.  This is deliberately read-only: the
     * production indicator follows native arbitration instead of guessing from
     * the configured axis or the camera ray.
     */
    public static int prePatchActiveIntentFace() {
        if (!requireNative()) return -1;
        return (int) d(236);
    }
    /** Immutable origin selected by PrePatch's current live intent. */
    public static long prePatchActiveIntentOrigin() {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(237);
    }
    /** Immutable terminal selected by PrePatch's current live intent. */
    public static long prePatchActiveIntentTerminal() {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(238);
    }
    /** Advances only an already-captured PrePatch horizon on its V1 ray. */
    public static void retargetSimpleAssistPrePatch(long origin) {
        if (!requireNative()) return;
        d(240, origin, 0L);
    }
    public static long prePatchRenderRecord(int index) {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(196, index, 0L);
    }
    public static int prePatchSyntheticSpread() {
        if (!requireNative()) return 0;
        return (int) d(194);
    }
    public static long prePatchDiagnosticTiming(long now) {
        if (!requireNative()) return 0L;
        return d(230, now, 0L);
    }
    public static long prePatchDiagnosticCounts() {
        if (!requireNative()) return 0L;
        return d(231);
    }
    public static void queuePrePatchPlacementCheck(long hitPosition, int face) {
        if (!requireNative()) return;
        d(197, hitPosition, face);
    }
    public static long pollPrePatchWork(int action, long now) {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(198, action, now);
    }
    public static void recordPrePatchBlockPlaced(long position, long now) {
        if (!requireNative()) return;
        d(199, position, now);
    }
    public static int updatePrePatchClicker(int operation, long now, float cps) {
        if (!requireNative()) return 0;
        return (int) d(204, operation, now, Float.floatToRawIntBits(cps));
    }
    public static void submitPrePatchScanCandidate(long position,
                                                    boolean firstExposed,
                                                    boolean secondExposed) {
        if (!requireNative()) return;
        d(205, position, firstExposed ? 1L : 0L, secondExposed ? 1L : 0L);
    }

    public static void startSimpleAssistPatch(long origin, long terminal, double playerX,
                                              double playerY, double playerZ, int tick) {
        if (!requireNative()) return;
        d(217, origin, terminal, Double.doubleToRawLongBits(playerX),
                Double.doubleToRawLongBits(playerY), Double.doubleToRawLongBits(playerZ), tick);
    }
    public static void resetSimpleAssist() { if (nativeAvailable) d(147); }
    public static void beginSimpleAssistRequest(long target) { if (!requireNative()) return; d(175, target, 0L); }
    public static void pushSimpleAssistNaturalCandidate(long support, int face, double hitX,
            double hitY, double hitZ, int supportSnapshot, double eyeX, double eyeY,
            double eyeZ, double reach) {
        if (!requireNative()) return;
        d(218, support, face, Double.doubleToRawLongBits(hitX), Double.doubleToRawLongBits(hitY),
                Double.doubleToRawLongBits(hitZ), supportSnapshot, Double.doubleToRawLongBits(eyeX),
                Double.doubleToRawLongBits(eyeY), Double.doubleToRawLongBits(eyeZ),
                Double.doubleToRawLongBits(reach));
    }
    public static long pollSimpleAssistGuidanceSupport(int face) {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(212, face, 0L);
    }
    public static void pushSimpleAssistGuidanceCandidate(int face, int supportSnapshot,
            double eyeX, double eyeY, double eyeZ, double reach) {
        if (!requireNative()) return;
        d(216, face, supportSnapshot, Double.doubleToRawLongBits(eyeX),
                Double.doubleToRawLongBits(eyeY), Double.doubleToRawLongBits(eyeZ),
                Double.doubleToRawLongBits(reach));
    }
    public static boolean hasSimpleAssistRequest() { if (!requireNative()) return false; return d(148) != 0L; }
    public static long pollSimpleAssistRequestTarget() { if (!requireNative()) return Long.MIN_VALUE; return d(149); }
    public static long pollSimpleAssistRequestSupport() { if (!requireNative()) return Long.MIN_VALUE; return d(150); }
    public static int pollSimpleAssistRequestFace() { if (!requireNative()) return 0; return (int) d(151); }
    public static double pollSimpleAssistRequestHitX() { if (!requireNative()) return 0.0D; return Double.longBitsToDouble(d(152)); }
    public static double pollSimpleAssistRequestHitY() { if (!requireNative()) return 0.0D; return Double.longBitsToDouble(d(153)); }
    public static double pollSimpleAssistRequestHitZ() { if (!requireNative()) return 0.0D; return Double.longBitsToDouble(d(154)); }
    public static long nextSimpleAssistCursor(boolean cursorIsAccepted) { if (!requireNative()) return Long.MIN_VALUE; return d(176, cursorIsAccepted ? 1L : 0L, 0L); }
    public static void beginSimpleAssistPlacement(long target, int expectedBlock, int tick) {
        if (!requireNative()) return;
        d(208, target, expectedBlock, tick);
    }
    /** Returns the native pending target, or Long.MIN_VALUE when no confirmation is pending. */
    public static long pollSimpleAssistPendingTarget() { if (!requireNative()) return Long.MIN_VALUE; return d(155); }
    public static void setSimpleAssistPreview(long target) { if (!requireNative()) return; d(177, target, 0L); }
    public static long pollSimpleAssistPreview() { if (!requireNative()) return Long.MIN_VALUE; return d(156); }
    public static int pollSimpleAssistPreviewX() { if (!requireNative()) return 0; return (int) d(157); }
    public static int pollSimpleAssistPreviewY() { if (!requireNative()) return 0; return (int) d(158); }
    public static int pollSimpleAssistPreviewZ() { if (!requireNative()) return 0; return (int) d(159); }
    public static int confirmSimpleAssistPlacement(int actualBlock, int tick, int ping) {
        if (!requireNative()) return 0;
        return (int) d(209, actualBlock, tick, ping);
    }
    public static boolean consumeSimpleAssistCredit(int tick, float cps) {
        if (!requireNative()) return false;
        return d(210, tick, Float.floatToRawIntBits(cps)) != 0L;
    }
    public static boolean hasSimpleAssistCredit(int tick, float cps) {
        if (!requireNative()) return false;
        return d(225, tick, Float.floatToRawIntBits(cps)) != 0L;
    }
    public static boolean isSimpleAssistCaptureValid(int tick, double x, double y, double z) {
        if (!requireNative()) return false;
        return d(211, tick, Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y),
                Double.doubleToRawLongBits(z), 0L, 0L) != 0L;
    }
    public static boolean hasSimpleAssistGrace(int tick) { if (!requireNative()) return false; return d(169, tick, 0L) != 0L; }
    public static void extendSimpleAssistGrace(int tick) { if (!requireNative()) return; d(170, tick, 0L); }
    public static void beginSimpleAssistPatchFrame() { if (!requireNative()) return; d(160); }
    public static void reconcileSimpleAssistPatchFrame(int tick) { if (!requireNative()) return; d(227, tick, 0L); }
    public static void recordSimpleAssistRejectedInteraction(int tick) { if (!requireNative()) return; d(228, tick, 0L); }
    public static void suspendSimpleAssistWatchdog(int tick) { if (!requireNative()) return; d(229, tick, 0L); }
    public static int simpleAssistPatchCount() { if (!requireNative()) return 0; return (int) d(161); }
    /** 0=none/busy, 1=real crumb accepted, 2=PrePatch accepted. */
    public static int arbitrateSimpleAssistPatch(long hit, int hitFace, double x, double y,
                                                  double z, int tick) {
        if (!requireNative()) return 0;
        return (int)d(234, hit, Double.doubleToRawLongBits(x),
                Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(z), tick, hitFace);
    }
    public static boolean matchesSimpleAssistPatchHit(long hit) {
        if (!requireNative()) return false;
        return d(206, hit, 0L) != 0L;
    }
    public static long pollSimpleAssistOrigin() { if (!requireNative()) return Long.MIN_VALUE; return d(162); }
    public static long pollSimpleAssistTerminal() { if (!requireNative()) return Long.MIN_VALUE; return d(163); }
    public static long pollSimpleAssistCursor() { if (!requireNative()) return Long.MIN_VALUE; return d(164); }
    public static boolean isSimpleAssistCaptured() { if (!requireNative()) return false; return d(165) != 0L; }
    public static boolean isSimpleAssistAcceptedBlock(int blockId) { if (!requireNative()) return false; return d(171, blockId, 0L) != 0L; }
    public static void configureSimpleAssistAcceptedBlocks(int obsidianId, int bedrockId) { if (!requireNative()) return; d(178, obsidianId, bedrockId); }
    public static boolean isSimpleAssistReplaceableSnapshot(int snapshot) { if (!requireNative()) return false; return d(172, snapshot, 0L) != 0L; }
    public static float simpleAssistMaxCps() { if (!requireNative()) return 0.0F; return Float.intBitsToFloat((int) d(166)); }
    public static boolean isSimpleAssistBackoffReady(int tick) { if (!requireNative()) return false; return d(173, tick, 0L) != 0L; }
    public static boolean recordSimpleAssistRequestMiss() { if (!requireNative()) return false; return d(167) != 0L; }
    public static void resetSimpleAssistRequestMisses() { if (!requireNative()) return; d(168); }
    public static int simpleAssistPhase(int tick) { if (!requireNative()) return 0; return (int) d(174, tick, 0L); }
    /** True when native SimpleAssist abandoned a non-progressing capture. */
    public static boolean watchdogSimpleAssist(int tick) {
        return requireNative() && d(226, tick, 0L) != 0L;
    }

    public static String getLoadFailure() {
        return loadFailure;
    }

    /** Rust-owned ten-step trajectory; returns the next yaw delta. */
    public static float nextAimStep(float difference, float speed, float sensitivity,
                                    boolean discard) {
        if (!requireNative()) return 0.0F;
        return Float.intBitsToFloat((int) d(131, Float.floatToRawIntBits(difference),
                Float.floatToRawIntBits(speed), Float.floatToRawIntBits(sensitivity),
                discard ? 1L : 0L, 0L, 0L));
    }

    public static void initializeAimState() { if (!requireNative()) return; d(128); }

    public static void resetAimState() { if (!requireNative()) return; d(129); }

    public static long nextAimNoiseDelta(int ticks, float partial, float strength, float speed,
                                         boolean targetChanged) {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(132, ticks, Float.floatToRawIntBits(partial), Float.floatToRawIntBits(strength),
                Float.floatToRawIntBits(speed), targetChanged ? 1L : 0L, 0L);
    }

    /**
     * Selects an aim target from raw player snapshots. Candidate layout is
     * nine floats: position xyz, box min/max xz, box max y, and reserved.
     */

    public static void beginAimCapture() { if (!requireNative()) return; d(130); }
    public static void captureAimCandidate(int id, int flags, float x, float y, float z,
                                           float minX, float maxX, float minZ, float maxZ,
                                           float maxY) {
        if (!requireNative()) return;
        d(134, id, flags, Float.floatToRawIntBits(x), Float.floatToRawIntBits(y),
                Float.floatToRawIntBits(z), Float.floatToRawIntBits(minX),
                Float.floatToRawIntBits(maxX), Float.floatToRawIntBits(minZ),
                Float.floatToRawIntBits(maxZ), Float.floatToRawIntBits(maxY));
    }
    private static long d(int opcode, long first, long second, long third, long fourth,
                          long fifth, long sixth, long seventh, long eighth, long ninth,
                          long tenth) {
        return invoke(opcode, new long[] { first, second, third, fourth, fifth, sixth, seventh, eighth, ninth, tenth });
    }
    public static long selectCapturedAimTargetPacked(float playerX, float playerY, float playerZ,
                                                      float playerYaw, float fov, float minimumDistance,
                                                      float maximumDistance, boolean lock) {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(133, Float.floatToRawIntBits(playerX), Float.floatToRawIntBits(playerY),
                Float.floatToRawIntBits(playerZ), Float.floatToRawIntBits(playerYaw),
                Float.floatToRawIntBits(fov), Float.floatToRawIntBits(minimumDistance),
                Float.floatToRawIntBits(maximumDistance), lock ? 1L : 0L);
    }
    private static long d(int opcode, long first, long second, long third, long fourth,
                          long fifth, long sixth, long seventh, long eighth) {
        return invoke(opcode, new long[] { first, second, third, fourth, fifth, sixth, seventh, eighth });
    }

    /**
     * Executes the complete Rust-owned aim decision. The result packs the yaw
     * delta in its high word and the already-clamped pitch in its low word.
     */
    public static long nextCapturedAimActionPacked(float playerX, float playerY, float playerZ,
                                                    float playerYaw, float playerPitch, int ticks,
                                                    float partialTick, float mouseSensitivity,
                                                    float fov, float speed, float minimumDistance,
                                                    float maximumDistance, boolean lock,
                                                    boolean clickRequired, boolean clickDown,
                                                    boolean swordOnly, boolean axeOnly,
                                                    int heldItemKind, float noiseStrength,
                                                    float noiseSpeed) {
        if (!requireNative()) return Long.MIN_VALUE;
        int flags = (lock ? 1 : 0) | (clickRequired ? 2 : 0) | (clickDown ? 4 : 0)
                | (swordOnly ? 8 : 0) | (axeOnly ? 16 : 0);
        return d(135, Float.floatToRawIntBits(playerX), Float.floatToRawIntBits(playerY),
                Float.floatToRawIntBits(playerZ), Float.floatToRawIntBits(playerYaw),
                Float.floatToRawIntBits(playerPitch), ticks, Float.floatToRawIntBits(partialTick),
                Float.floatToRawIntBits(mouseSensitivity), Float.floatToRawIntBits(fov),
                Float.floatToRawIntBits(speed), Float.floatToRawIntBits(minimumDistance),
                Float.floatToRawIntBits(maximumDistance), flags, heldItemKind,
                Float.floatToRawIntBits(noiseStrength), Float.floatToRawIntBits(noiseSpeed));
    }
    private static long d(int opcode, long first, long second, long third, long fourth,
                          long fifth, long sixth, long seventh, long eighth, long ninth,
                          long tenth, long eleventh, long twelfth, long thirteenth,
                          long fourteenth, long fifteenth, long sixteenth) {
        return invoke(opcode, new long[] { first, second, third, fourth, fifth, sixth, seventh, eighth, ninth, tenth, eleventh, twelfth, thirteenth, fourteenth, fifteenth, sixteenth });
    }

    public static void resetLeftClicker() {
        if (!requireNative()) return;
        d(64);
    }

    /** Packed action: 0 idle, 1 press, 2 release; bit 8 requests block-hit. */
    public static long nextLeftClickerActionPacked(float minimumCps, float maximumCps,
                                                    boolean organic, long nowMillis,
                                                    long nowNanos, boolean block) {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(213, Float.floatToRawIntBits(minimumCps), Float.floatToRawIntBits(maximumCps),
                organic ? 1L : 0L, nowMillis, nowNanos, block ? 1L : 0L);
    }

    /** Returns a refill action packed as slot (low 8 bits) and delay (bits 8-15). */
    public static long selectRefillAction(int candidateMask, float speed, boolean random) {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(68, candidateMask, Float.floatToRawIntBits(speed), random ? 1L : 0L);
    }
    /** Packed action: target hotbar slot (bits 0-7), source inventory slot
     *  (bits 8-15), delay in ticks (bits 16-23); negative means no action. */
    public static long selectObsidianRefillAction(int candidateMask, int targetMask,
                                                   int obsidianHotbarMask, float speed) {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(233, candidateMask, targetMask, obsidianHotbarMask,
                Float.floatToRawIntBits(speed));
    }
    /** Directed potion swap selected from primitive masks in Rust. */
    public static long selectPotionRefillAction(int candidateMask, int allowedEmptyHotbarMask,
                                                float speed, boolean random) {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(235, candidateMask, allowedEmptyHotbarMask, Float.floatToRawIntBits(speed),
                random ? 1L : 0L);
    }
    /** Returns item-kind flags from an opaque primitive ItemStack tuple. */
    public static int classifyItemPacked(int itemId, int metadata, int stackSize) {
        if (!requireNative()) return 0;
        return (int) d(67, itemId, metadata, stackSize);
    }
    public static boolean advanceRefillDelay() { if (!requireNative()) return false; return d(65) != 0L; }
    public static void setRefillDelay(int ticks) { if (!requireNative()) return; d(66, ticks, 0L); }

    public static boolean approveSmartPearl(double x, double y, double z,
                                            double velocityX, double velocityY, double velocityZ,
                                            double playerY, float minimumGain, int mode, long[] samples) {
        if (!requireNative()) return true;
        return perfectpathc.dev.nativecore.NativeBridge.simulateSmartPearlGateNative(
                x, y, z, velocityX, velocityY, velocityZ, playerY, minimumGain, mode, samples) != 0;
    }

    /** Starts the Rust-owned ThrowPot state machine. */
    public static void startThrowPot(float requestedCenter, int initialSlot, int potionSlot,
                                     boolean detourEnabled, boolean archon, boolean debug,
                                     long randomState, int currentTick) {
        if (!requireNative()) return;
        int flags = (detourEnabled ? 1 : 0) | (archon ? 2 : 0) | (debug ? 4 : 0);
        d(96, Float.floatToRawIntBits(requestedCenter), initialSlot, potionSlot,
                flags, randomState, currentTick);
    }

    /**
     * Advances the Rust-owned state machine and returns packed action flags
     * (low byte) plus its slot (bits 8-15). No Java scheduling state is kept.
     */
    public static long nextThrowPotAction(int currentTick, boolean canContinue,
                                          boolean splashPotionHeld) {
        if (!requireNative()) return Long.MIN_VALUE;
        return d(97, currentTick, (canContinue ? 1L : 0L) | (splashPotionHeld ? 2L : 0L));
    }

    public static boolean isThrowPotActive() {
        if (!requireNative()) return false;
        return d(98) != 0L;
    }

    public static void resetThrowPot() {
        if (nativeAvailable) d(99);
    }

    private static long d(int opcode, long first, long second, long third, long fourth,
                          long fifth, long sixth) {
        return invoke(opcode, new long[] { first, second, third, fourth, fifth, sixth });
    }

    public static void initializeEquipo() { if (!requireNative()) return; d(80); }
    public static boolean equipoContains(long mostSignificantBits, long leastSignificantBits) {
        if (!requireNative()) return false;
        return d(81, mostSignificantBits, leastSignificantBits) != 0L;
    }
    public static boolean equipoAdd(long mostSignificantBits, long leastSignificantBits) {
        if (!requireNative()) return false;
        return d(82, mostSignificantBits, leastSignificantBits) != 0L;
    }
    public static boolean equipoAdd(long mostSignificantBits, long leastSignificantBits,
                                    String name) {
        if (!requireNative()) return false;
        long firstNameWord = packPlayerName(name, 0);
        long secondNameWord = packPlayerName(name, 8);
        return d(88, mostSignificantBits, leastSignificantBits,
                firstNameWord, secondNameWord) != 0L;
    }
    public static boolean equipoRemove(long mostSignificantBits, long leastSignificantBits) {
        if (!requireNative()) return false;
        return d(83, mostSignificantBits, leastSignificantBits) != 0L;
    }
    public static int equipoCount() {
        if (!requireNative()) return 0;
        return (int) d(84);
    }
    public static long equipoMemberMost(int index) {
        if (!requireNative()) return 0L;
        return d(85, index, 0L);
    }
    public static long equipoMemberLeast(int index) {
        if (!requireNative()) return 0L;
        return d(86, index, 0L);
    }
    public static String equipoMemberName(int index) {
        if (!requireNative()) return "";
        long first = d(87, index, 0L);
        long second = d(87, index, 1L);
        StringBuilder name = new StringBuilder(16);
        unpackPlayerName(first, name);
        unpackPlayerName(second, name);
        return name.toString();
    }

    private static long packPlayerName(String name, int offset) {
        if (name == null) return 0L;
        long packed = 0L;
        int end = Math.min(name.length(), offset + 8);
        for (int index = offset; index < end; index++) {
            char value = name.charAt(index);
            if (value > 0x7F) break;
            packed |= (long) value << ((index - offset) * 8);
        }
        return packed;
    }

    private static void unpackPlayerName(long packed, StringBuilder output) {
        for (int index = 0; index < 8; index++) {
            char value = (char) ((packed >>> (index * 8)) & 0xFFL);
            if (value == 0) return;
            output.append(value);
        }
    }

    /** Soft gate after quiesce: never throw into Forge event buses. */
    private static boolean requireNative() {
        return nativeAvailable && nativeLifecycle == NATIVE_ACTIVE;
    }

    private static void loadNativeLibrary() {
        String override = System.getProperty("perfectpatch.native.path");
        if (override != null && !override.trim().isEmpty()) {
            try {
                System.load(new File(override).getAbsolutePath());
                nativeAvailable = true;
                nativeLifecycle = (int) d(2);
                return;
            } catch (Throwable throwable) {
                nativeAvailable = false;
                nativeLifecycle = 0;
                loadFailure = "Configured native library failed: "
                        + throwable.getClass().getSimpleName();
                return;
            }
        }

        String os = operatingSystem();
        String architecture = architecture();
        if (os == null || architecture == null) {
            loadFailure = "Unsupported native platform.";
            return;
        }
        String resource = "/natives/" + os + "/" + architecture + "/"
                + libraryName(os);
        InputStream input = NativeBridge.class.getResourceAsStream(resource);
        if (input == null) {
            loadFailure = "No packaged native library for " + os + "/" + architecture + ".";
            return;
        }
        try {
            File extracted = Files.createTempFile("perfectpatch-native-",
                    os.equals("windows") ? ".dll" : ".so").toFile();
            extracted.deleteOnExit();
            copy(input, extracted);
            System.load(extracted.getAbsolutePath());
            nativeAvailable = true;
            nativeLifecycle = (int) d(2);
        } catch (Throwable throwable) {
            nativeAvailable = false;
            nativeLifecycle = 0;
            loadFailure = "Native library failed: " + throwable.getClass().getSimpleName();
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // Nothing else to do for an optional native extension.
            }
        }
    }

    private static void copy(InputStream input, File destination) throws IOException {
        OutputStream output = Files.newOutputStream(destination.toPath());
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        } finally {
            output.close();
        }
    }

    private static String operatingSystem() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("win")) return "windows";
        if (name.contains("linux")) return "linux";
        return null;
    }

    private static String architecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return arch.contains("64") && (arch.contains("amd") || arch.contains("x86"))
                ? "x86_64" : null;
    }

    private static String libraryName(String os) {
        return os.equals("windows") ? "perfectpatch_native.dll"
                : "libperfectpatch_native.so";
    }
}

package perfectpathc.dev.modules.impl;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Mouse;

/** Humanized left-click automation coordinated with the other combat2 modules. */
@Mod(keybind = 0)
public final class LeftClicker extends Module {
    private static final long W_TAP_MEAN_NANOS = 75_000_000L;
    private static final long W_TAP_DEVIATION_NANOS = 18_000_000L;
    private static final long W_TAP_MIN_NANOS = 40_000_000L;
    private static final long W_TAP_MAX_NANOS = 120_000_000L;
    private static final long W_TAP_ATTACK_GUARD_NANOS = 50_000_000L;
    private static final long W_TAP_COOLDOWN_NANOS = 500_000_000L;
    private static final int W_TAP_CONFIRM_WINDOW_TICKS = 4;

    private final Settings wTapEnabled;
    private EntityLivingBase pendingWTapTarget;
    private int pendingWTapDeadline;
    private long wTapStopAtNanos;
    private long wTapRestoreAtNanos;
    private long lastWTapAtNanos;

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        if (commandId == 12) { onClientTick(); return null; }
        if (commandId == 14) { onRenderOverlay(); return null; }
        return super.invokeCommand(commandId, args);
    }

    public LeftClicker() {
        this.sl.add(new Settings(text(7, 0x5a7a5a345a335a17L,
                0x00005a095a0a5a19L, 0L).toCharArray(), this, 9.0F, 1.0F, 20.0F, 1.0F));
        this.sl.add(new Settings(text(5, 0x5a285a355a2d5a09L,
                0x0000000000005a3eL, 0L).toCharArray(), this, true));
        this.sl.add(new Settings(text(3, 0x00005a3f5a225a1bL,
                0L, 0L).toCharArray(), this, true));
        this.sl.add(new Settings(text(5, 0x5a395a355a365a18L,
                0x0000000000005a31L, 0L).toCharArray(), this, true));
        this.sl.add(new Settings(text(5, 0x5a3b5a3f5a285a18L,
                0x0000000000005a31L, 0L).toCharArray(), this, true));
        this.sl.add(new Settings(text(7, 0x5a7a5a225a3b5a17L,
                0x00005a095a0a5a19L, 0L).toCharArray(), this, 12.0F, 1.0F, 20.0F, 1.0F));
        this.sl.add(new Settings(text(8, 0x5a3b5a3d5a285a15L,
                0x5a355a395a335a34L, 0L).toCharArray(), this, false));
        this.sl.add(Settings.label("W-Tap".toCharArray(), this));
        this.wTapEnabled = new Settings("Enabled".toCharArray(), this, false);
        this.sl.add(this.wTapEnabled);
        registerSettings();
        this.name = text(12, 0x5a2e5a3c5a3f5a16L,
                0x5a335a365a195a7aL, 0x5a285a3f5a315a39L);
    }

    @Override
    public char getSort() {
        return 'l';
    }

    @Override
    public void onDisable() {
        cancelWTap(true);
        super.onDisable();
        stopClicking();
    }

    public void onRenderOverlay() {
        advanceWTap();
        // A press and its release must both be observable within a CPS period.
        // Client ticks alone are 50 ms apart, which quantizes a 15 CPS stream
        // to roughly 10 CPS. The same Forge fake-click route is used here;
        // only the scheduler cadence changes.
        advanceClicker();
    }

    public void onClientTick() {
        advanceWTap();
        confirmPendingWTap();
        advanceClicker();
    }

    private void advanceClicker() {
        if (this.minecraft.thePlayer == null || this.minecraft.theWorld == null
                || !this.minecraft.inGameHasFocus || this.minecraft.currentScreen != null) {
            stopClicking();
            return;
        }
        if (shouldKeepBreakingBlock()) {
            resetNativeSchedule();
            setAttackKey(true);
            return;
        }
        if (!heldItemAllowed()) {
            stopClicking();
            return;
        }
        Mouse.poll();
        if (!Mouse.isButtonDown(0)) {
            stopClicking();
            return;
        }
        try {
            long action = NativeBridge.nextLeftClickerActionPacked(setting(0).getValfloat(),
                    setting(5).getValfloat(), setting(6).getValBoolean(),
                    System.currentTimeMillis(), System.nanoTime(), setting(3).getValBoolean());
            if ((action & 0xFFL) == 1L) pressAttack();
            else if ((action & 0xFFL) == 2L) releaseAttack((action & (1L << 8)) != 0L);
        } catch (IllegalStateException exception) {
            setEnabled(false);
            return;
        }
    }

    private boolean shouldKeepBreakingBlock() {
        return this.minecraft.objectMouseOver != null && Mouse.isButtonDown(0)
                && setting(4).getValBoolean()
                && this.minecraft.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
    }

    private boolean heldItemAllowed() {
        boolean sword = setting(1).getValBoolean();
        boolean axe = setting(2).getValBoolean();
        if (!sword && !axe) return true;
        if (this.minecraft.thePlayer.getHeldItem() == null) return false;
        int tool = CombatMinecraftAccess.classifyHeldItem(
                this.minecraft.thePlayer.getHeldItem().getItem());
        return (sword && (tool & 1) != 0) || (axe && (tool & 2) != 0);
    }

    private void pressAttack() {
        int attackKey = this.minecraft.gameSettings.keyBindAttack.getKeyCode();
        KeyBinding.setKeyBindState(attackKey, true);
        KeyBinding.onTick(attackKey);
        // Preserve the existing input route used by the client and Forge hooks.
        // It is intentionally not replaced with a manually constructed C02.
        CombatMinecraftAccess.dispatchInput(0);
        armWTapConfirmation();
    }

    private void releaseAttack(boolean block) {
        releaseAttackState();
        if (this.minecraft.objectMouseOver != null && this.minecraft.objectMouseOver.entityHit != null
                && block) {
            int useKey = this.minecraft.gameSettings.keyBindUseItem.getKeyCode();
            KeyBinding.setKeyBindState(useKey, true);
            KeyBinding.onTick(useKey);
            CombatMinecraftAccess.dispatchInput(1);
            KeyBinding.setKeyBindState(useKey, false);
        }
    }

    private void stopClicking() {
        relinquishAttackState();
    }

    private void relinquishAttackState() {
        releaseAttackState();
        resetNativeSchedule();
        if (this.minecraft.playerController != null) this.minecraft.playerController.resetBlockRemoving();
    }

    private void releaseAttackState() {
        setAttackKey(false);
        CombatMinecraftAccess.clearPressTime(this.minecraft.gameSettings.keyBindAttack);
    }

    private void setAttackKey(boolean pressed) {
        KeyBinding.setKeyBindState(this.minecraft.gameSettings.keyBindAttack.getKeyCode(), pressed);
    }

    public void update() {
        resetNativeSchedule();
    }

    private void resetNativeSchedule() {
        if (NativeBridge.isNativeAvailable()) {
            try { NativeBridge.resetLeftClicker(); } catch (IllegalStateException ignored) { }
        }
    }

    /** Arms validation only for attacks dispatched by this module, never physical clicks. */
    private void armWTapConfirmation() {
        this.pendingWTapTarget = null;
        if (!this.wTapEnabled.getValBoolean() || this.minecraft.thePlayer == null
                || this.minecraft.objectMouseOver == null) return;
        Entity hit = this.minecraft.objectMouseOver.entityHit;
        if (!(hit instanceof EntityLivingBase) || !hit.isEntityAlive()) return;
        this.pendingWTapTarget = (EntityLivingBase) hit;
        this.pendingWTapDeadline = this.minecraft.thePlayer.ticksExisted + W_TAP_CONFIRM_WINDOW_TICKS;
        // Some integrated/single-player paths update hurtTime during dispatch itself.
        confirmPendingWTap();
    }

    /** Consumes the pending attack when its server-confirmed damage ticks arrive. */
    private void confirmPendingWTap() {
        if (this.pendingWTapTarget == null || this.minecraft.thePlayer == null) return;
        if (this.minecraft.thePlayer.ticksExisted > this.pendingWTapDeadline) {
            this.pendingWTapTarget = null;
            return;
        }
        MovingObjectPosition hit = this.minecraft.objectMouseOver;
        if (hit == null || hit.entityHit != this.pendingWTapTarget
                || !this.pendingWTapTarget.isEntityAlive()
                // Entity status can be processed after its initial hurtTime=10 tick.
                || this.pendingWTapTarget.hurtTime < 9) return;
        this.pendingWTapTarget = null;
        if (!this.wTapEnabled.getValBoolean() || !this.minecraft.thePlayer.isSprinting()
                || isInFluid()) return;
        long now = System.nanoTime();
        if (now - this.lastWTapAtNanos < W_TAP_COOLDOWN_NANOS) return;
        this.lastWTapAtNanos = now;
        // Leave the attack/movement update alone for one Minecraft tick before
        // changing sprint, so C02 is not coupled to the sprint-state transition.
        this.wTapStopAtNanos = now + W_TAP_ATTACK_GUARD_NANOS;
    }

    private void advanceWTap() {
        long now = System.nanoTime();
        if (this.wTapStopAtNanos != 0L && now >= this.wTapStopAtNanos) {
            this.wTapStopAtNanos = 0L;
            if (this.minecraft.thePlayer != null && this.minecraft.thePlayer.isSprinting()
                    && !isInFluid()) {
                this.minecraft.thePlayer.setSprinting(false);
                this.wTapRestoreAtNanos = now + nextWTapDelayNanos();
            }
        }
        if (this.wTapRestoreAtNanos == 0L || now < this.wTapRestoreAtNanos) return;
        this.wTapRestoreAtNanos = 0L;
        if (this.minecraft.thePlayer != null && !isInFluid()
                && this.minecraft.gameSettings.keyBindForward.isKeyDown()) {
            KeyBinding forward = this.minecraft.gameSettings.keyBindForward;
            KeyBinding.setKeyBindState(forward.getKeyCode(), true);
            KeyBinding.onTick(forward.getKeyCode());
            this.minecraft.thePlayer.setSprinting(true);
        }
    }

    private void cancelWTap(boolean restoreSprint) {
        this.pendingWTapTarget = null;
        this.wTapStopAtNanos = 0L;
        this.wTapRestoreAtNanos = 0L;
        if (restoreSprint && this.minecraft.thePlayer != null && !isInFluid()
                && this.minecraft.gameSettings.keyBindForward.isKeyDown()) {
            this.minecraft.thePlayer.setSprinting(true);
        }
    }

    private boolean isInFluid() {
        return this.minecraft.thePlayer != null && (this.minecraft.thePlayer.isInWater()
                || this.minecraft.thePlayer.isInLava());
    }

    private static long nextWTapDelayNanos() {
        double gaussian = Math.max(-2.0D, Math.min(2.0D,
                ThreadLocalRandom.current().nextGaussian()));
        long delay = W_TAP_MEAN_NANOS + Math.round(gaussian * W_TAP_DEVIATION_NANOS);
        return Math.max(W_TAP_MIN_NANOS, Math.min(W_TAP_MAX_NANOS, delay));
    }

    private Settings setting(int index) {
        return this.sl.get(index);
    }

    private static String text(int length, long a, long b, long c) {
        char[] output = new char[length];
        for (int index = 0; index < length; index++) {
            long packed = (index >> 2) == 0 ? a : ((index >> 2) == 1 ? b : c);
            output[index] = (char) (((packed >>> ((index & 3) << 4)) & 0xFFFFL) ^ 0x5A5AL);
        }
        return new String(output);
    }
}

package perfectpathc.dev.modules.impl;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/** Vanilla-input safety gate: Rust decides whether a sampled pearl flight earns Y. */
@Mod(keybind = 0)
public final class SmartPearlGatekeeper extends Module {
    private static final int MAX_TICKS = 100;
    private static final int SAMPLE_WORDS = 7;
    private static final float NEUTRAL_PITCH_DEGREES = 1.0F;
    private static final int LOW_PING_SAMPLES = 3;
    private static final int MEDIUM_PING_SAMPLES = 5;
    private static final int HIGH_PING_SAMPLES = 7;
    private static final int DECISION_HYSTERESIS_TICKS = 2;
    private static final double MAX_PREDICTION_TICKS = 8.0D;
    /* EntityThrowable(EntityLivingBase) spawn transform in 1.8.9. */
    private static final double VANILLA_THROW_BACK_OFFSET = 0.16D;
    private static final double VANILLA_THROW_VERTICAL_OFFSET = 0.10D;
    /* setThrowableHeading: gaussian(0, 0.0075) * inaccuracy * velocity. */
    private static final double VANILLA_THROW_INACCURACY = 0.01125D;
    /** Visual-only erosion of the impacted face; it never rejects an otherwise valid throw. */
    private static final double ROBUST_FACE_INSET = 0.15D;
    private static final double GUIDE_FACE_OFFSET = 0.002D;
    private int cachedDecisionTick = Integer.MIN_VALUE;
    private EnvelopeDecision cachedDecision;
    private int lastSafeDecisionTick = Integer.MIN_VALUE;

    public SmartPearlGatekeeper() {
        this.sl.add(new Settings("MinY".toCharArray(), this, 1.5F, 0.0F, 12.0F, 0.25F));
        this.sl.add(new Settings("Ascenso".toCharArray(), this, true));
        this.sl.add(new Settings("Descenso".toCharArray(), this, true));
        this.sl.add(new Settings("Guia".toCharArray(), this, true));
        this.sl.add(new Settings("Seguro".toCharArray(), this, false));
        this.sl.add(new Settings("Keybind".toCharArray(), this));
        registerSettings();
        this.name = "Perlas";
    }

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        if (commandId == 21) return Boolean.valueOf(shouldBlockUse());
        if (commandId == 22) { handleKeyboardUse(); return null; }
        if (commandId == 15) { onRenderWorld((RenderWorldLastEvent) args[0]); return null; }
        return super.invokeCommand(commandId, args);
    }

    private void handleKeyboardUse() {
        if (!Keyboard.getEventKeyState()) return;
        KeyBinding use = this.minecraft.gameSettings.keyBindUseItem;
        if (Keyboard.getEventKey() != use.getKeyCode() || !shouldBlockUse()) return;
        KeyBinding.setKeyBindState(use.getKeyCode(), false);
        CombatMinecraftAccess.clearPressTime(use);
    }

    private boolean shouldBlockUse() {
        EntityPlayerSP player = this.minecraft.thePlayer;
        World world = this.minecraft.theWorld;
        if (player == null || world == null || this.minecraft.currentScreen != null) return false;
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemEnderPearl)) return false;
        int mode = modeForPitch(player.rotationPitch);
        if (mode == 0) return false;
        EnvelopeDecision decision = evaluatePingEnvelope(world, player, mode);
        // Safe mode is deliberately exact: green/hysteresis may communicate a
        // viable throw, but only a blue robust envelope authorizes dispatch.
        boolean approved = setting(4).getValBoolean()
                ? decision.robust
                : applyDecisionHysteresis(player.ticksExisted, decision);
        if (approved) return false;
        player.playSound("gui.button.press", 1.0F, 0.5F);
        return true;
    }

    /** Java samples world materials/ray hits; Euler integration is replayed in Rust. */
    private PearlTrajectorySample sampleTrajectory(World world, double x, double y, double z,
                                                   double vx, double vy, double vz) {
        long[] samples = new long[SAMPLE_WORDS];
        boolean touchedWater = false;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            boolean water = MovementMinecraftAccess.isWater(world, (int)Math.floor(x),
                    (int)Math.floor(y), (int)Math.floor(z));
            touchedWater |= water;
            double drag = water ? 0.80D : 0.99D;
            double gravity = water ? 0.015D : 0.03D;
            vx *= drag; vy = vy * drag - gravity; vz *= drag;
            double nx = x + vx, ny = y + vy, nz = z + vz;
            MovingObjectPosition hit = world.rayTraceBlocks(new Vec3(x, y, z), new Vec3(nx, ny, nz), false, true, false);
            // Water changes flight physics but never counts as a descent
            // arrival. Only a solid ray hit ends the simulated flight.
            int sample = (water ? 1 : 0) | (hit != null ? 2 : 0);
            samples[tick >> 4] |= (long)sample << ((tick & 15) << 2);
            if (hit != null) {
                return new PearlTrajectorySample(samples, touchedWater, true, hit);
            }
            x = nx; y = ny; z = nz;
        }
        return new PearlTrajectorySample(samples, touchedWater, false, null);
    }

    public void onRenderWorld(RenderWorldLastEvent event) {
        EntityPlayerSP player = this.minecraft.thePlayer;
        World world = this.minecraft.theWorld;
        if (!setting(3).getValBoolean() || player == null || world == null) return;
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemEnderPearl)) return;
        int mode = modeForPitch(player.rotationPitch);
        if (mode == 0) return;
        EnvelopeDecision decision = evaluatePingEnvelope(world, player, mode);
        MovingObjectPosition impact = decision.impact;
        if (impact == null || impact.getBlockPos() == null || impact.sideHit == null) return;
        boolean approved = decision.approved && !decision.criticalWater;
        double viewX = player.prevPosX + (player.posX - player.prevPosX) * event.partialTicks;
        double viewY = player.prevPosY + (player.posY - player.prevPosY) * event.partialTicks;
        double viewZ = player.prevPosZ + (player.posZ - player.prevPosZ) * event.partialTicks;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.pushMatrix();
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glLineWidth(2.0F);
            GL11.glColor4f(approved ? 0.2F : 1.0F, approved ? 1.0F : 0.2F, 0.2F, 0.9F);
            drawFaceOutline(impact.getBlockPos(), impact.sideHit, 0.0D, viewX, viewY, viewZ);
            if (decision.robust) {
                GL11.glLineWidth(2.5F);
                GL11.glColor4f(0.2F, 0.65F, 1.0F, 1.0F);
                drawFaceOutline(impact.getBlockPos(), impact.sideHit,
                        ROBUST_FACE_INSET, viewX, viewY, viewZ);
            }
        } finally { GlStateManager.popMatrix(); GL11.glPopAttrib(); }
    }

    private MovingObjectPosition projectedImpact(World world, EntityPlayerSP player) {
        LaunchOrigin origin = vanillaLaunchOrigin(player, 0.0D);
        double x = origin.x, y = origin.y, z = origin.z;
        double vx = velocityX(player), vy = velocityY(player), vz = velocityZ(player);
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            boolean water = MovementMinecraftAccess.isWater(world, (int)Math.floor(x),
                    (int)Math.floor(y), (int)Math.floor(z));
            double drag = water ? 0.80D : 0.99D;
            vx *= drag; vy = vy * drag - (water ? 0.015D : 0.03D); vz *= drag;
            double nx = x + vx, ny = y + vy, nz = z + vz;
            MovingObjectPosition hit = world.rayTraceBlocks(new Vec3(x, y, z), new Vec3(nx, ny, nz), false, true, false);
            if (hit != null) return hit;
            x = nx; y = ny; z = nz;
        }
        return null;
    }

    private static void drawFaceOutline(BlockPos position, net.minecraft.util.EnumFacing face,
                                        double inset, double viewX, double viewY, double viewZ) {
        double x1 = position.getX() + inset - viewX;
        double y1 = position.getY() + inset - viewY;
        double z1 = position.getZ() + inset - viewZ;
        double x2 = position.getX() + 1.0D - inset - viewX;
        double y2 = position.getY() + 1.0D - inset - viewY;
        double z2 = position.getZ() + 1.0D - inset - viewZ;
        GL11.glBegin(GL11.GL_LINE_LOOP);
        switch (face) {
            case DOWN: {
                double y = position.getY() - GUIDE_FACE_OFFSET - viewY;
                GL11.glVertex3d(x1, y, z1); GL11.glVertex3d(x2, y, z1);
                GL11.glVertex3d(x2, y, z2); GL11.glVertex3d(x1, y, z2);
                break;
            }
            case UP: {
                double y = position.getY() + 1.0D + GUIDE_FACE_OFFSET - viewY;
                GL11.glVertex3d(x1, y, z1); GL11.glVertex3d(x1, y, z2);
                GL11.glVertex3d(x2, y, z2); GL11.glVertex3d(x2, y, z1);
                break;
            }
            case NORTH: {
                double z = position.getZ() - GUIDE_FACE_OFFSET - viewZ;
                GL11.glVertex3d(x1, y1, z); GL11.glVertex3d(x1, y2, z);
                GL11.glVertex3d(x2, y2, z); GL11.glVertex3d(x2, y1, z);
                break;
            }
            case SOUTH: {
                double z = position.getZ() + 1.0D + GUIDE_FACE_OFFSET - viewZ;
                GL11.glVertex3d(x1, y1, z); GL11.glVertex3d(x2, y1, z);
                GL11.glVertex3d(x2, y2, z); GL11.glVertex3d(x1, y2, z);
                break;
            }
            case WEST: {
                double x = position.getX() - GUIDE_FACE_OFFSET - viewX;
                GL11.glVertex3d(x, y1, z1); GL11.glVertex3d(x, y1, z2);
                GL11.glVertex3d(x, y2, z2); GL11.glVertex3d(x, y2, z1);
                break;
            }
            case EAST: {
                double x = position.getX() + 1.0D + GUIDE_FACE_OFFSET - viewX;
                GL11.glVertex3d(x, y1, z1); GL11.glVertex3d(x, y2, z1);
                GL11.glVertex3d(x, y2, z2); GL11.glVertex3d(x, y1, z2);
                break;
            }
        }
        GL11.glEnd();
    }

    private static double velocityX(EntityPlayerSP player) {
        double pitch = Math.toRadians(player.rotationPitch);
        return -Math.sin(Math.toRadians(player.rotationYaw)) * Math.cos(pitch) * 1.5D;
    }
    private static double velocityY(EntityPlayerSP player) { return -Math.sin(Math.toRadians(player.rotationPitch)) * 1.5D; }
    private static double velocityZ(EntityPlayerSP player) {
        return Math.cos(Math.toRadians(player.rotationYaw)) * Math.cos(Math.toRadians(player.rotationPitch)) * 1.5D;
    }
    /** Minecraft pitch is negative while looking up and positive while looking down. */
    private int modeForPitch(float pitch) {
        // Mouse sensitivity rarely produces a literal 0.0F. A small neutral
        // band keeps a horizontal pearl on the player's current plane from
        // being misclassified as a rejected ascent/descent attempt.
        if (Math.abs(pitch) <= NEUTRAL_PITCH_DEGREES) return 0;
        if (pitch < 0.0F) return setting(1).getValBoolean() ? 1 : 0;
        if (pitch > 0.0F) return setting(2).getValBoolean() ? 2 : 0;
        return 0;
    }

    @Override
    public void onDisable() {
        this.cachedDecision = null;
        this.cachedDecisionTick = Integer.MIN_VALUE;
        this.lastSafeDecisionTick = Integer.MIN_VALUE;
    }

    /**
     * Evaluates a small envelope around the delayed server view of the player.
     * It is cached for the tick, so a render pass and the use-key path never
     * replay the same trajectories twice.
     */
    private EnvelopeDecision evaluatePingEnvelope(World world, EntityPlayerSP player, int mode) {
        int tick = player.ticksExisted;
        if (this.cachedDecision != null && this.cachedDecisionTick == tick) {
            return this.cachedDecision;
        }
        int sampleCount = samplesForPing(readPingMillis(player));
        double latencyTicks = Math.min(MAX_PREDICTION_TICKS, Math.max(0.0D,
                readPingMillis(player) / 50.0D));
        int approvedSamples = 0;
        boolean criticalWater = false;
        MovingObjectPosition nominalImpact = projectedImpact(world, player);
        boolean robust = isSafeFaceImpact(nominalImpact);
        double throwX = velocityX(player);
        double throwY = velocityY(player);
        double throwZ = velocityZ(player);
        for (int index = 0; index < sampleCount; index++) {
            // Center the envelope on the present state and cover both a
            // slightly stale server position and the next receive window.
            double fraction = sampleCount == 1 ? 0.0D
                    : (double) index / (double)(sampleCount - 1) - 0.5D;
            double offsetTicks = fraction * latencyTicks;
            LaunchOrigin origin = vanillaLaunchOrigin(player, offsetTicks);
            double phase = Math.PI * 2.0D * (double)index / (double)sampleCount;
            double lateralJitter = Math.cos(phase) * VANILLA_THROW_INACCURACY;
            double verticalJitter = Math.sin(phase) * VANILLA_THROW_INACCURACY;
            double yawRadians = Math.toRadians(player.rotationYaw);
            // A compact deterministic ring represents Vanilla's otherwise
            // random heading inaccuracy without adding extra trajectories.
            double jitteredX = throwX + Math.cos(yawRadians) * lateralJitter;
            double jitteredY = throwY + verticalJitter;
            double jitteredZ = throwZ + Math.sin(yawRadians) * lateralJitter;
            PearlTrajectorySample trajectory = sampleTrajectory(world, origin.x, origin.y, origin.z,
                    jitteredX, jitteredY, jitteredZ);
            if (!sameSafeTerminal(nominalImpact, trajectory.impact)) robust = false;
            if (trajectory.touchedWater && !trajectory.solidImpact) {
                criticalWater = true;
                robust = false;
                continue;
            }
            boolean sampleApproved = NativeBridge.approveSmartPearl(origin.x, origin.y, origin.z,
                    jitteredX, jitteredY, jitteredZ,
                    player.posY + player.motionY * offsetTicks,
                    setting(0).getValfloat(), mode, trajectory.samples);
            if (sampleApproved) {
                approvedSamples++;
            } else {
                robust = false;
            }
        }
        int required = (int)Math.ceil(sampleCount * 0.70D);
        boolean approved = approvedSamples >= required;
        this.cachedDecision = new EnvelopeDecision(approved, criticalWater,
                approved && !criticalWater && robust, nominalImpact);
        this.cachedDecisionTick = tick;
        return this.cachedDecision;
    }

    private boolean applyDecisionHysteresis(int tick, EnvelopeDecision decision) {
        if (decision.criticalWater) {
            this.lastSafeDecisionTick = Integer.MIN_VALUE;
            return false;
        }
        if (decision.approved) {
            this.lastSafeDecisionTick = tick;
            return true;
        }
        return tick - this.lastSafeDecisionTick <= DECISION_HYSTERESIS_TICKS;
    }

    private int readPingMillis(EntityPlayerSP player) {
        NetworkPlayerInfo info = this.minecraft.getNetHandler() == null ? null
                : this.minecraft.getNetHandler().getPlayerInfo(player.getUniqueID());
        return info == null ? 0 : Math.max(0, info.getResponseTime());
    }

    private static int samplesForPing(int pingMillis) {
        if (pingMillis < 90) return LOW_PING_SAMPLES;
        if (pingMillis < 180) return MEDIUM_PING_SAMPLES;
        return HIGH_PING_SAMPLES;
    }

    private static LaunchOrigin vanillaLaunchOrigin(EntityPlayerSP player, double offsetTicks) {
        double yawRadians = Math.toRadians(player.rotationYaw);
        return new LaunchOrigin(
                player.posX + player.motionX * offsetTicks
                        - Math.cos(yawRadians) * VANILLA_THROW_BACK_OFFSET,
                player.posY + player.getEyeHeight() + player.motionY * offsetTicks
                        - VANILLA_THROW_VERTICAL_OFFSET,
                player.posZ + player.motionZ * offsetTicks
                        - Math.sin(yawRadians) * VANILLA_THROW_BACK_OFFSET);
    }

    private static final class LaunchOrigin {
        private final double x;
        private final double y;
        private final double z;

        private LaunchOrigin(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class PearlTrajectorySample {
        private final long[] samples;
        private final boolean touchedWater;
        private final boolean solidImpact;
        private final MovingObjectPosition impact;

        private PearlTrajectorySample(long[] samples, boolean touchedWater, boolean solidImpact,
                                      MovingObjectPosition impact) {
            this.samples = samples;
            this.touchedWater = touchedWater;
            this.solidImpact = solidImpact;
            this.impact = impact;
        }
    }

    private static final class EnvelopeDecision {
        private final boolean approved;
        private final boolean criticalWater;
        private final boolean robust;
        private final MovingObjectPosition impact;

        private EnvelopeDecision(boolean approved, boolean criticalWater,
                                 boolean robust, MovingObjectPosition impact) {
            this.approved = approved;
            this.criticalWater = criticalWater;
            this.robust = robust;
            this.impact = impact;
        }
    }

    private static boolean sameSafeTerminal(MovingObjectPosition expected,
                                            MovingObjectPosition actual) {
        return isSafeFaceImpact(expected) && isSafeFaceImpact(actual)
                && expected.sideHit == actual.sideHit
                && expected.getBlockPos().equals(actual.getBlockPos());
    }

    private static boolean isSafeFaceImpact(MovingObjectPosition impact) {
        if (impact == null || impact.getBlockPos() == null
                || impact.sideHit == null || impact.hitVec == null) return false;
        BlockPos block = impact.getBlockPos();
        double localX = impact.hitVec.xCoord - block.getX();
        double localY = impact.hitVec.yCoord - block.getY();
        double localZ = impact.hitVec.zCoord - block.getZ();
        switch (impact.sideHit) {
            case DOWN:
            case UP:
                return insideSafeAxis(localX) && insideSafeAxis(localZ);
            case NORTH:
            case SOUTH:
                return insideSafeAxis(localX) && insideSafeAxis(localY);
            case WEST:
            case EAST:
                return insideSafeAxis(localZ) && insideSafeAxis(localY);
            default:
                return false;
        }
    }

    private static boolean insideSafeAxis(double value) {
        return value >= ROBUST_FACE_INSET && value <= 1.0D - ROBUST_FACE_INSET;
    }

    private Settings setting(int index) { return this.sl.get(index); }
    @Override public char getSort() { return 'p'; }
}

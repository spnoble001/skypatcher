package perfectpathc.dev.modules.impl;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.util.MovementInput;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import perfectpathc.dev.modules.impl.events.XenonEvent;
import perfectpathc.dev.modules.runtime.IModuleEntry;

/** Java-only MVP for the vertical-lane Océano architecture. */
@Mod(keybind = 0)
public final class OceanoV2 extends Module implements IModuleEntry {
    private static final int SETTING_VERTICAL_LOSS = 0;
    private static final int SETTING_SAFETY_MARGIN = 1;
    private static final int SOFT_Y_MEMORY_TICKS = 20;
    private static final int WATER_SCAN_RADIUS = 2;
    private static final int WATER_SCAN_DEPTH = 5;
    private static final int VERTICAL_LOOKAHEAD_TICKS = 10;
    private static final int MAX_CONTACT_HORIZON_TICKS = 5;
    private static final double MIN_SPEED = 0.0001D;
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98D;
    private static final double HORIZONTAL_AIR_DRAG = 0.91D;
    private static final double PLAYER_RADIUS = 0.30D;
    private static final double INPUT_DEAD_ZONE = 0.35D;
    private static final double VANILLA_AIR_ACCELERATION = 0.02D;
    private static final int[][] DISCRETE_INPUTS = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1}, {0, 0}, {0, 1},
            {1, -1}, {1, 0}, {1, 1}
    };

    private double referenceY = Double.NaN;
    private int referenceTick = Integer.MIN_VALUE;
    /** Decision produced before living update and consumed immediately after Vanilla reads keys. */
    private Candidate pendingCandidate;
    private int pendingCandidateTick = Integer.MIN_VALUE;
    private ControlledMovementInput controlledInput;

    public OceanoV2() {
        this.name = "Oceano V2";
        this.sl.add(new Settings("Perdida Y maxima".toCharArray(), this,
                4.25F, 0.50F, 5.00F, 0.25F));
        this.sl.add(new Settings("Margen de tubo".toCharArray(), this,
                0.05F, 0.00F, 0.15F, 0.01F));
        this.sl.add(new Settings("Tecla".toCharArray(), this));
        registerSettings();
    }

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        if (commandId == 17) {
            onPlayerTick((TickEvent.PlayerTickEvent) args[0]);
            return null;
        }
        return super.invokeCommand(commandId, args);
    }

    @Override
    public void onEnable() {
        this.referenceY = Double.NaN;
        this.referenceTick = Integer.MIN_VALUE;
        clearPendingCandidate();
    }

    @Override
    public void onDisable() {
        this.referenceY = Double.NaN;
        this.referenceTick = Integer.MIN_VALUE;
        clearPendingCandidate();
        restoreVanillaMovementInput();
    }

    @XenonEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!this.enabled || event.phase != TickEvent.Phase.START) return;
        EntityPlayerSP player = event.player instanceof EntityPlayerSP
                ? (EntityPlayerSP) event.player : null;
        World world = this.minecraft.theWorld;
        if (player == null || world == null) {
            clearPendingCandidate();
            return;
        }
        installInputHook(player);
        if (player.isInWater() || player.onGround) {
            this.referenceY = player.posY;
            this.referenceTick = player.ticksExisted;
            clearPendingCandidate();
            return;
        }
        if (player.capabilities.isFlying || player.isOnLadder() || player.isSneaking()
                || player.motionY >= 0.0D || player.fallDistance <= 0.5F) {
            clearPendingCandidate();
            return;
        }
        if (this.referenceTick == Integer.MIN_VALUE
                || player.ticksExisted - this.referenceTick > SOFT_Y_MEMORY_TICKS) {
            clearPendingCandidate();
            return;
        }
        Candidate candidate = selectCandidate(player, world);
        if (candidate == null || candidate.natural) {
            clearPendingCandidate();
            return;
        }
        this.pendingCandidate = candidate;
        this.pendingCandidateTick = player.ticksExisted;
    }

    private Candidate selectCandidate(EntityPlayerSP player, World world) {
        double verticalFloor = this.referenceY - setting(SETTING_VERTICAL_LOSS).getValfloat();
        double margin = setting(SETTING_SAFETY_MARGIN).getValfloat();
        float humanForward = quantizeInput(player.movementInput.moveForward);
        float humanStrafe = quantizeInput(player.movementInput.moveStrafe);
        double airAcceleration = airAcceleration(player);
        int baseX = (int) Math.floor(player.posX);
        int baseY = (int) Math.floor(player.posY);
        int baseZ = (int) Math.floor(player.posZ);
        Candidate best = null;
        for (int x = -WATER_SCAN_RADIUS; x <= WATER_SCAN_RADIUS; x++) {
            for (int z = -WATER_SCAN_RADIUS; z <= WATER_SCAN_RADIUS; z++) {
                for (int drop = 0; drop <= WATER_SCAN_DEPTH; drop++) {
                    int waterY = baseY - drop;
                    if (!MovementMinecraftAccess.isWater(world, baseX + x, waterY, baseZ + z)) continue;
                    double waterTop = waterY + 1.0D;
                    if (waterTop < verticalFloor) break;
                    int contactTick = ticksToWaterTop(player.posY, player.motionY, waterTop);
                    if (contactTick < 0) continue;
                    int horizon = Math.min(contactTick, MAX_CONTACT_HORIZON_TICKS);
                    double centerX = baseX + x + 0.5D;
                    double centerZ = baseZ + z + 0.5D;
                    double safeHalfWidth = 0.5D - PLAYER_RADIUS - margin;
                    Candidate natural = simulateInput(player, centerX, centerZ, waterTop, horizon,
                            safeHalfWidth, humanForward, humanStrafe, humanForward, humanStrafe,
                            airAcceleration, true);
                    // The player already reaches a viable water tube. Preserve
                    // that intent rather than competing it against corrections.
                    if (natural.isSafe()) return natural;
                    for (int[] action : DISCRETE_INPUTS) {
                        Candidate next = simulateInput(player, centerX, centerZ, waterTop, horizon,
                                safeHalfWidth, action[0], action[1], humanForward, humanStrafe,
                                airAcceleration, false);
                        if (best == null || next.isBetterThan(best)) best = next;
                    }
                    break;
                }
            }
        }
        return best;
    }

    private Candidate simulateInput(EntityPlayerSP player, double centerX, double centerZ,
                                    double waterTop, int horizon, double safeHalfWidth,
                                    float forward, float strafe, float humanForward, float humanStrafe,
                                    double airAcceleration, boolean natural) {
        double length = Math.sqrt(forward * forward + strafe * strafe);
        double scale = length > 1.0D ? 1.0D / length : 1.0D;
        double yaw = Math.toRadians(player.rotationYaw);
        double accelerationX = (-forward * Math.sin(yaw) + strafe * Math.cos(yaw))
                * airAcceleration * scale;
        double accelerationZ = (forward * Math.cos(yaw) + strafe * Math.sin(yaw))
                * airAcceleration * scale;
        double projectedX = player.posX;
        double projectedZ = player.posZ;
        double motionX = player.motionX;
        double motionZ = player.motionZ;
        for (int tick = 0; tick < horizon; tick++) {
            motionX += accelerationX;
            motionZ += accelerationZ;
            projectedX += motionX;
            projectedZ += motionZ;
            motionX *= HORIZONTAL_AIR_DRAG;
            motionZ *= HORIZONTAL_AIR_DRAG;
        }
        double lateralMargin = Math.min(safeHalfWidth - Math.abs(projectedX - centerX),
                safeHalfWidth - Math.abs(projectedZ - centerZ));
        double verticalLoss = Math.max(0.0D, this.referenceY - waterTop);
        double inputDeviation = Math.abs(forward - humanForward) + Math.abs(strafe - humanStrafe);
        return new Candidate(centerX, centerZ, waterTop, horizon, forward, strafe,
                verticalLoss, lateralMargin, inputDeviation, natural);
    }

    private static double airAcceleration(EntityPlayerSP player) {
        double base = player.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getBaseValue();
        double current = player.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getAttributeValue();
        double scale = base > MIN_SPEED ? current / base : 1.0D;
        return VANILLA_AIR_ACCELERATION * Math.max(0.25D, Math.min(3.0D, scale));
    }

    /** Runs after MovementInputFromOptions has copied physical keyboard state for this tick. */
    private void applyOneTickInput(EntityPlayerSP player, MovementInput input, Candidate candidate) {
        input.moveForward = candidate.forward;
        input.moveStrafe = candidate.strafe;
    }

    private void installInputHook(EntityPlayerSP player) {
        if (player.movementInput == this.controlledInput) return;
        if (player.movementInput == null) return;
        this.controlledInput = new ControlledMovementInput(player.movementInput);
        player.movementInput = this.controlledInput;
    }

    private void restoreVanillaMovementInput() {
        EntityPlayerSP player = this.minecraft == null ? null : this.minecraft.thePlayer;
        if (player != null && this.controlledInput != null && player.movementInput == this.controlledInput) {
            player.movementInput = this.controlledInput.delegate;
        }
        this.controlledInput = null;
    }

    private void clearPendingCandidate() {
        this.pendingCandidate = null;
        this.pendingCandidateTick = Integer.MIN_VALUE;
    }

    private static int ticksToWaterTop(double y, double motionY, double waterTop) {
        double simulatedY = y;
        double simulatedMotionY = motionY;
        for (int tick = 1; tick <= VERTICAL_LOOKAHEAD_TICKS; tick++) {
            simulatedY += simulatedMotionY;
            if (simulatedY <= waterTop) return tick;
            simulatedMotionY = (simulatedMotionY - GRAVITY) * VERTICAL_DRAG;
        }
        return -1;
    }

    private static float quantizeInput(double value) {
        return value > INPUT_DEAD_ZONE ? 1.0F : value < -INPUT_DEAD_ZONE ? -1.0F : 0.0F;
    }

    private Settings setting(int index) {
        return this.sl.get(index);
    }

    @Override
    public char getSort() {
        return 'n';
    }

    /**
     * Keeps Vanilla as the input owner. Its state is copied first; V2 can only
     * replace it for the one validated recovery tick prepared by onPlayerTick.
     */
    private final class ControlledMovementInput extends MovementInput {
        private final MovementInput delegate;

        private ControlledMovementInput(MovementInput delegate) {
            this.delegate = delegate;
        }

        @Override
        public void updatePlayerMoveState() {
            this.delegate.updatePlayerMoveState();
            this.moveStrafe = this.delegate.moveStrafe;
            this.moveForward = this.delegate.moveForward;
            this.jump = this.delegate.jump;
            this.sneak = this.delegate.sneak;

            EntityPlayerSP player = OceanoV2.this.minecraft.thePlayer;
            if (!OceanoV2.this.enabled || player == null
                    || OceanoV2.this.pendingCandidate == null
                    || OceanoV2.this.pendingCandidateTick != player.ticksExisted) return;
            Candidate candidate = OceanoV2.this.pendingCandidate;
            OceanoV2.this.clearPendingCandidate();
            OceanoV2.this.applyOneTickInput(player, this, candidate);
        }
    }

    private static final class Candidate {
        private final double centerX;
        private final double centerZ;
        private final double waterTop;
        private final int horizon;
        private final float forward;
        private final float strafe;
        private final double verticalLoss;
        private final double lateralMargin;
        private final double inputDeviation;
        private final boolean natural;

        private Candidate(double centerX, double centerZ, double waterTop, int horizon,
                          float forward, float strafe, double verticalLoss,
                          double lateralMargin, double inputDeviation, boolean natural) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.waterTop = waterTop;
            this.horizon = horizon;
            this.forward = forward;
            this.strafe = strafe;
            this.verticalLoss = verticalLoss;
            this.lateralMargin = lateralMargin;
            this.inputDeviation = inputDeviation;
            this.natural = natural;
        }

        private boolean isSafe() { return this.lateralMargin >= 0.0D; }

        private boolean isBetterThan(Candidate other) {
            // No scalar cost: survival and height are hard, ordered criteria.
            if (this.isSafe() != other.isSafe()) return this.isSafe();
            if (this.verticalLoss != other.verticalLoss) return this.verticalLoss < other.verticalLoss;
            if (this.lateralMargin != other.lateralMargin) return this.lateralMargin > other.lateralMargin;
            if (this.inputDeviation != other.inputDeviation) return this.inputDeviation < other.inputDeviation;
            return this.horizon < other.horizon;
        }
    }
}

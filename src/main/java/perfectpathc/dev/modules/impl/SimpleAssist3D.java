package perfectpathc.dev.modules.impl;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import perfectpathc.dev.modules.impl.events.XenonEvent;
import perfectpathc.dev.modules.impl.simpleassist.SpatialAssistanceEngine;
import perfectpathc.dev.modules.impl.simpleassist.StateDrivenFriction;
import perfectpathc.dev.modules.impl.utils.RightClickCoordinator;
import perfectpathc.dev.modules.impl.utils.GlobalCps;
import perfectpathc.dev.modules.impl.utils.GlobalReducedKb;
import perfectpathc.dev.modules.impl.utils.GlobalSpatialAssistance;
import perfectpathc.dev.modules.impl.utils.GlobalNaturalMode;
import perfectpathc.dev.modules.impl.utils.GlobalRenderHelp;
import perfectpathc.dev.modules.impl.utils.SimpleAssist3DRoute;
import perfectpathc.dev.modules.impl.utils.AnchorQuarantineRegistry;
import perfectpathc.dev.modules.runtime.IModuleEntry;

/**
 * Independent 3D PatchCrumbs executor.  It does not read or mutate the
 * terminal-to-origin state owned by SimpleAssist; its reactive cursor, pending state,
 * confirmation timeout and placement quota are entirely local.
 */
@Mod(keybind = 0)
public final class SimpleAssist3D extends Module implements RightClickCoordinator.Owner, IModuleEntry {
    private static final int INPUT_PRIORITY = 34;
    private static final int PENDING_TIMEOUT_TICKS = 80;
    private static final int ROUTE_WATCHDOG_TIMEOUT_TICKS = 80;
    private static final int REQUEST_MISS_LIMIT = 3;
    private static final int MAX_CHAINED_PENDING = 2;
    private static final double WEIGHT_META_PROGRESS = 40.0D;
    private static final double WEIGHT_PREDICTED_REACH = 1.5D;
    private static final double WEIGHT_ANGLE = 3.0D;
    private static final double WEIGHT_BEHIND_MOTION = 5.0D;
    private static final double WEIGHT_DEPTH_LEAD = 25.0D;
    private static final double WEIGHT_TRAIL_CONTINUITY = 2.0D;
    private static final int DEPTH_LEAD_BLOCKS = 2;
    private static final int CANDIDATE_STICKY_TICKS = 2;
    /** Lower score wins; a challenger must improve this much to replace a valid intent. */
    private static final double CANDIDATE_REPLACE_RATIO = 0.85D;
    private static final double NATURAL_REPLACE_RATIO = 0.80D;
    private static final float NATURAL_REARM_ANGLE = 16.0F;
    private static final int NATURAL_SESSION_ARMED = 0;
    private static final int NATURAL_SESSION_DISPATCHED = 1;
    private static final int NATURAL_SESSION_SPENT = 2;
    private static final Field RIGHT_CLICK_DELAY_TIMER = ReflectionHelper.findField(
            Minecraft.class, "rightClickDelayTimer", "field_71467_ac");
    private static final Field PRESS_TIME = ReflectionHelper.findField(
            KeyBinding.class, "pressTime", "field_151474_i");

    private final SpatialAssistanceEngine spatial = new SpatialAssistanceEngine();
    /** Packet-only S12/C0B synchronization; it never changes local movement. */
    private final StateDrivenFriction stateDrivenFriction = StateDrivenFriction.shared();
    private boolean stateFrictionRegistered;
    private boolean cursorActive;
    private BlockPos lastConfirmed;
    private BlockPos goalCrumb;
    private boolean goalNorthSouth;
    private long capturedCrumbRevision = -1L;
    /** Last dispatched reactive cell, retained only until local confirmation. */
    private long previewTarget = Long.MIN_VALUE;
    private int depthAnchor;
    /** Confirmed consecutive advances on the same normal; depth is a two-node manoeuvre. */
    private int depthRun;
    private int depthRunSign;
    private String lastSelectionReason = "idle";
    private ReactiveCandidate retainedCandidate;
    private final long[] pendingTargets = { Long.MIN_VALUE, Long.MIN_VALUE };
    private final int[] pendingBlockIds = { -1, -1 };
    private final int[] pendingSinceTicks = new int[MAX_CHAINED_PENDING];
    private int pendingCount;
    private int requestMisses;
    private int lastProgressTick = Integer.MIN_VALUE;
    private float credits = 1.0F;
    private int creditTick = Integer.MIN_VALUE;
    private int naturalSession = NATURAL_SESSION_ARMED;
    private boolean naturalLastRightDown;
    private int naturalNoHitTicks;
    private long naturalSpentActivation = Long.MIN_VALUE;
    private float naturalSpentYaw;
    private float naturalSpentPitch;
    private Vec3 naturalLastDispatchDirection;

    public SimpleAssist3D() {
        registerSettings();
        this.name = "Simple Patch 3D";
    }

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        if (commandId == 11) {
            onClientTick((TickEvent.ClientTickEvent)args[0]);
            return null;
        }
        if (commandId == 15) {
            onRenderWorld((RenderWorldLastEvent)args[0]);
            return null;
        }
        // Read-only arbitration probe used by the 1D PrePatch executor.
        // PrePatch yields only to a real route that can dispatch now.
        if (commandId == 22) {
            return Boolean.valueOf(hasRealPatchRoute());
        }
        if (commandId == 23) return this.lastSelectionReason;
        // Parent-owned global Spatial Assistance changed.  Do not carry an
        // old token window across the new network policy.
        if (commandId == 21) {
            this.spatial.reset();
            return null;
        }
        return super.invokeCommand(commandId, args);
    }

    @Override public void onEnable() {
        reset();
        resetNaturalSession();
        syncStateFrictionRegistration();
    }
    @Override public void onDisable() {
        unregisterStateFriction();
        reset();
        resetNaturalSession();
    }

    @Override public void onSettingsChanged() {
        if (!this.enabled) return;
        syncStateFrictionRegistration();
        if (!isNatural()) resetNaturalSession();
    }

    @XenonEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!this.enabled || !hasWorld()) return;
        if (event.phase == TickEvent.Phase.START) {
            syncStateFrictionRegistration();
            AnchorQuarantineRegistry.shared().observe(this.minecraft.theWorld,
                    this.minecraft.thePlayer.ticksExisted);
            if (this.stateFrictionRegistered) {
                this.stateDrivenFriction.onTickStart(this.minecraft);
            }
            this.spatial.onPreMotionUpdate();
            refillCredits();
            reserveInputIfNeeded();
            try {
                run();
            } catch (RuntimeException exception) {
                reset();
            }
            return;
        }
        if (this.stateFrictionRegistered) {
            this.stateDrivenFriction.onTickEnd(this.minecraft);
        }
    }

    private void run() {
        if (!isPatchBlockHeld()) {
            reset();
            resetNaturalSession();
            return;
        }
        updateNaturalSession();
        if ((this.cursorActive || hasPending()) && !isGoalStillValid()) {
            // A C08 already in flight must still receive its confirmation/
            // timeout window; only new work is forbidden after its objective
            // has expired.
            if (hasPending()) {
                confirmPending();
                if (hasPending()) return;
            }
            reset();
            return;
        }
        if (watchdogExpired()) {
            if (isNatural()) markNaturalSpent();
            reset();
            return;
        }
        confirmPending();
        if (!this.cursorActive) {
            if (isNatural() && this.naturalSession == NATURAL_SESSION_SPENT) {
                releaseInput();
                return;
            }
            captureCursor();
        }
        if (!this.cursorActive) {
            releaseInput();
            return;
        }
        if (isGoalReached() && !hasPending()) {
            if (isNatural()) markNaturalSpent();
            reset();
            return;
        }
        if (this.pendingCount >= MAX_CHAINED_PENDING) return;
        if (!hasCredit()) return;
        ReactiveCandidate candidate = selectReactiveCandidate();
        if (!this.cursorActive) return;
        if (candidate == null) {
            abortAfterMiss();
            return;
        }
        if (!RightClickCoordinator.acquire(this, INPUT_PRIORITY)) return;
        suppressPhysicalRightClick();
        dispatchReactiveWindow(candidate);
    }

    /** Observes the current reactive target without mutating route selection. */
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!this.enabled || !GlobalRenderHelp.isEnabled()
                || this.minecraft.thePlayer == null || this.minecraft.theWorld == null
                || this.previewTarget == Long.MIN_VALUE) return;
        BlockPos target = BlockPos.fromLong(this.previewTarget);
        double viewX = this.minecraft.getRenderManager().viewerPosX;
        double viewY = this.minecraft.getRenderManager().viewerPosY;
        double viewZ = this.minecraft.getRenderManager().viewerPosZ;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.pushMatrix();
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glLineWidth(3.0F);
            GL11.glColor4f(1.0F, 0.85F, 0.0F, 1.0F);
            drawPreviewBox(target.getX() - viewX, target.getY() - viewY, target.getZ() - viewZ,
                    target.getX() + 1.0D - viewX, target.getY() + 1.0D - viewY,
                    target.getZ() + 1.0D - viewZ);
        } finally {
            GlStateManager.popMatrix();
            GL11.glPopAttrib();
        }
    }

    private void captureCursor() {
        MovingObjectPosition hit = activationHit();
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null) return;
        BlockPos anchor = hit.getBlockPos();
        int snapshot = SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, anchor);
        if (!isAccepted(snapshot)) return;

        // PrePatch already owns a linear terminal-to-origin contract and is
        // always executed by SimpleAssist 1D. A real crumb, however, admits
        // every accepted anchor in its guide band; restricting this to a
        // scanned terminal destroys the reactive 3D route surface.
        long crumbPacked = NativeBridge.patchCrumbPackedPos(System.currentTimeMillis());
        if (crumbPacked == Long.MIN_VALUE) return;
        this.goalCrumb = BlockPos.fromLong(crumbPacked);
        this.goalNorthSouth = (NativeBridge.patchCrumbFlags() & (1 << 2)) != 0;
        this.capturedCrumbRevision = NativeBridge.patchCrumbRevision();
        if (!SimpleAssist3DRoute.isWithinGuideBand(anchor, this.goalCrumb, this.goalNorthSouth)) return;
        this.lastConfirmed = anchor;
        this.cursorActive = true;
        this.depthAnchor = depthCoordinate(anchor);
        this.requestMisses = 0;
        this.lastProgressTick = this.minecraft.thePlayer.ticksExisted;
    }

    /**
     * A PatchCrumbs revision is not necessarily a new objective: native code
     * may refresh its internal view while keeping the same goal plane. Keep a
     * live cursor only for that geometrically equivalent case.
     */
    private boolean isGoalStillValid() {
        if (this.goalCrumb == null) return false;
        long currentRevision = NativeBridge.patchCrumbRevision();
        if (currentRevision == this.capturedCrumbRevision) return true;

        long freshPacked = NativeBridge.patchCrumbPackedPos(System.currentTimeMillis());
        boolean freshNorthSouth = (NativeBridge.patchCrumbFlags() & (1 << 2)) != 0;
        this.capturedCrumbRevision = currentRevision;
        if (freshPacked == Long.MIN_VALUE) return false;

        BlockPos fresh = BlockPos.fromLong(freshPacked);
        boolean samePlane = freshNorthSouth == this.goalNorthSouth
                && fresh.getY() == this.goalCrumb.getY()
                && (freshNorthSouth ? fresh.getX() == this.goalCrumb.getX()
                : fresh.getZ() == this.goalCrumb.getZ());
        if (!samePlane) return false;

        // Preserve the cursor/pending window while adopting the fresh native
        // representation of the same plane.
        this.goalCrumb = fresh;
        this.goalNorthSouth = freshNorthSouth;
        return true;
    }

    private MovingObjectPosition activationHit() {
        if (!GlobalSpatialAssistance.isEnabled()) return this.minecraft.objectMouseOver;
        return SpatialAssistanceEngine.getActivationHit(this.minecraft,
                SpatialAssistanceEngine.VIRTUAL_CONE_RADIUS,
                new SpatialAssistanceEngine.TargetFilter() {
                    @Override public boolean matches(BlockPos anchor, EnumFacing side) {
                        return isAccepted(SimpleAssistMinecraftAccess.getBlockSnapshot(
                                SimpleAssist3D.this.minecraft.theWorld, anchor));
                    }
                });
    }

    private boolean hasRealPatchRoute() {
        if (!this.enabled || !hasWorld() || !isPatchBlockHeld()) return false;
        if (this.cursorActive || hasPending()) {
            BlockPos base = this.pendingCount == 0 ? this.lastConfirmed
                    : BlockPos.fromLong(this.pendingTargets[this.pendingCount - 1]);
            return base != null && findBestReactiveCandidate(base, Long.MIN_VALUE) != null;
        }

        MovingObjectPosition hit = activationHit();
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null) return false;
        BlockPos anchor = hit.getBlockPos();
        if (!isAccepted(SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, anchor))) {
            return false;
        }
        long crumbPacked = NativeBridge.patchCrumbPackedPos(System.currentTimeMillis());
        if (crumbPacked == Long.MIN_VALUE) return false;
        BlockPos crumb = BlockPos.fromLong(crumbPacked);
        boolean northSouth = (NativeBridge.patchCrumbFlags() & (1 << 2)) != 0;
        if (!SimpleAssist3DRoute.isWithinGuideBand(anchor, crumb, northSouth)) return false;
        return hasExecutableCandidate(anchor, crumb, northSouth);
    }

    /** Probes the unchanged reactive selector without capturing or reserving input. */
    private boolean hasExecutableCandidate(BlockPos base, BlockPos crumb, boolean northSouth) {
        BlockPos savedGoal = this.goalCrumb;
        boolean savedNorthSouth = this.goalNorthSouth;
        int savedDepthAnchor = this.depthAnchor;
        try {
            this.goalCrumb = crumb;
            this.goalNorthSouth = northSouth;
            this.depthAnchor = depthCoordinate(base);
            return findBestReactiveCandidate(base, Long.MIN_VALUE) != null;
        } finally {
            this.goalCrumb = savedGoal;
            this.goalNorthSouth = savedNorthSouth;
            this.depthAnchor = savedDepthAnchor;
        }
    }

    /** Keeps a physically revalidated intent for a short window. */
    private ReactiveCandidate selectReactiveCandidate() {
        BlockPos base = this.pendingCount == 0 ? this.lastConfirmed
                : BlockPos.fromLong(this.pendingTargets[this.pendingCount - 1]);
        if (base == null) return null;

        ReactiveCandidate challenger = findBestReactiveCandidate(base, Long.MIN_VALUE);
        if (challenger != null && BlockPos.fromLong(challenger.target).getY() == this.goalCrumb.getY()) {
            this.lastSelectionReason = "direct-plane";
            this.retainedCandidate = challenger;
            return challenger;
        }

        ReactiveCandidate retained = revalidateRetainedCandidate(base);
        int tick = this.minecraft.thePlayer.ticksExisted;
        if (retained != null && tick - retained.createdTick < CANDIDATE_STICKY_TICKS) {
            this.lastSelectionReason = "retained";
            this.retainedCandidate = retained;
            return retained;
        }
        double replaceRatio = isNatural() ? NATURAL_REPLACE_RATIO : CANDIDATE_REPLACE_RATIO;
        if (retained != null && (challenger == null
                || challenger.score >= retained.score * replaceRatio)) {
            this.lastSelectionReason = "hysteresis";
            this.retainedCandidate = retained;
            return retained;
        }
        this.retainedCandidate = challenger;
        this.lastSelectionReason = challenger == null ? "no-physical-candidate" : "maneuver";
        return challenger;
    }

    private ReactiveCandidate revalidateRetainedCandidate(BlockPos base) {
        if (this.retainedCandidate == null || this.retainedCandidate.base != base.toLong()) return null;
        return findBestReactiveCandidate(base, this.retainedCandidate.target);
    }

    /** Chooses a live neighbour, optionally restricting evaluation to one retained target. */
    private ReactiveCandidate findBestReactiveCandidate(BlockPos base, long requiredTarget) {
        double currentDistance = goalDistance(base);
        double currentDepthCost = depthLeadCost(base);
        Vec3 eye = currentEyePosition();
        Vec3 predictedEye = predictedEyePosition(eye);
        Vec3 look = this.minecraft.thePlayer.getLookVec();
        boolean directPlaneAvailable = hasDirectPlaneCandidate(base, requiredTarget,
                currentDistance, currentDepthCost);
        ReactiveCandidate best = null;
        double bestScore = Double.MAX_VALUE;
        ReactiveCandidate bestExactPlane = null;
        double bestExactPlaneScore = Double.MAX_VALUE;
        ReactiveCandidate soleDirect = null;
        int directCount = 0;
        for (EnumFacing step : EnumFacing.VALUES) {
            boolean depthStep = isDepthFacing(step);
            if (depthStep && this.depthRun >= 2
                    && Integer.signum(step.getFrontOffsetX() + step.getFrontOffsetZ()) == this.depthRunSign) continue;
            BlockPos target = depthStep ? base.offset(step, DEPTH_LEAD_BLOCKS) : base.offset(step);
            if (requiredTarget != Long.MIN_VALUE && target.toLong() != requiredTarget) continue;
            if (directPlaneAvailable && (depthStep || target.getY() != this.goalCrumb.getY())) continue;
            if (!SimpleAssist3DRoute.isWithinGuideBand(target, this.goalCrumb, this.goalNorthSouth)) continue;
            if (!isReplaceable(SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, target))) continue;
            double progress = goalDistance(target);
            double depthCost = depthLeadCost(target);
            if (!(progress < currentDistance || progress == currentDistance && depthCost < currentDepthCost)) continue;
            if (!hasValidDepthParity(target)) continue;
            boolean horizontal = target.getY() == base.getY() && !depthStep;
            EnumFacing sidePolicy = horizontal ? allowedGuideSide() : null;
            if (horizontal && (sidePolicy == null || sidePolicy != step)) continue;
            BlockPos queuedSupport = this.pendingCount == 0 ? null : base;
            SpatialAssistanceEngine.TargetPlacement placement = SpatialAssistanceEngine.findExecutionPlacement(
                    this.minecraft, target, this.minecraft.playerController.getBlockReachDistance(),
                    depthStep ? null : queuedSupport, GlobalSpatialAssistance.isEnabled(),
                    depthStep ? null : step);
            if (placement == null) continue;
            if (depthStep && goalDistance(target) != 0.0D && !hasForwardExit(target)) continue;
            Vec3 hit = placement.getHitVector();
            // The engine has already approved the real eye position. Lead
            // targeting influences only preference, never authorization.
            double predictedReach = predictedEye.squareDistanceTo(hit);
            double angular = angularDelta(eye, look, hit);
            double behindPenalty = behindMotionPenalty(hit);
            double score = progress * WEIGHT_META_PROGRESS
                    + depthCost * WEIGHT_DEPTH_LEAD
                    + predictedReach * WEIGHT_PREDICTED_REACH
                    + angular * WEIGHT_ANGLE + behindPenalty
                    + naturalTrailPenalty(eye, hit);
            if (score < bestScore) {
                bestScore = score;
                best = new ReactiveCandidate(base.toLong(), target.toLong(), placement, score,
                        this.minecraft.thePlayer.ticksExisted);
            }
            // A physically executable closure on the crumb's actual plane
            // dominates the ±1 vertical rescue band.  The latter remains
            // available only when no direct plane placement exists.
            if (target.getY() == this.goalCrumb.getY() && score < bestExactPlaneScore) {
                bestExactPlaneScore = score;
                bestExactPlane = new ReactiveCandidate(base.toLong(), target.toLong(), placement, score,
                        this.minecraft.thePlayer.ticksExisted);
            }
            // Degenerate 1D case: one exact-plane continuation means there
            // is no spatial decision to optimise. Preserve the deterministic
            // direct behaviour and reserve reactive scoring for a real fork.
            if (!depthStep && target.getY() == this.goalCrumb.getY()) {
                directCount++;
                soleDirect = new ReactiveCandidate(base.toLong(), target.toLong(), placement, score,
                        this.minecraft.thePlayer.ticksExisted);
            }
        }
        return directCount == 1 ? soleDirect : bestExactPlane != null ? bestExactPlane : best;
    }

    /** Cheap first pass: direct-plane geometry decides whether 3D expansion is needed at all. */
    private boolean hasDirectPlaneCandidate(BlockPos base, long requiredTarget,
                                            double currentDistance, double currentDepthCost) {
        for (EnumFacing step : EnumFacing.VALUES) {
            if (isDepthFacing(step)) continue;
            BlockPos target = base.offset(step);
            if (target.getY() != this.goalCrumb.getY()
                    || requiredTarget != Long.MIN_VALUE && target.toLong() != requiredTarget
                    || !SimpleAssist3DRoute.isWithinGuideBand(target, this.goalCrumb, this.goalNorthSouth)
                    || !isReplaceable(SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, target))
                    || !hasValidDepthParity(target)) continue;
            double progress = goalDistance(target);
            if (!(progress < currentDistance || progress == currentDistance
                    && depthLeadCost(target) < currentDepthCost)) continue;
            boolean horizontal = target.getY() == base.getY();
            if (horizontal && allowedGuideSide() != step) continue;
            if (SpatialAssistanceEngine.findExecutionPlacement(this.minecraft, target,
                    this.minecraft.playerController.getBlockReachDistance(),
                    this.pendingCount == 0 ? null : base, GlobalSpatialAssistance.isEnabled(), step) != null) return true;
        }
        return false;
    }

    /** A depth manoeuvre is admitted only when it already exposes a real exit. */
    private boolean hasForwardExit(BlockPos from) {
        double distance = goalDistance(from);
        for (EnumFacing step : EnumFacing.VALUES) {
            boolean depth = isDepthFacing(step);
            BlockPos next = depth ? from.offset(step, DEPTH_LEAD_BLOCKS) : from.offset(step);
            if (!SimpleAssist3DRoute.isWithinGuideBand(next, this.goalCrumb, this.goalNorthSouth)
                    || !isReplaceable(SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, next))
                    || !hasValidDepthParity(next) || goalDistance(next) >= distance) continue;
            if (SpatialAssistanceEngine.findExecutionPlacement(this.minecraft, next,
                    this.minecraft.playerController.getBlockReachDistance()) != null) return true;
        }
        return false;
    }

    private boolean isDepthFacing(EnumFacing side) {
        if (side.getFrontOffsetY() != 0) return false;
        return this.goalNorthSouth
                ? side == EnumFacing.NORTH || side == EnumFacing.SOUTH
                : side == EnumFacing.EAST || side == EnumFacing.WEST;
    }

    /** Patch nodes must remain separated by one water cell along depth. */
    private boolean hasValidDepthParity(BlockPos position) {
        return Math.abs(depthCoordinate(position) - this.depthAnchor) % 2 == 0;
    }

    /** A session leads once by two cells; it cannot drift indefinitely in depth. */
    private double depthLeadCost(BlockPos position) {
        int depth = depthCoordinate(position);
        if (Math.abs(depth - this.depthAnchor) >= DEPTH_LEAD_BLOCKS) return 0.0D;
        double motion = this.goalNorthSouth ? this.minecraft.thePlayer.motionZ
                : this.minecraft.thePlayer.motionX;
        if (Math.abs(motion) <= 0.005D) return 0.0D;
        int desired = this.depthAnchor + (motion > 0.0D ? DEPTH_LEAD_BLOCKS : -DEPTH_LEAD_BLOCKS);
        return Math.abs(depth - desired);
    }

    private int depthCoordinate(BlockPos position) {
        return this.goalNorthSouth ? position.getZ() : position.getX();
    }

    private Vec3 currentEyePosition() {
        return new Vec3(this.minecraft.thePlayer.posX,
                this.minecraft.thePlayer.posY + this.minecraft.thePlayer.getEyeHeight(),
                this.minecraft.thePlayer.posZ);
    }

    /** One-tick bounded lead; it ranks candidates but cannot expand reach. */
    private Vec3 predictedEyePosition(Vec3 eye) {
        double horizontalSpeedSq = this.minecraft.thePlayer.motionX * this.minecraft.thePlayer.motionX
                + this.minecraft.thePlayer.motionZ * this.minecraft.thePlayer.motionZ;
        double leadFactor = horizontalSpeedSq > 0.015D ? 1.0D
                : horizontalSpeedSq > 0.005D ? 0.5D : 0.0D;
        return eye.addVector(this.minecraft.thePlayer.motionX * leadFactor,
                this.minecraft.thePlayer.motionY * leadFactor,
                this.minecraft.thePlayer.motionZ * leadFactor);
    }

    private double behindMotionPenalty(Vec3 hit) {
        double motionX = this.minecraft.thePlayer.motionX;
        double motionZ = this.minecraft.thePlayer.motionZ;
        double targetX = hit.xCoord - this.minecraft.thePlayer.posX;
        double targetZ = hit.zCoord - this.minecraft.thePlayer.posZ;
        double motionLengthSq = motionX * motionX + motionZ * motionZ;
        double targetLengthSq = targetX * targetX + targetZ * targetZ;
        if (motionLengthSq <= 0.0001D || targetLengthSq <= 0.0001D) return 0.0D;
        double dot = (motionX * targetX + motionZ * targetZ)
                / Math.sqrt(motionLengthSq * targetLengthSq);
        return dot < 0.0D ? -dot * WEIGHT_BEHIND_MOTION : 0.0D;
    }

    /** Softly prefers a continuous hand/view trajectory over an isolated optimum. */
    private double naturalTrailPenalty(Vec3 eye, Vec3 hit) {
        if (!isNatural() || this.naturalLastDispatchDirection == null) return 0.0D;
        Vec3 direction = directionTo(eye, hit);
        return vectorAngle(this.naturalLastDispatchDirection, direction) * WEIGHT_TRAIL_CONTINUITY;
    }

    private static Vec3 directionTo(Vec3 origin, Vec3 target) {
        double x = target.xCoord - origin.xCoord;
        double y = target.yCoord - origin.yCoord;
        double z = target.zCoord - origin.zCoord;
        double length = Math.sqrt(x * x + y * y + z * z);
        return length <= 0.00001D ? new Vec3(0.0D, 0.0D, 0.0D)
                : new Vec3(x / length, y / length, z / length);
    }

    private static double vectorAngle(Vec3 first, Vec3 second) {
        double firstLength = Math.sqrt(first.xCoord * first.xCoord + first.yCoord * first.yCoord
                + first.zCoord * first.zCoord);
        double secondLength = Math.sqrt(second.xCoord * second.xCoord + second.yCoord * second.yCoord
                + second.zCoord * second.zCoord);
        if (firstLength <= 0.00001D || secondLength <= 0.00001D) return 0.0D;
        double dot = (first.xCoord * second.xCoord + first.yCoord * second.yCoord
                + first.zCoord * second.zCoord) / (firstLength * secondLength);
        return Math.acos(Math.max(-1.0D, Math.min(1.0D, dot)));
    }

    /** Updates the Natural-mode session latch without changing the local view. */
    private void updateNaturalSession() {
        if (!isNatural()) return;
        boolean rightDown = Mouse.isButtonDown(1);
        if (rightDown && !this.naturalLastRightDown) {
            armNaturalSession();
        }
        this.naturalLastRightDown = rightDown;
        if (this.naturalSession != NATURAL_SESSION_SPENT) return;

        long liveActivation = physicalActivationFingerprint();
        if (liveActivation == Long.MIN_VALUE) {
            this.naturalNoHitTicks++;
            return;
        }
        boolean deliberateReaim = rotationDistance(this.minecraft.thePlayer.rotationYaw,
                this.minecraft.thePlayer.rotationPitch, this.naturalSpentYaw,
                this.naturalSpentPitch) >= NATURAL_REARM_ANGLE;
        // Do not treat a block change caused by our own last C08 as a fresh
        // intent. Only a real input edge, a clear/re-entry, or a meaningful
        // user-driven re-aim can leave SPENT.
        if (this.naturalNoHitTicks >= 2 || deliberateReaim) {
            armNaturalSession();
        }
        this.naturalNoHitTicks = 0;
    }

    private void markNaturalSpent() {
        this.naturalSession = NATURAL_SESSION_SPENT;
        this.naturalSpentActivation = physicalActivationFingerprint();
        this.naturalSpentYaw = this.minecraft.thePlayer == null ? 0.0F
                : this.minecraft.thePlayer.rotationYaw;
        this.naturalSpentPitch = this.minecraft.thePlayer == null ? 0.0F
                : this.minecraft.thePlayer.rotationPitch;
        this.naturalNoHitTicks = 0;
    }

    private void armNaturalSession() {
        this.naturalSession = NATURAL_SESSION_ARMED;
        this.naturalSpentActivation = Long.MIN_VALUE;
        this.naturalNoHitTicks = 0;
    }

    private void resetNaturalSession() {
        this.naturalSession = NATURAL_SESSION_ARMED;
        this.naturalLastRightDown = Mouse.isButtonDown(1);
        this.naturalNoHitTicks = 0;
        this.naturalSpentActivation = Long.MIN_VALUE;
        this.naturalSpentYaw = 0.0F;
        this.naturalSpentPitch = 0.0F;
    }

    /** Uses the physical ray only: virtual assistance cannot silently re-arm a session. */
    private long physicalActivationFingerprint() {
        MovingObjectPosition hit = this.minecraft.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null || hit.sideHit == null) return Long.MIN_VALUE;
        return hit.getBlockPos().toLong() ^ ((long)(hit.sideHit.ordinal() + 1) << 60);
    }

    private static float rotationDistance(float yaw, float pitch, float previousYaw, float previousPitch) {
        float yawDelta = Math.abs(yaw - previousYaw) % 360.0F;
        if (yawDelta > 180.0F) yawDelta = 360.0F - yawDelta;
        float pitchDelta = pitch - previousPitch;
        return (float)Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);
    }

    private static double angularDelta(Vec3 eye, Vec3 look, Vec3 hit) {
        double targetX = hit.xCoord - eye.xCoord;
        double targetY = hit.yCoord - eye.yCoord;
        double targetZ = hit.zCoord - eye.zCoord;
        double targetLength = Math.sqrt(targetX * targetX + targetY * targetY + targetZ * targetZ);
        double lookLength = Math.sqrt(look.xCoord * look.xCoord + look.yCoord * look.yCoord
                + look.zCoord * look.zCoord);
        if (targetLength <= 0.0D || lookLength <= 0.0D) return Math.PI;
        double cosine = (targetX * look.xCoord + targetY * look.yCoord + targetZ * look.zCoord)
                / (targetLength * lookLength);
        return Math.acos(Math.max(-1.0D, Math.min(1.0D, cosine)));
    }

    private double goalDistance(BlockPos position) {
        if (this.goalCrumb == null || position == null) return Double.MAX_VALUE;
        int horizontal = this.goalNorthSouth ? Math.abs(position.getX() - this.goalCrumb.getX())
                : Math.abs(position.getZ() - this.goalCrumb.getZ());
        return horizontal + Math.abs(position.getY() - this.goalCrumb.getY());
    }

    private boolean isGoalReached() {
        return this.lastConfirmed != null && goalDistance(this.lastConfirmed) == 0.0D;
    }

    /** Only build from the side of the PatchCrumbs dividing plane occupied by the player. */
    private EnumFacing allowedGuideSide() {
        if (this.goalCrumb == null) return null;
        if (this.goalNorthSouth) {
            double plane = this.goalCrumb.getX() + 0.5D;
            if (this.minecraft.thePlayer.posX < plane) return EnumFacing.WEST;
            if (this.minecraft.thePlayer.posX > plane) return EnumFacing.EAST;
        } else {
            double plane = this.goalCrumb.getZ() + 0.5D;
            if (this.minecraft.thePlayer.posZ < plane) return EnumFacing.NORTH;
            if (this.minecraft.thePlayer.posZ > plane) return EnumFacing.SOUTH;
        }
        return null;
    }

    private void dispatch(final long target, SpatialAssistanceEngine.TargetPlacement placement) {
        ItemStack held = this.minecraft.thePlayer.getHeldItem();
        final BlockPos support = placement.getAnchorNode();
        final EnumFacing face = placement.getSide();
        final Vec3 hitVec = placement.getHitVector();
        boolean accepted = GlobalSpatialAssistance.isEnabled()
                ? this.spatial.processInteraction(this.minecraft, support, face, hitVec,
                placement.usesQueuedSupport(),
                new SpatialAssistanceEngine.InteractionDispatcher() {
                    @Override public boolean dispatch() { return rightClick(support, face, hitVec); }
                }) : rightClick(support, face, hitVec);
        if (!accepted) {
            this.retainedCandidate = null;
            abortAfterMiss();
            return;
        }
        this.retainedCandidate = null;
        this.credits -= 1.0F;
        this.previewTarget = target;
        this.pendingTargets[this.pendingCount] = target;
        this.pendingBlockIds[this.pendingCount] = SimpleAssistMinecraftAccess.getHeldBlockId(held);
        this.pendingSinceTicks[this.pendingCount] = this.minecraft.thePlayer.ticksExisted;
        this.pendingCount++;
        AnchorQuarantineRegistry.shared().expectAssistedPlacement(target, face.getIndex(),
                this.pendingBlockIds[this.pendingCount - 1], this.minecraft.thePlayer.ticksExisted);
        this.lastProgressTick = this.minecraft.thePlayer.ticksExisted;
        this.requestMisses = 0;
        if (isNatural()) {
            this.naturalSession = NATURAL_SESSION_DISPATCHED;
            this.naturalLastDispatchDirection = directionTo(currentEyePosition(), hitVec);
        }
        this.minecraft.thePlayer.swingItem();
    }

    /** Sends a second prevalidated neighbour in the same client pulse when credit permits. */
    private void dispatchReactiveWindow(ReactiveCandidate first) {
        int pendingBefore = this.pendingCount;
        dispatch(first.target, first.placement);
        if (this.pendingCount == pendingBefore || this.pendingCount >= MAX_CHAINED_PENDING
                || !hasCredit()) return;
        ReactiveCandidate follower = selectReactiveCandidate();
        if (follower != null) dispatch(follower.target, follower.placement);
    }

    private boolean rightClick(BlockPos support, EnumFacing face, Vec3 hitVec) {
        ItemStack held = this.minecraft.thePlayer.getHeldItem();
        return held != null && this.minecraft.playerController.onPlayerRightClick(this.minecraft.thePlayer,
                this.minecraft.theWorld, held, support, face, hitVec);
    }

    private void confirmPending() {
        if (!hasPending()) return;
        int currentTick = this.minecraft.thePlayer.ticksExisted;
        while (this.pendingCount > 0) {
            int actual = SimpleAssistMinecraftAccess.blockId(SimpleAssistMinecraftAccess.getBlockSnapshot(
                    this.minecraft.theWorld, this.pendingTargets[0]));
            if (actual == this.pendingBlockIds[0]) {
                if (this.previewTarget == this.pendingTargets[0]) {
                    this.previewTarget = this.pendingCount > 1
                            ? this.pendingTargets[1] : Long.MIN_VALUE;
                }
                BlockPos confirmed = BlockPos.fromLong(this.pendingTargets[0]);
                if (this.lastConfirmed != null) {
                    int delta = depthCoordinate(confirmed) - depthCoordinate(this.lastConfirmed);
                    int sign = Integer.signum(delta);
                    if (sign != 0) this.depthRun = sign == this.depthRunSign ? this.depthRun + 1 : 1;
                    else { this.depthRun = 0; this.depthRunSign = 0; }
                }
                this.lastConfirmed = confirmed;
                shiftPending();
                this.requestMisses = 0;
                this.lastProgressTick = currentTick;
                continue;
            }
            if (currentTick - this.pendingSinceTicks[0] > PENDING_TIMEOUT_TICKS) reset();
            return;
        }
    }

    private void reserveInputIfNeeded() {
        if (hasRealPatchRoute()) {
            if (RightClickCoordinator.acquire(this, INPUT_PRIORITY)) suppressPhysicalRightClick();
        } else if (RightClickCoordinator.isOwner(this)) {
            releaseInput();
        }
    }

    private void refillCredits() {
        int tick = this.minecraft.thePlayer.ticksExisted;
        if (this.creditTick == Integer.MIN_VALUE) this.creditTick = tick;
        int elapsed = Math.max(0, tick - this.creditTick);
        this.creditTick = tick;
        this.credits = Math.min(2.0F, this.credits + elapsed * GlobalCps.get() / 20.0F);
    }

    /** Mirrors SimpleAssist's stall handling without sharing its 1D state. */
    private boolean watchdogExpired() {
        if (!this.cursorActive && !hasPending()) return false;
        int tick = this.minecraft.thePlayer.ticksExisted;
        // Vanilla use-item actions intentionally hold the input pipeline; do
        // not classify that user-controlled pause as a stalled route.
        if (this.minecraft.thePlayer.isUsingItem()) {
            this.lastProgressTick = tick;
            return false;
        }
        return this.lastProgressTick != Integer.MIN_VALUE
                && tick - this.lastProgressTick > ROUTE_WATCHDOG_TIMEOUT_TICKS;
    }

    private boolean hasCredit() { return this.credits >= 1.0F; }
    private boolean hasPending() { return this.pendingCount > 0; }
    private boolean isNatural() { return GlobalNaturalMode.isEnabled(); }
    private void syncStateFrictionRegistration() {
        boolean shouldRegister = this.enabled && GlobalReducedKb.isEnabled();
        if (shouldRegister && !this.stateFrictionRegistered) {
            this.stateDrivenFriction.activate(StateDrivenFriction.PARTICIPANT_SIMPLE_ASSIST_3D);
            this.stateFrictionRegistered = true;
        } else if (!shouldRegister && this.stateFrictionRegistered) {
            unregisterStateFriction();
        }
    }
    private void unregisterStateFriction() {
        if (!this.stateFrictionRegistered) return;
        this.stateDrivenFriction.deactivate(StateDrivenFriction.PARTICIPANT_SIMPLE_ASSIST_3D,
                this.minecraft);
        this.stateFrictionRegistered = false;
    }
    private boolean isAccepted(int snapshot) { return NativeBridge.isSimpleAssistAcceptedBlock(SimpleAssistMinecraftAccess.blockId(snapshot)); }
    private boolean isReplaceable(int snapshot) { return snapshot >= 0 && (snapshot & (1 << 16)) != 0; }
    private boolean hasWorld() { return this.minecraft.thePlayer != null && this.minecraft.theWorld != null && this.minecraft.playerController != null; }
    private boolean isPatchBlockHeld() {
        ItemStack held = this.minecraft.thePlayer.getHeldItem();
        return held != null && held.stackSize > 0 && held.getItem() instanceof ItemBlock
                && NativeBridge.isSimpleAssistAcceptedBlock(SimpleAssistMinecraftAccess.getHeldBlockId(held));
    }
    private void abortAfterMiss() {
        if (++this.requestMisses >= REQUEST_MISS_LIMIT) {
            if (isNatural()) markNaturalSpent();
            reset();
        }
    }
    private void releaseInput() {
        boolean owned = RightClickCoordinator.isOwner(this);
        RightClickCoordinator.release(this);
        if (owned) setInt(RIGHT_CLICK_DELAY_TIMER, this.minecraft, 4);
    }
    private void reset() {
        this.spatial.reset(); this.cursorActive = false; this.lastConfirmed = null;
        this.goalCrumb = null; this.goalNorthSouth = false; this.depthAnchor = 0;
        this.depthRun = 0; this.depthRunSign = 0;
        this.lastSelectionReason = "idle";
        this.previewTarget = Long.MIN_VALUE;
        this.capturedCrumbRevision = -1L;
        this.retainedCandidate = null;
        this.pendingCount = 0;
        for (int index = 0; index < MAX_CHAINED_PENDING; index++) {
            this.pendingTargets[index] = Long.MIN_VALUE;
            this.pendingBlockIds[index] = -1;
            this.pendingSinceTicks[index] = 0;
        }
        this.requestMisses = 0;
        this.lastProgressTick = Integer.MIN_VALUE;
        this.credits = 1.0F; this.creditTick = Integer.MIN_VALUE; releaseInput();
        this.naturalLastDispatchDirection = null;
    }
    private void suppressPhysicalRightClick() {
        KeyBinding key = this.minecraft.gameSettings.keyBindUseItem;
        KeyBinding.setKeyBindState(key.getKeyCode(), false); setInt(PRESS_TIME, key, 0);
    }
    private void shiftPending() {
        for (int index = 1; index < this.pendingCount; index++) {
            this.pendingTargets[index - 1] = this.pendingTargets[index];
            this.pendingBlockIds[index - 1] = this.pendingBlockIds[index];
            this.pendingSinceTicks[index - 1] = this.pendingSinceTicks[index];
        }
        this.pendingCount--;
        this.pendingTargets[this.pendingCount] = Long.MIN_VALUE;
        this.pendingBlockIds[this.pendingCount] = -1;
        this.pendingSinceTicks[this.pendingCount] = 0;
    }
    private static final class ReactiveCandidate {
        private final long base;
        private final long target;
        private final SpatialAssistanceEngine.TargetPlacement placement;
        private final double score;
        private final int createdTick;
        private ReactiveCandidate(long base, long target,
                                  SpatialAssistanceEngine.TargetPlacement placement,
                                  double score, int createdTick) {
            this.base = base;
            this.target = target;
            this.placement = placement;
            this.score = score;
            this.createdTick = createdTick;
        }
    }
    private static void drawPreviewBox(double x1, double y1, double z1,
                                       double x2, double y2, double z2) {
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(x1, y1, z1); GL11.glVertex3d(x2, y1, z1);
        GL11.glVertex3d(x2, y1, z1); GL11.glVertex3d(x2, y1, z2);
        GL11.glVertex3d(x2, y1, z2); GL11.glVertex3d(x1, y1, z2);
        GL11.glVertex3d(x1, y1, z2); GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x1, y2, z1); GL11.glVertex3d(x2, y2, z1);
        GL11.glVertex3d(x2, y2, z1); GL11.glVertex3d(x2, y2, z2);
        GL11.glVertex3d(x2, y2, z2); GL11.glVertex3d(x1, y2, z2);
        GL11.glVertex3d(x1, y2, z2); GL11.glVertex3d(x1, y2, z1);
        GL11.glVertex3d(x1, y1, z1); GL11.glVertex3d(x1, y2, z1);
        GL11.glVertex3d(x2, y1, z1); GL11.glVertex3d(x2, y2, z1);
        GL11.glVertex3d(x2, y1, z2); GL11.glVertex3d(x2, y2, z2);
        GL11.glVertex3d(x1, y1, z2); GL11.glVertex3d(x1, y2, z2);
        GL11.glEnd();
    }
    private Settings setting(int index) { return this.sl.get(index); }
    private static void setInt(Field field, Object target, int value) {
        try { field.setInt(target, value); }
        catch (IllegalAccessException exception) { throw new IllegalStateException(exception); }
    }
    @Override public void onRightClickControlLost() { reset(); }
    @Override public char getSort() { return 'h'; }
}

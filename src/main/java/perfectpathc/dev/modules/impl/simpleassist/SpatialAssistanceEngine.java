package perfectpathc.dev.modules.impl.simpleassist;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import perfectpathc.dev.modules.impl.utils.AimUtils;
import perfectpathc.dev.modules.impl.utils.XObject;

/**
 * Packet-only target alignment used by {@code SimpleAssist} placement requests.
 *
 * <p>The engine never writes to the player's rotation fields.  A corrected look
 * packet is sent only immediately before a placement that has passed both the
 * capture-radius and world-raytrace checks.</p>
 */
public final class SpatialAssistanceEngine {
    public static final double VIRTUAL_CONE_RADIUS = 1.25D;
    private static final double RAYTRACE_FACE_PENETRATION = 0.001D;
    /** Keeps a projected click off 1.8.9 face edges/corners. */
    private static final double FACE_INTERIOR_INSET = 0.05D;
    private static final double MAX_PHYSICAL_REACH = 4.5D;
    private static final float MAX_CREDITS = 2.0F;
    private static final float INTERACTION_COST = 1.0F;

    private float tokenCredits = INTERACTION_COST;

    /** Refill once for each client/server tick, never retaining more than two sends. */
    public void onPreMotionUpdate() {
        this.tokenCredits = Math.min(MAX_CREDITS, this.tokenCredits + INTERACTION_COST);
    }

    public void reset() {
        this.tokenCredits = INTERACTION_COST;
    }

    /**
     * Resolves a placement support for a required cell.  The physical raycast
     * remains the preferred candidate, while the virtual cone is its fallback.
     */
    public static TargetPlacement findTargetPlacement(Minecraft minecraft,
                                                       BlockPos requiredTarget,
                                                       double reach) {
        if (!hasRuntime(minecraft) || requiredTarget == null || reach <= 0.0D) return null;
        TargetPlacement vanilla = fromVanillaHit(minecraft, requiredTarget);
        return vanilla != null ? vanilla
                : findVirtualTarget(minecraft, VIRTUAL_CONE_RADIUS, requiredTarget, reach, null);
    }

    /**
     * Execution-phase support search. Unlike activation, this deliberately
     * has no virtual-cone constraint: a captured patch already authorizes the
     * placement intent. It evaluates all six adjacent support faces and ranks
     * only feasible candidates by their angular delta from the real look vector.
     */
    public static TargetPlacement findExecutionPlacement(Minecraft minecraft,
                                                          BlockPos target, double reach) {
        return findExecutionPlacement(minecraft, target, reach, null);
    }

    /** A single already-queued block may act as support for a chained C08. */
    public static TargetPlacement findExecutionPlacement(Minecraft minecraft,
                                                          BlockPos target, double reach,
                                                          BlockPos queuedSupport) {
        return findExecutionPlacement(minecraft, target, reach, queuedSupport, false);
    }

    /** Optionally constrains support acquisition to the expanded input cone. */
    public static TargetPlacement findExecutionPlacement(Minecraft minecraft,
                                                          BlockPos target, double reach,
                                                          BlockPos queuedSupport,
                                                          boolean requireExpandedCone) {
        return findExecutionPlacement(minecraft, target, reach, queuedSupport,
                requireExpandedCone, null);
    }

    /** Restricts a request to one physically valid side when a guide supplies it. */
    public static TargetPlacement findExecutionPlacement(Minecraft minecraft,
                                                          BlockPos target, double reach,
                                                          BlockPos queuedSupport,
                                                          boolean requireExpandedCone,
                                                          EnumFacing requiredSide) {
        if (!hasRuntime(minecraft) || target == null || reach <= 0.0D) return null;
        EntityPlayerSP player = minecraft.thePlayer;
        Vec3 eyePos = new Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        Vec3 lookVector = player.getLookVec();
        TargetPlacement best = null;
        double lowestAngularDelta = Double.MAX_VALUE;
        for (EnumFacing side : EnumFacing.VALUES) {
            if (requiredSide != null && side != requiredSide) continue;
            BlockPos support = target.offset(side.getOpposite());
            if (wouldPlacementIntersectPlayer(player, target)) continue;
            boolean virtualSupport = queuedSupport != null && queuedSupport.equals(support);
            if (!virtualSupport && !isSolidSupport(minecraft.theWorld, support)) continue;
            // Reach belongs to the closest usable point of a face, not its
            // center.  A center-only test silently throws away valid Vanilla
            // range near 4.5 blocks at oblique angles.
            Vec3 hitVector = nearestPointOnFace(eyePos, support, side);
            if (!isPhysicallyPlausible(player, support, side, hitVector)) continue;
            boolean valid = isExecutionPointValid(minecraft.theWorld, eyePos, lookVector, hitVector,
                    support, side, reach, requireExpandedCone, virtualSupport);
            if (!valid && !virtualSupport) hitVector = fallbackFacePoint(minecraft.theWorld, eyePos,
                    lookVector, support, side, reach, requireExpandedCone);
            if (hitVector == null) {
                continue;
            }
            double angularDelta = angularDelta(eyePos, lookVector, hitVector);
            if (angularDelta < lowestAngularDelta) {
                lowestAngularDelta = angularDelta;
                best = new TargetPlacement(support, side, hitVector, virtualSupport);
            }
        }
        return best;
    }

    /**
     * Finds the nearest exposed solid face inside the expanded look cone.
     * This overload is useful when the caller has no required placement cell.
     */
    public static TargetPlacement findVirtualTarget(Minecraft minecraft, double radius) {
        return findVirtualTarget(minecraft, radius, null,
                minecraft != null && minecraft.playerController != null
                        ? minecraft.playerController.getBlockReachDistance() : 0.0D, null);
    }

    /**
     * Input-pipeline adapter for callers that consume a vanilla raycast. It
     * preserves a real block hit verbatim and synthesizes one only for air/null.
     */
    public static MovingObjectPosition getTargetOrVirtual(Minecraft minecraft) {
        return getTargetOrVirtual(minecraft, null);
    }

    /** Restricts synthetic hits to a caller-provided state-machine predicate. */
    public static MovingObjectPosition getTargetOrVirtual(Minecraft minecraft,
                                                           TargetFilter filter) {
        return getActivationHit(minecraft, VIRTUAL_CONE_RADIUS, filter);
    }

    /**
     * Minimal activation-read adapter. It returns the physical raycast only
     * when that hit is already admissible for the caller; otherwise it scans
     * the enlarged cone without changing any caller state.
     */
    public static MovingObjectPosition getActivationHit(Minecraft minecraft, double radius,
                                                         TargetFilter activationFilter) {
        if (!hasRuntime(minecraft)) return null;
        MovingObjectPosition originalHit = minecraft.objectMouseOver;
        if (originalHit != null
                && originalHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && (activationFilter == null || originalHit.getBlockPos() != null
                && activationFilter.matches(originalHit.getBlockPos(), originalHit.sideHit))) {
            return originalHit;
        }
        double reach = minecraft.playerController == null ? 0.0D
                : minecraft.playerController.getBlockReachDistance();
        TargetPlacement virtual = findVirtualTarget(minecraft, radius,
                null, reach, activationFilter);
        return virtual == null ? originalHit : new MovingObjectPosition(virtual.getHitVector(),
                virtual.getSide(), virtual.getAnchorNode());
    }

    /** True only when the real or expanded view ray can acquire this exact face. */
    public static boolean hasExpandedRaycastToFace(Minecraft minecraft, final BlockPos anchor,
                                                   final EnumFacing side) {
        if (anchor == null || side == null) return false;
        MovingObjectPosition hit = getActivationHit(minecraft, VIRTUAL_CONE_RADIUS,
                new TargetFilter() {
                    @Override public boolean matches(BlockPos candidate, EnumFacing candidateSide) {
                        return anchor.equals(candidate) && side == candidateSide;
                    }
                });
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && anchor.equals(hit.getBlockPos()) && side == hit.sideHit;
    }

    private static TargetPlacement fromVanillaHit(Minecraft minecraft, BlockPos requiredTarget) {
        MovingObjectPosition hit = minecraft.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null || hit.sideHit == null || hit.hitVec == null
                || !isSolidSupport(minecraft.theWorld, hit.getBlockPos())
                || requiredTarget != null && !requiredTarget.equals(hit.getBlockPos().offset(hit.sideHit))) {
            return null;
        }
        return new TargetPlacement(hit.getBlockPos(), hit.sideHit, hit.hitVec);
    }

    private static TargetPlacement findVirtualTarget(Minecraft minecraft, double radius,
                                                     BlockPos requiredTarget, double reach,
                                                     TargetFilter filter) {
        if (!hasRuntime(minecraft) || radius < 0.0D || reach <= 0.0D) return null;
        EntityPlayerSP player = minecraft.thePlayer;
        Vec3 eyePos = new Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        Vec3 lookVec = player.getLookVec();
        Vec3 rayEnd = eyePos.addVector(lookVec.xCoord * reach,
                lookVec.yCoord * reach, lookVec.zCoord * reach);
        int padding = (int)Math.ceil(radius) + 1;
        BlockPos minimum = new BlockPos(Math.min(eyePos.xCoord, rayEnd.xCoord) - padding,
                Math.min(eyePos.yCoord, rayEnd.yCoord) - padding,
                Math.min(eyePos.zCoord, rayEnd.zCoord) - padding);
        BlockPos maximum = new BlockPos(Math.max(eyePos.xCoord, rayEnd.xCoord) + padding,
                Math.max(eyePos.yCoord, rayEnd.yCoord) + padding,
                Math.max(eyePos.zCoord, rayEnd.zCoord) + padding);

        TargetPlacement best = null;
        double closestDistance = Double.MAX_VALUE;
        for (BlockPos.MutableBlockPos cursor : BlockPos.getAllInBoxMutable(minimum, maximum)) {
            // 1.8.9 MutableBlockPos has no toImmutable() helper.
            BlockPos anchor = new BlockPos(cursor.getX(), cursor.getY(), cursor.getZ());
            if (!isSolidSupport(minecraft.theWorld, anchor)) continue;
            for (EnumFacing side : EnumFacing.VALUES) {
                if (requiredTarget != null && !requiredTarget.equals(anchor.offset(side))) continue;
                if (filter != null && !filter.matches(anchor, side)) continue;
                if (!isExposedFace(minecraft.theWorld, anchor, side)) continue;
                Vec3 hitVector = nearestPointOnFace(eyePos, anchor, side);
                double forwardDistance = forwardDistance(eyePos, lookVec, hitVector);
                double distanceToRay = distanceToLookRay(eyePos, lookVec, hitVector);
                if (forwardDistance < 0.0D || forwardDistance > reach
                        || eyePos.squareDistanceTo(hitVector) > reach * reach
                        || distanceToRay > radius || distanceToRay >= closestDistance
                        || !hasUnobstructedLineOfSight(minecraft.theWorld, eyePos,
                        hitVector, anchor, side)) {
                    continue;
                }
                closestDistance = distanceToRay;
                best = new TargetPlacement(anchor, side, hitVector);
            }
        }
        return best;
    }

    /**
     * Validates and dispatches a placement with a packet-only yaw/pitch correction.
     * The callback owns the actual interaction packet so SimpleAssist retains its
     * established placement-confirmation state machine.
     */
    public boolean processInteraction(Minecraft minecraft, BlockPos anchorNode,
                                      EnumFacing side, Vec3 targetVec,
                                      InteractionDispatcher dispatcher) {
        return processInteraction(minecraft, anchorNode, side, targetVec, false, dispatcher);
    }

    public boolean processInteraction(Minecraft minecraft, BlockPos anchorNode,
                                      EnumFacing side, Vec3 targetVec, boolean queuedSupport,
                                      InteractionDispatcher dispatcher) {
        if (minecraft == null || minecraft.thePlayer == null || minecraft.theWorld == null
                || minecraft.thePlayer.sendQueue == null || anchorNode == null || side == null
                || targetVec == null || dispatcher == null || this.tokenCredits < INTERACTION_COST) {
            return false;
        }

        EntityPlayerSP player = minecraft.thePlayer;
        Vec3 eyePos = new Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        BlockPos placementTarget = anchorNode.offset(side);
        if (wouldPlacementIntersectPlayer(player, placementTarget)
                || !isPhysicallyPlausible(player, anchorNode, side, targetVec)
                || !(queuedSupport ? hasClearLineToQueuedSupport(minecraft.theWorld, eyePos, targetVec)
                : hasUnobstructedLineOfSight(minecraft.theWorld, eyePos, targetVec, anchorNode, side))) {
            return false;
        }

        float[] rotations = AimUtils.getRotations(new XObject<Vec3>(targetVec)).get();
        // This state is intentionally network-only: do not update rotationYaw/Pitch
        // or their previous-frame counterparts here.
        player.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(
                rotations[0], rotations[1], player.onGround));

        if (!dispatcher.dispatch()) {
            return false;
        }
        this.tokenCredits -= INTERACTION_COST;
        return true;
    }

    /**
     * Rejects faces that a local player posture could not credibly interact
     * with.  This is deliberately evaluated from the real client rotation;
     * the later C05 is network-only and cannot satisfy a missing posture.
     */
    public static boolean isPhysicallyPlausible(EntityPlayerSP player,
                                                 BlockPos anchorNode,
                                                 EnumFacing sideToClick,
                                                 Vec3 hitVec) {
        if (player == null || anchorNode == null || sideToClick == null || hitVec == null) {
            return false;
        }
        Vec3 eyePos = new Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        if (eyePos.squareDistanceTo(hitVec) > MAX_PHYSICAL_REACH * MAX_PHYSICAL_REACH) return false;

        return true;
    }

    /** Reject a placement that would create a full block inside the player. */
    public static boolean wouldPlacementIntersectPlayer(EntityPlayerSP player, BlockPos target) {
        if (player == null || target == null) return true;
        // Preserve legitimate feet/bridge placements: only widen the torso
        // footprint, never the player box toward the block below the feet.
        AxisAlignedBB playerBox = player.getEntityBoundingBox().expand(0.05D, 0.0D, 0.05D);
        AxisAlignedBB placedBlock = new AxisAlignedBB(target.getX(), target.getY(), target.getZ(),
                target.getX() + 1.0D, target.getY() + 1.0D, target.getZ() + 1.0D);
        return playerBox.intersectsWith(placedBlock);
    }

    private static boolean isWithinCaptureRadius(Vec3 eyePos, Vec3 lookVector, Vec3 targetVec) {
        return distanceToLookRay(eyePos, lookVector, targetVec) <= VIRTUAL_CONE_RADIUS
                && forwardDistance(eyePos, lookVector, targetVec) >= 0.0D;
    }

    private static double distanceToLookRay(Vec3 eyePos, Vec3 lookVector, Vec3 targetVec) {
        double lengthSquared = lookVector.xCoord * lookVector.xCoord
                + lookVector.yCoord * lookVector.yCoord + lookVector.zCoord * lookVector.zCoord;
        if (lengthSquared <= 0.0D) return Double.POSITIVE_INFINITY;
        double targetX = targetVec.xCoord - eyePos.xCoord;
        double targetY = targetVec.yCoord - eyePos.yCoord;
        double targetZ = targetVec.zCoord - eyePos.zCoord;
        double forward = forwardDistance(eyePos, lookVector, targetVec);
        double closestX = eyePos.xCoord + lookVector.xCoord * forward;
        double closestY = eyePos.yCoord + lookVector.yCoord * forward;
        double closestZ = eyePos.zCoord + lookVector.zCoord * forward;
        double offsetX = targetVec.xCoord - closestX;
        double offsetY = targetVec.yCoord - closestY;
        double offsetZ = targetVec.zCoord - closestZ;
        return Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
    }

    private static double forwardDistance(Vec3 eyePos, Vec3 lookVector, Vec3 targetVec) {
        double lengthSquared = lookVector.xCoord * lookVector.xCoord
                + lookVector.yCoord * lookVector.yCoord + lookVector.zCoord * lookVector.zCoord;
        if (lengthSquared <= 0.0D) return Double.NEGATIVE_INFINITY;
        return ((targetVec.xCoord - eyePos.xCoord) * lookVector.xCoord
                + (targetVec.yCoord - eyePos.yCoord) * lookVector.yCoord
                + (targetVec.zCoord - eyePos.zCoord) * lookVector.zCoord) / lengthSquared;
    }

    private static double angularDelta(Vec3 eyePos, Vec3 lookVector, Vec3 targetVec) {
        double targetX = targetVec.xCoord - eyePos.xCoord;
        double targetY = targetVec.yCoord - eyePos.yCoord;
        double targetZ = targetVec.zCoord - eyePos.zCoord;
        double targetLength = Math.sqrt(targetX * targetX + targetY * targetY + targetZ * targetZ);
        double lookLength = Math.sqrt(lookVector.xCoord * lookVector.xCoord
                + lookVector.yCoord * lookVector.yCoord + lookVector.zCoord * lookVector.zCoord);
        if (targetLength <= 0.0D || lookLength <= 0.0D) return Double.MAX_VALUE;
        double cosine = (targetX * lookVector.xCoord + targetY * lookVector.yCoord
                + targetZ * lookVector.zCoord) / (targetLength * lookLength);
        return Math.acos(Math.max(-1.0D, Math.min(1.0D, cosine)));
    }

    /**
     * Projects the eyes onto a support face, clamps that projection to its
     * stable interior and returns the closest valid C08 point.  The normal
     * coordinate remains exactly on the clicked face; LOS applies its own
     * tiny inward nudge only for the 1.8.9 boundary raytrace.
     */
    private static Vec3 nearestPointOnFace(Vec3 eyePos, BlockPos position, EnumFacing side) {
        double minimumX = position.getX() + FACE_INTERIOR_INSET;
        double maximumX = position.getX() + 1.0D - FACE_INTERIOR_INSET;
        double minimumY = position.getY() + FACE_INTERIOR_INSET;
        double maximumY = position.getY() + 1.0D - FACE_INTERIOR_INSET;
        double minimumZ = position.getZ() + FACE_INTERIOR_INSET;
        double maximumZ = position.getZ() + 1.0D - FACE_INTERIOR_INSET;
        switch (side) {
            case DOWN:
                return new Vec3(clamp(eyePos.xCoord, minimumX, maximumX), position.getY(),
                        clamp(eyePos.zCoord, minimumZ, maximumZ));
            case UP:
                return new Vec3(clamp(eyePos.xCoord, minimumX, maximumX), position.getY() + 1.0D,
                        clamp(eyePos.zCoord, minimumZ, maximumZ));
            case NORTH:
                return new Vec3(clamp(eyePos.xCoord, minimumX, maximumX),
                        clamp(eyePos.yCoord, minimumY, maximumY), position.getZ());
            case SOUTH:
                return new Vec3(clamp(eyePos.xCoord, minimumX, maximumX),
                        clamp(eyePos.yCoord, minimumY, maximumY), position.getZ() + 1.0D);
            case WEST:
                return new Vec3(position.getX(), clamp(eyePos.yCoord, minimumY, maximumY),
                        clamp(eyePos.zCoord, minimumZ, maximumZ));
            case EAST:
                return new Vec3(position.getX() + 1.0D, clamp(eyePos.yCoord, minimumY, maximumY),
                        clamp(eyePos.zCoord, minimumZ, maximumZ));
            default:
                return new Vec3(position.getX() + 0.5D, position.getY() + 0.5D,
                        position.getZ() + 0.5D);
        }
    }

    private static boolean isExecutionPointValid(World world, Vec3 eye, Vec3 look, Vec3 hit,
                                                  BlockPos support, EnumFacing side, double reach,
                                                  boolean cone, boolean queued) {
        return eye.distanceTo(hit) <= reach && (!cone || isWithinCaptureRadius(eye, look, hit))
                && (queued ? hasClearLineToQueuedSupport(world, eye, hit)
                : hasUnobstructedLineOfSight(world, eye, hit, support, side));
    }

    /** Legacy projection stays primary; these four are fallback only. */
    private static Vec3 fallbackFacePoint(World world, Vec3 eye, Vec3 look, BlockPos pos,
                                          EnumFacing side, double reach, boolean cone) {
        double[] p={.15D,.15D,.85D,.15D,.85D,.85D,.15D,.85D};
        for(int i=0;i<p.length;i+=2){ double a=p[i],b=p[i+1]; Vec3 h;
            switch(side){case DOWN:h=new Vec3(pos.getX()+a,pos.getY(),pos.getZ()+b);break;case UP:h=new Vec3(pos.getX()+a,pos.getY()+1,pos.getZ()+b);break;case NORTH:h=new Vec3(pos.getX()+a,pos.getY()+b,pos.getZ());break;case SOUTH:h=new Vec3(pos.getX()+a,pos.getY()+b,pos.getZ()+1);break;case WEST:h=new Vec3(pos.getX(),pos.getY()+a,pos.getZ()+b);break;default:h=new Vec3(pos.getX()+1,pos.getY()+a,pos.getZ()+b);}
            if(isExecutionPointValid(world,eye,look,h,pos,side,reach,cone,false)) return h;
        } return null;
    }


    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean hasRuntime(Minecraft minecraft) {
        return minecraft != null && minecraft.thePlayer != null && minecraft.theWorld != null;
    }

    private static boolean isSolidSupport(World world, BlockPos position) {
        IBlockState state = world.getBlockState(position);
        Block block = state.getBlock();
        return !block.isReplaceable(world, position) && block.canCollideCheck(state, false);
    }

    private static boolean isExposedFace(World world, BlockPos anchor, EnumFacing side) {
        BlockPos adjacent = anchor.offset(side);
        IBlockState state = world.getBlockState(adjacent);
        return state.getBlock().isReplaceable(world, adjacent);
    }

    private static boolean hasUnobstructedLineOfSight(World world, Vec3 eyePos,
                                                       Vec3 targetVec, BlockPos anchorNode,
                                                       EnumFacing side) {
        // hitVec lies exactly on the selected face. Nudge the ray endpoint into
        // the support block so 1.8.9's AABB-boundary handling cannot classify a
        // grazing endpoint as the adjacent cell or an early miss.
        Vec3 raytraceTarget = targetVec.addVector(
                -side.getFrontOffsetX() * RAYTRACE_FACE_PENETRATION,
                -side.getFrontOffsetY() * RAYTRACE_FACE_PENETRATION,
                -side.getFrontOffsetZ() * RAYTRACE_FACE_PENETRATION);
        MovingObjectPosition hit = world.rayTraceBlocks(eyePos, raytraceTarget,
                false, true, false);
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && anchorNode.equals(hit.getBlockPos()) && side == hit.sideHit;
    }

    /** The queued support is still air locally; only an earlier real blocker is invalid. */
    private static boolean hasClearLineToQueuedSupport(World world, Vec3 eyePos, Vec3 hitVec) {
        return world.rayTraceBlocks(eyePos, hitVec, false, true, false) == null;
    }

    public interface InteractionDispatcher {
        boolean dispatch();
    }

    /** Identifies virtual hits that the caller may admit into its state machine. */
    public interface TargetFilter {
        boolean matches(BlockPos anchorNode, EnumFacing side);
    }

    /** Immutable support block, face and hit point selected by the virtual raycast. */
    public static final class TargetPlacement {
        private final BlockPos anchorNode;
        private final EnumFacing side;
        private final Vec3 hitVector;

        private final boolean queuedSupport;

        private TargetPlacement(BlockPos anchorNode, EnumFacing side, Vec3 hitVector) {
            this(anchorNode, side, hitVector, false);
        }

        private TargetPlacement(BlockPos anchorNode, EnumFacing side, Vec3 hitVector,
                                boolean queuedSupport) {
            this.anchorNode = anchorNode;
            this.side = side;
            this.hitVector = hitVector;
            this.queuedSupport = queuedSupport;
        }

        public BlockPos getAnchorNode() { return this.anchorNode; }
        public EnumFacing getSide() { return this.side; }
        public Vec3 getHitVector() { return this.hitVector; }
        public boolean usesQueuedSupport() { return this.queuedSupport; }
    }
}

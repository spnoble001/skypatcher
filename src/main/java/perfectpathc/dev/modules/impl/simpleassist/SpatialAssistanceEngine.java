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
import perfectpathc.dev.modules.impl.utils.GlobalSpatialAssistance;
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
    /** Projection first, then center and four stable interior subpixels. */
    private static final double[][] VIRTUAL_FACE_SAMPLES = {
            {0.50D, 0.50D},
            {0.15D, 0.15D}, {0.85D, 0.15D},
            {0.85D, 0.85D}, {0.15D, 0.85D}
    };
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
        // Acquisition and dispatch share the same non-negotiable physical
        // envelope. A controller reach above this value must not let a
        // virtual target enter a route that execution would later reject.
        double physicalReach = Math.min(reach, maximumPhysicalReach());
        Vec3 eyePos = new Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        Vec3 lookVec = player.getLookVec();
        Vec3 rayEnd = eyePos.addVector(lookVec.xCoord * physicalReach,
                lookVec.yCoord * physicalReach, lookVec.zCoord * physicalReach);
        int padding = (int)Math.ceil(radius) + 1;
        BlockPos minimum = new BlockPos(Math.min(eyePos.xCoord, rayEnd.xCoord) - padding,
                Math.min(eyePos.yCoord, rayEnd.yCoord) - padding,
                Math.min(eyePos.zCoord, rayEnd.zCoord) - padding);
        BlockPos maximum = new BlockPos(Math.max(eyePos.xCoord, rayEnd.xCoord) + padding,
                Math.max(eyePos.yCoord, rayEnd.yCoord) + padding,
                Math.max(eyePos.zCoord, rayEnd.zCoord) + padding);

        // The world scan is deliberately cheap: retain only the three faces
        // geometrically closest to the visual ray. LOS is evaluated later and
        // only for these bounded finalists.
        BlockPos firstAnchor = null, secondAnchor = null, thirdAnchor = null;
        EnumFacing firstSide = null, secondSide = null, thirdSide = null;
        double firstDistance = Double.MAX_VALUE;
        double secondDistance = Double.MAX_VALUE;
        double thirdDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos exposedCell = new BlockPos.MutableBlockPos();
        for (BlockPos.MutableBlockPos cursor : BlockPos.getAllInBoxMutable(minimum, maximum)) {
            if (!isWithinBlockReach(eyePos, cursor, physicalReach)
                    || !isSolidSupport(minecraft.theWorld, cursor)) continue;
            // 1.8.9 MutableBlockPos has no toImmutable() helper. Do not pass
            // a mutable coordinate into the caller predicate or retain it.
            BlockPos anchor = new BlockPos(cursor.getX(), cursor.getY(), cursor.getZ());
            for (EnumFacing side : EnumFacing.VALUES) {
                if (requiredTarget != null && (requiredTarget.getX() != anchor.getX() + side.getFrontOffsetX()
                        || requiredTarget.getY() != anchor.getY() + side.getFrontOffsetY()
                        || requiredTarget.getZ() != anchor.getZ() + side.getFrontOffsetZ())) continue;
                if (filter != null && !filter.matches(anchor, side)) continue;
                if (!isFaceFacingEye(eyePos, anchor, side)) continue;
                if (!isExposedFace(minecraft.theWorld, anchor, side, exposedCell)) continue;
                double distance = minimumVirtualFaceDistanceToRay(eyePos, lookVec, anchor, side,
                        physicalReach);
                // This evaluates the exact finite sample set without a world
                // raytrace, so rejecting it cannot hide a later valid sample.
                if (distance > radius) continue;
                if (distance < firstDistance) {
                    thirdAnchor = secondAnchor; thirdSide = secondSide; thirdDistance = secondDistance;
                    secondAnchor = firstAnchor; secondSide = firstSide; secondDistance = firstDistance;
                    firstAnchor = anchor; firstSide = side; firstDistance = distance;
                } else if (distance < secondDistance) {
                    thirdAnchor = secondAnchor; thirdSide = secondSide; thirdDistance = secondDistance;
                    secondAnchor = anchor; secondSide = side; secondDistance = distance;
                } else if (distance < thirdDistance) {
                    thirdAnchor = anchor; thirdSide = side; thirdDistance = distance;
                }
            }
        }

        TargetPlacement placement = validateVirtualFinalist(minecraft.theWorld, eyePos, lookVec,
                firstAnchor, firstSide, physicalReach, radius);
        if (placement != null) return placement;
        placement = validateVirtualFinalist(minecraft.theWorld, eyePos, lookVec,
                secondAnchor, secondSide, physicalReach, radius);
        if (placement != null) return placement;
        return validateVirtualFinalist(minecraft.theWorld, eyePos, lookVec,
                thirdAnchor, thirdSide, physicalReach, radius);
    }

    /** Performs the only costly LOS phase, after cheap top-three selection. */
    private static TargetPlacement validateVirtualFinalist(World world, Vec3 eye, Vec3 look,
                                                           BlockPos anchor, EnumFacing side,
                                                           double reach, double radius) {
        if (anchor == null || side == null) return null;
        FaceSample sample = findVirtualFaceSample(world, eye, look, anchor, side, reach, radius);
        return sample == null ? null : new TargetPlacement(anchor, side, sample.hitVector);
    }

    /** True when at least one point of the block volume can satisfy physical reach. */
    private static boolean isWithinBlockReach(Vec3 eye, BlockPos block, double reach) {
        double dx = eye.xCoord < block.getX() ? block.getX() - eye.xCoord
                : eye.xCoord > block.getX() + 1.0D ? eye.xCoord - (block.getX() + 1.0D) : 0.0D;
        double dy = eye.yCoord < block.getY() ? block.getY() - eye.yCoord
                : eye.yCoord > block.getY() + 1.0D ? eye.yCoord - (block.getY() + 1.0D) : 0.0D;
        double dz = eye.zCoord < block.getZ() ? block.getZ() - eye.zCoord
                : eye.zCoord > block.getZ() + 1.0D ? eye.zCoord - (block.getZ() + 1.0D) : 0.0D;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /** Poda caras que un rayo desde el ojo no puede alcanzar como primera superficie. */
    private static boolean isFaceFacingEye(Vec3 eye, BlockPos anchor, EnumFacing side) {
        final double epsilon = 0.001D;
        switch (side) {
            case DOWN: return eye.yCoord <= anchor.getY() + epsilon;
            case UP: return eye.yCoord >= anchor.getY() + 1.0D - epsilon;
            case NORTH: return eye.zCoord <= anchor.getZ() + epsilon;
            case SOUTH: return eye.zCoord >= anchor.getZ() + 1.0D - epsilon;
            case WEST: return eye.xCoord <= anchor.getX() + epsilon;
            case EAST: return eye.xCoord >= anchor.getX() + 1.0D - epsilon;
            default: return false;
        }
    }

    /** Cheap counterpart of findVirtualFaceSample: it performs no world access or LOS. */
    private static double minimumVirtualFaceDistanceToRay(Vec3 eye, Vec3 look,
                                                          BlockPos support, EnumFacing side,
                                                          double reach) {
        double minimumX = support.getX() + FACE_INTERIOR_INSET;
        double maximumX = support.getX() + 1.0D - FACE_INTERIOR_INSET;
        double minimumY = support.getY() + FACE_INTERIOR_INSET;
        double maximumY = support.getY() + 1.0D - FACE_INTERIOR_INSET;
        double minimumZ = support.getZ() + FACE_INTERIOR_INSET;
        double maximumZ = support.getZ() + 1.0D - FACE_INTERIOR_INSET;
        double projectedX;
        double projectedY;
        double projectedZ;
        switch (side) {
            case DOWN:
                projectedX = clamp(eye.xCoord, minimumX, maximumX);
                projectedY = support.getY();
                projectedZ = clamp(eye.zCoord, minimumZ, maximumZ);
                break;
            case UP:
                projectedX = clamp(eye.xCoord, minimumX, maximumX);
                projectedY = support.getY() + 1.0D;
                projectedZ = clamp(eye.zCoord, minimumZ, maximumZ);
                break;
            case NORTH:
                projectedX = clamp(eye.xCoord, minimumX, maximumX);
                projectedY = clamp(eye.yCoord, minimumY, maximumY);
                projectedZ = support.getZ();
                break;
            case SOUTH:
                projectedX = clamp(eye.xCoord, minimumX, maximumX);
                projectedY = clamp(eye.yCoord, minimumY, maximumY);
                projectedZ = support.getZ() + 1.0D;
                break;
            case WEST:
                projectedX = support.getX();
                projectedY = clamp(eye.yCoord, minimumY, maximumY);
                projectedZ = clamp(eye.zCoord, minimumZ, maximumZ);
                break;
            case EAST:
                projectedX = support.getX() + 1.0D;
                projectedY = clamp(eye.yCoord, minimumY, maximumY);
                projectedZ = clamp(eye.zCoord, minimumZ, maximumZ);
                break;
            default:
                return Double.MAX_VALUE;
        }
        double closest = admissibleFacePointDistanceToRay(eye, look,
                projectedX, projectedY, projectedZ, reach);
        for (double[] coordinates : VIRTUAL_FACE_SAMPLES) {
            double u = coordinates[0];
            double v = coordinates[1];
            double sampleX;
            double sampleY;
            double sampleZ;
            switch (side) {
                case DOWN: sampleX = support.getX() + u; sampleY = support.getY(); sampleZ = support.getZ() + v; break;
                case UP: sampleX = support.getX() + u; sampleY = support.getY() + 1.0D; sampleZ = support.getZ() + v; break;
                case NORTH: sampleX = support.getX() + u; sampleY = support.getY() + v; sampleZ = support.getZ(); break;
                case SOUTH: sampleX = support.getX() + u; sampleY = support.getY() + v; sampleZ = support.getZ() + 1.0D; break;
                case WEST: sampleX = support.getX(); sampleY = support.getY() + u; sampleZ = support.getZ() + v; break;
                case EAST: sampleX = support.getX() + 1.0D; sampleY = support.getY() + u; sampleZ = support.getZ() + v; break;
                default: continue;
            }
            double distance = admissibleFacePointDistanceToRay(eye, look,
                    sampleX, sampleY, sampleZ, reach);
            if (distance < closest) closest = distance;
        }
        return closest;
    }

    /** Applies all cheap hard constraints; LOS remains exclusive to the final phase. */
    private static double admissibleFacePointDistanceToRay(Vec3 eye, Vec3 look,
                                                           double pointX, double pointY,
                                                           double pointZ, double reach) {
        double lookLengthSquared = look.xCoord * look.xCoord + look.yCoord * look.yCoord
                + look.zCoord * look.zCoord;
        if (lookLengthSquared <= 0.0D) return Double.MAX_VALUE;
        double forward = ((pointX - eye.xCoord) * look.xCoord
                + (pointY - eye.yCoord) * look.yCoord
                + (pointZ - eye.zCoord) * look.zCoord)
                / lookLengthSquared;
        if (forward < 0.0D || forward > reach
                || squareDistance(eye, pointX, pointY, pointZ) > reach * reach) return Double.MAX_VALUE;
        double closestX = eye.xCoord + look.xCoord * forward;
        double closestY = eye.yCoord + look.yCoord * forward;
        double closestZ = eye.zCoord + look.zCoord * forward;
        double offsetX = pointX - closestX;
        double offsetY = pointY - closestY;
        double offsetZ = pointZ - closestZ;
        return Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
    }

    private static double squareDistance(Vec3 origin, double x, double y, double z) {
        double dx = x - origin.xCoord;
        double dy = y - origin.yCoord;
        double dz = z - origin.zCoord;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * A virtual cone is a distance-to-ray constraint, not a closest-to-eye
     * constraint. Evaluate a bounded face matrix so a valid exposed subpixel
     * cannot be discarded merely because the eye projection falls outside the
     * cone or grazes a solid block edge.
     */
    private static FaceSample findVirtualFaceSample(World world, Vec3 eye, Vec3 look,
                                                    BlockPos support, EnumFacing side,
                                                    double reach, double radius) {
        Vec3 projected = nearestPointOnFace(eye, support, side);
        FaceSample projectedSample = validateVirtualFacePoint(world, eye, look, projected,
                support, side, reach, radius);
        // The eye projection is the most stable and natural C08 point. Face
        // samples exist only to recover a geometrically valid exposed margin
        // when that primary point is not admissible.
        if (projectedSample != null) return projectedSample;

        Vec3 bestPoint = null;
        double bestDistanceToRay = Double.MAX_VALUE;
        for (double[] coordinates : VIRTUAL_FACE_SAMPLES) {
            Vec3 sample = pointOnFace(support, side, coordinates[0], coordinates[1]);
            FaceSample valid = validateVirtualFacePoint(world, eye, look, sample,
                    support, side, reach, radius);
            if (valid != null && valid.distanceToRay < bestDistanceToRay) {
                bestPoint = valid.hitVector;
                bestDistanceToRay = valid.distanceToRay;
            }
        }
        return bestPoint == null ? null : new FaceSample(bestPoint, bestDistanceToRay);
    }

    private static FaceSample validateVirtualFacePoint(World world, Vec3 eye, Vec3 look,
                                                        Vec3 hit, BlockPos support,
                                                        EnumFacing side, double reach,
                                                        double radius) {
        double forward = forwardDistance(eye, look, hit);
        double distanceToRay = distanceToLookRay(eye, look, hit);
        if (forward < 0.0D || forward > reach || eye.squareDistanceTo(hit) > reach * reach
                || distanceToRay > radius
                || !hasUnobstructedLineOfSight(world, eye, hit, support, side)) return null;
        return new FaceSample(hit, distanceToRay);
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
        double maximumReach = maximumPhysicalReach();
        if (eyePos.squareDistanceTo(hitVec) > maximumReach * maximumReach) return false;

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

    /** Maps stable local (u,v) face coordinates to an exact world hit point. */
    private static Vec3 pointOnFace(BlockPos position, EnumFacing side, double u, double v) {
        switch (side) {
            case DOWN: return new Vec3(position.getX() + u, position.getY(), position.getZ() + v);
            case UP: return new Vec3(position.getX() + u, position.getY() + 1.0D, position.getZ() + v);
            case NORTH: return new Vec3(position.getX() + u, position.getY() + v, position.getZ());
            case SOUTH: return new Vec3(position.getX() + u, position.getY() + v, position.getZ() + 1.0D);
            case WEST: return new Vec3(position.getX(), position.getY() + u, position.getZ() + v);
            case EAST: return new Vec3(position.getX() + 1.0D, position.getY() + u, position.getZ() + v);
            default: return new Vec3(position.getX() + 0.5D, position.getY() + 0.5D,
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

    /** Legacy projection stays primary; stable face corners are fallback only. */
    private static Vec3 fallbackFacePoint(World world, Vec3 eye, Vec3 look, BlockPos pos,
                                          EnumFacing side, double reach, boolean cone) {
        for (double[] coordinates : VIRTUAL_FACE_SAMPLES) {
            // The execution primary already tried the eye projection. Keep
            // this fallback restricted to the four interior corners.
            if (coordinates[0] == 0.50D && coordinates[1] == 0.50D) continue;
            Vec3 hit = pointOnFace(pos, side, coordinates[0], coordinates[1]);
            if (isExecutionPointValid(world, eye, look, hit, pos, side, reach, cone, false)) {
                return hit;
            }
        }
        return null;
    }


    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double maximumPhysicalReach() {
        return GlobalSpatialAssistance.maximumPhysicalReach();
    }

    private static boolean hasRuntime(Minecraft minecraft) {
        return minecraft != null && minecraft.thePlayer != null && minecraft.theWorld != null;
    }

    private static boolean isSolidSupport(World world, BlockPos position) {
        IBlockState state = world.getBlockState(position);
        Block block = state.getBlock();
        return !block.isReplaceable(world, position) && block.canCollideCheck(state, false);
    }

    private static boolean isExposedFace(World world, BlockPos anchor, EnumFacing side,
                                         BlockPos.MutableBlockPos scratch) {
        scratch.set(anchor.getX() + side.getFrontOffsetX(), anchor.getY() + side.getFrontOffsetY(),
                anchor.getZ() + side.getFrontOffsetZ());
        IBlockState state = world.getBlockState(scratch);
        return state.getBlock().isReplaceable(world, scratch);
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

    /** A face point accepted by the virtual acquisition constraints. */
    private static final class FaceSample {
        private final Vec3 hitVector;
        private final double distanceToRay;

        private FaceSample(Vec3 hitVector, double distanceToRay) {
            this.hitVector = hitVector;
            this.distanceToRay = distanceToRay;
        }
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

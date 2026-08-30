package perfectpathc.dev.modules.impl;

import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;
import perfectpathc.dev.modules.impl.events.XenonEvent;
import perfectpathc.dev.modules.impl.simpleassist.SpatialAssistanceEngine;
import perfectpathc.dev.modules.impl.utils.AnchorQuarantineRegistry;
import perfectpathc.dev.modules.impl.utils.GlobalCps;
import perfectpathc.dev.modules.impl.utils.RightClickCoordinator;
import perfectpathc.dev.modules.runtime.IModuleEntry;

/** Local transactional PrePatch. It has no global-route or PatchCrumbs dependency. */
@Mod(keybind = 0)
public final class PrePatchV2 extends Module implements RightClickCoordinator.Owner, IModuleEntry {
    private static final int INPUT_PRIORITY = 33;
    private static final int INTENT_LEASE_TICKS = 2;
    private static final int PENDING_TIMEOUT_TICKS = 10;

    private final SpatialAssistanceEngine spatial = new SpatialAssistanceEngine();
    private long sessionAnchor = Long.MIN_VALUE;
    private int sessionFace = -1;
    private int sessionTick = Integer.MIN_VALUE;
    private int intentLeaseUntil = Integer.MIN_VALUE;
    private boolean lastRightDown;
    private int nextPermitTick = Integer.MIN_VALUE;
    private boolean placementPermit;

    private long previewCandidate = Long.MIN_VALUE;
    private SpatialAssistanceEngine.TargetPlacement previewPlacement;
    private double previewScore = Double.MAX_VALUE;

    private long pendingTarget = Long.MIN_VALUE;
    private int pendingBlockId = -1;
    private int pendingSince = Integer.MIN_VALUE;

    public PrePatchV2() {
        this.sl.add(new Settings("Keybind".toCharArray(), this));
        this.sl.add(new Settings("Dirección".toCharArray(), this,
                "East/West", "East/West", "North/South"));
        this.sl.add(new Settings("Spread Rango".toCharArray(), this, 2F, 3F, 1F, 8F, 1F));
        this.sl.add(new Settings("Manual".toCharArray(), this, false));
        this.sl.add(new Settings("Debug".toCharArray(), this, true));
        registerSettings();
        this.name = "PrePatch V2";
    }

    @Override public Object invokeCommand(int commandId, Object... args) {
        if (commandId == 11) {
            onClientTick((TickEvent.ClientTickEvent)args[0]);
            return null;
        }
        if (commandId == 23) {
            return "PrePatchV2 anchor=" + this.sessionAnchor + " face=" + this.sessionFace
                    + " lease=" + this.intentLeaseUntil + " candidate=" + this.previewCandidate
                    + " score=" + this.previewScore + " permit=" + this.placementPermit
                    + " pending=" + this.pendingTarget + " manual=" + setting(3).getValBoolean();
        }
        return super.invokeCommand(commandId, args);
    }

    @XenonEvent public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!this.enabled || event.phase != TickEvent.Phase.START || !hasWorld()) return;
        int tick = this.minecraft.thePlayer.ticksExisted;
        this.spatial.onPreMotionUpdate();
        AnchorQuarantineRegistry.shared().observe(this.minecraft.theWorld, tick);
        if (!isPatchBlockHeld()) {
            reset();
            return;
        }

        captureAnchor(this.minecraft.objectMouseOver, tick);
        confirmPending(tick);

        boolean rightDown = Mouse.isButtonDown(1);
        if (rightDown && !this.lastRightDown) this.nextPermitTick = tick;
        if (rightDown) this.intentLeaseUntil = tick + INTENT_LEASE_TICKS;
        else this.intentLeaseUntil = Integer.MIN_VALUE;
        this.lastRightDown = rightDown;

        selectReadOnlyCandidate();
        boolean manual = setting(3).getValBoolean();
        boolean liveIntent = !manual || tick <= this.intentLeaseUntil;
        this.placementPermit = liveIntent && this.pendingTarget == Long.MIN_VALUE
                && this.previewCandidate != Long.MIN_VALUE && tick >= this.nextPermitTick;
        if (this.placementPermit) {
            this.nextPermitTick = tick + Math.max(1,
                    Math.round(20.0F / Math.max(1.0F, GlobalCps.get())));
        }

        if (liveIntent && this.previewCandidate != Long.MIN_VALUE) {
            RightClickCoordinator.acquire(this, INPUT_PRIORITY);
        } else {
            RightClickCoordinator.release(this);
        }
        if (this.placementPermit && RightClickCoordinator.isOwner(this)) dispatchPreview(tick);
    }

    private void captureAnchor(MovingObjectPosition hit, int tick) {
        if (!isLocalAnchor(hit)) return;
        long position = hit.getBlockPos().toLong();
        int face = hit.sideHit.getIndex();
        if (position != this.sessionAnchor || face != this.sessionFace) {
            clearPending();
            this.previewCandidate = Long.MIN_VALUE;
            this.previewPlacement = null;
            this.nextPermitTick = tick;
        }
        this.sessionAnchor = position;
        this.sessionFace = face;
        this.sessionTick = tick;
    }

    /** Evaluates exactly six immutable neighbours of the captured anchor. */
    private void selectReadOnlyCandidate() {
        this.previewCandidate = Long.MIN_VALUE;
        this.previewPlacement = null;
        this.previewScore = Double.MAX_VALUE;
        if (this.sessionAnchor == Long.MIN_VALUE) return;

        BlockPos anchor = BlockPos.fromLong(this.sessionAnchor);
        EnumFacing normal = EnumFacing.getFront(this.sessionFace);
        BlockPos meta = anchor.offset(normal);
        BlockPos[] candidates = new BlockPos[] {
                anchor.offset(normal),
                anchor.offset(normal, 2),
                anchor.up(), anchor.down(),
                anchor.offset(normal.rotateY()),
                anchor.offset(normal.rotateYCCW())
        };
        Vec3 eye = eye();
        Vec3 look = this.minecraft.thePlayer.getLookVec();
        for (BlockPos target : candidates) {
            if (target.toLong() == this.pendingTarget || !isReplaceable(target)) continue;
            SpatialAssistanceEngine.TargetPlacement placement = SpatialAssistanceEngine.findExecutionPlacement(
                    this.minecraft, target, Math.min(4.5D,
                            this.minecraft.playerController.getBlockReachDistance()));
            if (placement == null) continue;
            Vec3 hit = placement.getHitVector();
            double reach = Math.sqrt(eye.squareDistanceTo(hit));
            if (reach > 4.5D) continue;
            double dx = hit.xCoord - eye.xCoord;
            double dy = hit.yCoord - eye.yCoord;
            double dz = hit.zCoord - eye.zCoord;
            double lookLength = Math.sqrt(look.xCoord * look.xCoord + look.yCoord * look.yCoord
                    + look.zCoord * look.zCoord);
            double dot = (dx * look.xCoord + dy * look.yCoord + dz * look.zCoord)
                    / Math.max(0.00001D, reach * lookLength);
            double angleCost = Math.acos(clamp(dot));
            double score = 40.0D * target.distanceSq(meta)
                    + 2.0D * (reach / 4.5D)
                    + 3.5D * angleCost
                    + behindMotion(hit);
            if (score < this.previewScore) {
                this.previewScore = score;
                this.previewCandidate = target.toLong();
                this.previewPlacement = placement;
            }
        }
    }

    /** One permit enters and leaves this method exactly once; it is never queued. */
    private void dispatchPreview(int tick) {
        this.placementPermit = false;
        ItemStack held = this.minecraft.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock) || this.previewPlacement == null) return;
        final BlockPos support = this.previewPlacement.getAnchorNode();
        final EnumFacing side = this.previewPlacement.getSide();
        final Vec3 hit = this.previewPlacement.getHitVector();
        final ItemStack item = held;
        boolean sent = this.spatial.processInteraction(this.minecraft, support, side, hit,
                this.previewPlacement.usesQueuedSupport(), new SpatialAssistanceEngine.InteractionDispatcher() {
                    @Override public boolean dispatch() {
                        return PrePatchV2.this.minecraft.playerController.onPlayerRightClick(
                                PrePatchV2.this.minecraft.thePlayer, PrePatchV2.this.minecraft.theWorld,
                                item, support, side, hit);
                    }
                });
        if (!sent) return;
        this.pendingTarget = this.previewCandidate;
        this.pendingBlockId = SimpleAssistMinecraftAccess.getHeldBlockId(held);
        this.pendingSince = tick;
        AnchorQuarantineRegistry.shared().expectPrePatchPlacement(this.pendingTarget,
                side.getIndex(), this.pendingBlockId, tick);
        this.minecraft.thePlayer.swingItem();
    }

    private void confirmPending(int tick) {
        if (this.pendingTarget == Long.MIN_VALUE) return;
        int actual = SimpleAssistMinecraftAccess.blockId(
                SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, this.pendingTarget));
        if (actual == this.pendingBlockId || tick - this.pendingSince >= PENDING_TIMEOUT_TICKS) {
            clearPending();
        }
    }

    private boolean isLocalAnchor(MovingObjectPosition hit) {
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null || hit.sideHit == null
                || hit.sideHit.getFrontOffsetY() != 0) return false;
        EnumFacing side = hit.sideHit;
        boolean eastWest = setting(1).getModeIndex() == 0;
        if (eastWest && side != EnumFacing.EAST && side != EnumFacing.WEST) return false;
        if (!eastWest && side != EnumFacing.NORTH && side != EnumFacing.SOUTH) return false;
        BlockPos anchor = hit.getBlockPos();
        return this.minecraft.theWorld.getBlockState(anchor).getBlock() == Blocks.obsidian
                && !AnchorQuarantineRegistry.shared().isQuarantined(this.minecraft.theWorld,
                anchor.toLong(), side.getIndex(), this.minecraft.thePlayer.ticksExisted)
                && isReplaceable(anchor.offset(side))
                && isReplaceable(anchor.offset(side.getOpposite()));
    }

    private double behindMotion(Vec3 hit) {
        double mx = this.minecraft.thePlayer.motionX, mz = this.minecraft.thePlayer.motionZ;
        double tx = hit.xCoord - this.minecraft.thePlayer.posX;
        double tz = hit.zCoord - this.minecraft.thePlayer.posZ;
        double motionSq = mx * mx + mz * mz, targetSq = tx * tx + tz * tz;
        if (motionSq <= 0.0001D || targetSq <= 0.0001D) return 0.0D;
        double dot = (mx * tx + mz * tz) / Math.sqrt(motionSq * targetSq);
        return Math.max(0.0D, -dot) * 5.0D;
    }

    private boolean hasWorld() {
        return this.minecraft.thePlayer != null && this.minecraft.theWorld != null
                && this.minecraft.playerController != null;
    }
    private boolean isPatchBlockHeld() {
        ItemStack held = this.minecraft.thePlayer.getHeldItem();
        return held != null && held.stackSize > 0 && held.getItem() instanceof ItemBlock
                && NativeBridge.isSimpleAssistAcceptedBlock(SimpleAssistMinecraftAccess.getHeldBlockId(held));
    }
    private boolean isReplaceable(BlockPos position) {
        Material material = this.minecraft.theWorld.getBlockState(position).getBlock().getMaterial();
        return material == Material.air || material == Material.water || material.isReplaceable();
    }
    private Vec3 eye() {
        return new Vec3(this.minecraft.thePlayer.posX,
                this.minecraft.thePlayer.posY + this.minecraft.thePlayer.getEyeHeight(),
                this.minecraft.thePlayer.posZ);
    }
    private static double clamp(double value) { return Math.max(-1.0D, Math.min(1.0D, value)); }
    private Settings setting(int index) { return this.sl.get(index); }
    private void clearPending() {
        this.pendingTarget = Long.MIN_VALUE;
        this.pendingBlockId = -1;
        this.pendingSince = Integer.MIN_VALUE;
    }
    private void reset() {
        RightClickCoordinator.release(this);
        this.spatial.reset();
        this.sessionAnchor = Long.MIN_VALUE;
        this.sessionFace = -1;
        this.sessionTick = Integer.MIN_VALUE;
        this.intentLeaseUntil = Integer.MIN_VALUE;
        this.nextPermitTick = Integer.MIN_VALUE;
        this.placementPermit = false;
        this.previewCandidate = Long.MIN_VALUE;
        this.previewPlacement = null;
        this.previewScore = Double.MAX_VALUE;
        clearPending();
    }
    @Override public void onDisable() { reset(); }
    @Override public void onSettingsChanged() { if (this.enabled) reset(); }
    @Override public void onRightClickControlLost() {
        this.intentLeaseUntil = Integer.MIN_VALUE;
        this.placementPermit = false;
    }
    @Override public char getSort() { return 'q'; }
}

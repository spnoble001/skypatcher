package perfectpathc.dev.modules.impl;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.input.Mouse;
import perfectpathc.dev.PerfectPatch;
import perfectpathc.dev.modules.impl.events.XenonEvent;
import perfectpathc.dev.modules.impl.ui.ClickGuiObj;
import perfectpathc.dev.modules.impl.utils.GlobalCps;
import perfectpathc.dev.modules.impl.utils.RightClickCoordinator;
import perfectpathc.dev.modules.impl.utils.AnchorQuarantineRegistry;

import java.util.Arrays;

/** Finds exposed obsidian faces in a small, continuously refreshed local volume. */
@Mod(keybind = 0)
public final class PrePatch extends Module implements RightClickCoordinator.Owner {
    private static final int SKY_PATCHER_MODULE_INDEX = 1;
    /** Twelve blocks per axis: enough margin for every normal placement reach. */
    private static final int SCAN_MIN_OFFSET = -6;
    private static final int SCAN_MAX_OFFSET = 5;
    private static final int SCAN_HALF_EXTENT = 6;
    /** Same cardinality as native SCAN_VOLUME_POSITION_COUNT. */
    private static final int SCAN_VOLUME_POSITION_COUNT = 1728;
    /** Native state is also capped; these bounds protect the render hot path. */
    private static final int MAX_RENDER_BLOCKS = 256;
    private static final int MAX_RENDER_FACES = 512;
    private static final long[] SCAN_OFFSETS_BY_DISTANCE = buildScanOffsetsByDistance();
    private static final int CLICKER_PRIORITY = 20;
    private static final double RENDER_DISTANCE_SQ = 121.0D;
    private static final int WORK_BEGIN_PLACEMENT_CHECKS = 0;
    private static final int WORK_PLACEMENT_RESULT = 4;
    private static final int WORK_BEGIN_LOCAL_REFRESHES = 8;
    private static final int WORK_LOCAL_REFRESH_RESULT = 16;
    private static final int CLICKER_OPERATION_NEXT = 0;
    private static final int CLICKER_OPERATION_DEACTIVATE = 1;
    private static final int CLICKER_OPERATION_RESET_CONTROL = 2;
    private static final int CLICKER_ACTION_RELEASE = 1;
    private static final int CLICKER_ACTION_CLICK = 2;
    private static final int SETTING_RENDER = 5;
    private static final int INPUT_IDLE = 0;
    private static final int INPUT_PATCH_OWNED = 1;
    private static final int INPUT_POST_PATCH_GUARD = 2;
    private static final int INPUT_MANUAL_CLICKER = 3;
    /** A real re-aim hands held RMB back to the ordinary vertical clicker. */
    private static final float MANUAL_HANDOFF_ANGLE = 16.0F;
    /** Absorbs the single residual frame following an assisted C08. */
    private static final int POST_PATCH_GUARD_TICKS = 1;
    private final BlockPos.MutableBlockPos scanCursor = new BlockPos.MutableBlockPos();
    /** World instances are replaced on disconnect/dimension reload. */
    private World scanWorld;
    private int inputState = INPUT_IDLE;
    private long patchRaySignature = Long.MIN_VALUE;
    private float patchYaw;
    private float patchPitch;
    private int guardSinceTick = Integer.MIN_VALUE;

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        switch (commandId) {
            case 6: return Boolean.valueOf(isVisibleAnchor(((Long) args[0]).longValue(), ((Integer) args[1]).intValue()));
            case 11: onClientTick((TickEvent.ClientTickEvent) args[0]); return null;
            case 14: onRenderOverlay((RenderGameOverlayEvent) args[0]); return null;
            case 15: onRenderWorld((RenderWorldLastEvent) args[0]); return null;
            case 18: return getAnchorDebugState();
            case 19: return flipDirection();
            case 20: applyPatchPresetOne(); return null;
            default: return super.invokeCommand(commandId, args);
        }
    }

    public PrePatch() {
        this.sl.add(new Settings("Keybind".toCharArray(), this));
        this.sl.add(new Settings("Dirección".toCharArray(), this,
                "East/West", "East/West", "North/South"));
        this.sl.add(new Settings("Spread Rango".toCharArray(), this,
                2.0F, 3.0F, 1.0F, 8.0F, 1.0F));
        this.sl.add(new Settings("Manual".toCharArray(), this, false));
        this.sl.add(new Settings("Click Blocks".toCharArray(), this, true));
        this.sl.add(new Settings("Render".toCharArray(), this, true));
        registerSettings();
        this.name = "PrePatch";
    }

    @Override
    public char getSort() {
        return 'p';
    }

    @Override
    public void onEnable() {
        this.scanWorld = null;
        clearAnchors();
        resetInputHandoff();
    }

    @Override
    public void onDisable() {
        this.scanWorld = null;
        clearAnchors();
        deactivateClicker();
        resetInputHandoff();
    }

    @XenonEvent
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent event) {
        if (!(event instanceof RenderGameOverlayEvent.Post)
                || event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        if (!this.enabled || !isSkyPatcherEnabled()) {
            deactivateClicker();
            resetInputHandoff();
            return;
        }
        if (this.minecraft.thePlayer == null || this.minecraft.theWorld == null
                || !this.minecraft.inGameHasFocus
                || this.minecraft.currentScreen instanceof ClickGuiObj
                || !heldItemAllowed()) {
            deactivateClicker();
            resetInputHandoff();
            return;
        }
        if (!Mouse.isButtonDown(1)) {
            deactivateClicker();
            resetInputHandoff();
            return;
        }
        boolean anchored = prePatchAnchorUnderCrosshair();
        long raySignature = physicalRaySignature();
        advanceInputHandoff(anchored, raySignature);
        if (this.inputState != INPUT_MANUAL_CLICKER) {
            // The active patch executor owns the assisted C05/C08.  During
            // PATCH_OWNED and its guard, PrePatch must never append a generic
            // held-RMB click to that placement sequence.
            deactivateClicker();
            return;
        }
        runManualClicker();
    }

    /** Normal vertical/free-build rightclicker, entered only after handoff. */
    private void runManualClicker() {
        if (!RightClickCoordinator.acquire(this, CLICKER_PRIORITY)) {
            resetClickerState();
            return;
        }
        if (this.minecraft.thePlayer.isUsingItem()) {
            // Never leave the generated RMB press active behind an external
            // item-use state. Doing so self-locks every later placement.
            deactivateClicker();
            return;
        }
        long now = System.currentTimeMillis();
        int action = NativeBridge.updatePrePatchClicker(
                CLICKER_OPERATION_NEXT, now, GlobalCps.get());
        if ((action & CLICKER_ACTION_RELEASE) != 0) {
            releaseUseKey();
        }
        if ((action & CLICKER_ACTION_CLICK) != 0) {
            click();
        }
    }

    /**
     * Gives an exposed PrePatch anchor precedence, then absorbs the residual
     * post-assist click until the user provides a meaningful new aim vector.
     */
    private void advanceInputHandoff(boolean anchored, long raySignature) {
        int tick = this.minecraft.thePlayer.ticksExisted;
        if (anchored) {
            if (this.inputState != INPUT_PATCH_OWNED) {
                this.patchRaySignature = raySignature;
                this.patchYaw = this.minecraft.thePlayer.rotationYaw;
                this.patchPitch = this.minecraft.thePlayer.rotationPitch;
            }
            this.inputState = INPUT_PATCH_OWNED;
            this.guardSinceTick = Integer.MIN_VALUE;
            return;
        }
        if (this.inputState == INPUT_PATCH_OWNED) {
            this.inputState = INPUT_POST_PATCH_GUARD;
            this.guardSinceTick = tick;
            return;
        }
        if (this.inputState != INPUT_POST_PATCH_GUARD) {
            this.inputState = INPUT_MANUAL_CLICKER;
            return;
        }
        if (hasManualHandoffIntent(raySignature, tick)) {
            this.inputState = INPUT_MANUAL_CLICKER;
        }
    }

    private boolean hasManualHandoffIntent(long raySignature, int tick) {
        float angle = rotationDistance(this.minecraft.thePlayer.rotationYaw,
                this.minecraft.thePlayer.rotationPitch, this.patchYaw, this.patchPitch);
        if (angle >= MANUAL_HANDOFF_ANGLE) return true;
        // A block/face change is strong intent, but wait one complete client
        // tick first so a world update caused by the preceding assisted C08
        // cannot leak an immediate duplicate placement.
        return raySignature != Long.MIN_VALUE && raySignature != this.patchRaySignature
                && this.guardSinceTick != Integer.MIN_VALUE
                && tick - this.guardSinceTick >= POST_PATCH_GUARD_TICKS;
    }

    private void resetInputHandoff() {
        this.inputState = INPUT_IDLE;
        this.patchRaySignature = Long.MIN_VALUE;
        this.patchYaw = 0.0F;
        this.patchPitch = 0.0F;
        this.guardSinceTick = Integer.MIN_VALUE;
    }

    /** The handoff uses only physical ray data; synthetic targets cannot spoof intent. */
    private long physicalRaySignature() {
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

    private boolean heldItemAllowed() {
        if (!setting(4).getValBoolean()) {
            return true;
        }
        return this.minecraft.thePlayer.getHeldItem() != null
                && this.minecraft.thePlayer.getHeldItem().getItem() instanceof ItemBlock;
    }

    private boolean prePatchAnchorUnderCrosshair() {
        MovingObjectPosition hit = this.minecraft.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null || hit.sideHit == null
                || this.minecraft.theWorld == null) {
            return false;
        }
        boolean eastWest = setting(1).getModeIndex() == 0;
        int face = hit.sideHit.ordinal();
        if ((eastWest && face != 4 && face != 5)
                || (!eastWest && face != 2 && face != 3)) {
            return false;
        }
        BlockPos position = hit.getBlockPos();
        if (AnchorQuarantineRegistry.shared().isQuarantined(this.minecraft.theWorld,
                position.toLong(), face, this.minecraft.thePlayer.ticksExisted)) {
            return false;
        }
        this.scanCursor.set(position.getX(), position.getY(), position.getZ());
        return this.minecraft.theWorld.isBlockLoaded(this.scanCursor)
                && this.minecraft.theWorld.getBlockState(this.scanCursor).getBlock() == Blocks.obsidian
                && isFaceExposed(position.getX(), position.getY(), position.getZ(),
                        hit.sideHit.getFrontOffsetX(), hit.sideHit.getFrontOffsetZ())
                && isVisibleAnchor(position.toLong(), face);
    }

    private void click() {
        // This is PrePatch's own synthetic click path.  Capture a short-lived
        // expectation before injecting it; quarantine still starts only once
        // observe() sees the resulting block in the local world.
        MovingObjectPosition hit = this.minecraft.objectMouseOver;
        ItemBlock heldBlock = this.minecraft.thePlayer.getHeldItem() != null
                && this.minecraft.thePlayer.getHeldItem().getItem() instanceof ItemBlock
                ? (ItemBlock)this.minecraft.thePlayer.getHeldItem().getItem() : null;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos() != null && hit.sideHit != null && heldBlock != null) {
            AnchorQuarantineRegistry.shared().expectPrePatchPlacement(
                    hit.getBlockPos().offset(hit.sideHit).toLong(), hit.sideHit.getIndex(),
                    Block.getIdFromBlock(heldBlock.getBlock()), this.minecraft.thePlayer.ticksExisted);
        }
        int useKey = this.minecraft.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(useKey, true);
        KeyBinding.onTick(useKey);
        ClickerUtils.sendFakeRight();
    }

    private void deactivateClicker() {
        int action = NativeBridge.updatePrePatchClicker(
                CLICKER_OPERATION_DEACTIVATE, 0L, 0.0F);
        if ((action & CLICKER_ACTION_RELEASE) != 0) {
            releaseUseKey();
        }
        RightClickCoordinator.release(this);
    }

    private void resetClickerState() {
        NativeBridge.updatePrePatchClicker(
                CLICKER_OPERATION_RESET_CONTROL, 0L, 0.0F);
    }

    private void releaseUseKey() {
        KeyBinding.setKeyBindState(
                this.minecraft.gameSettings.keyBindUseItem.getKeyCode(), false);
    }

    @Override
    public void onRightClickControlLost() {
        int action = NativeBridge.updatePrePatchClicker(
                CLICKER_OPERATION_DEACTIVATE, 0L, 0.0F);
        if ((action & CLICKER_ACTION_RELEASE) != 0) {
            releaseUseKey();
        }
    }

    @XenonEvent
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!this.enabled || !isSkyPatcherEnabled() || !NativeBridge.isNativeAvailable()
                || event.phase != TickEvent.Phase.START
                || this.minecraft.thePlayer == null || this.minecraft.theWorld == null) {
            if (this.enabled && !isSkyPatcherEnabled()) {
                clearAnchors();
                deactivateClicker();
                resetInputHandoff();
            }
            return;
        }
        if (this.scanWorld != this.minecraft.theWorld) {
            this.scanWorld = this.minecraft.theWorld;
            // Never carry anchors, pending checks, or an incomplete scan into
            // a fresh WorldClient instance.
            clearAnchors();
        }
        AnchorQuarantineRegistry.shared().observe(this.minecraft.theWorld,
                this.minecraft.thePlayer.ticksExisted);
        boolean eastWest = setting(1).getModeIndex() == 0;
        int centerX = (int)Math.floor(this.minecraft.thePlayer.posX);
        int centerY = (int)Math.floor(this.minecraft.thePlayer.posY);
        int centerZ = (int)Math.floor(this.minecraft.thePlayer.posZ);
        long now = System.currentTimeMillis();
        if (NativeBridge.watchdogPrePatch(now)) {
            deactivateClicker();
            return;
        }
        collectCompletedPlacementChecks(now);
        long scanBatch = NativeBridge.beginPrePatchTickAndScan(now,
                this.minecraft.thePlayer.posX,
                this.minecraft.thePlayer.posY,
                this.minecraft.thePlayer.posZ,
                centerX, centerY, centerZ, eastWest);
        applyPendingLocalRefreshes(eastWest);
        scanNearbyAnchorsIncremental(
                scanBatch, eastWest, centerX, centerY, centerZ);
        finishTick(centerX, centerY, centerZ);
    }

    /** Refreshes only the placed block and its neighbors on the next client tick. */
    @SubscribeEvent
    public void onBlockPlaced(BlockEvent.PlaceEvent event) {
        if (!this.enabled || !isSkyPatcherEnabled()
                || event.world == null || !event.world.isRemote
                || event.placedBlock != Blocks.obsidian || event.pos == null) {
            return;
        }
        NativeBridge.recordPrePatchBlockPlaced(
                event.pos.toLong(), System.currentTimeMillis());
    }

    /** Client-side fallback: the normal placement event may only arrive server-side. */
    @SubscribeEvent
    public void onRightClickForObsidian(PlayerInteractEvent event) {
        if (!this.enabled || !isSkyPatcherEnabled()
                || event.world == null || !event.world.isRemote
                || event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK
                || event.entityPlayer != this.minecraft.thePlayer || event.face == null) {
            return;
        }
        ItemBlock heldBlock = event.entityPlayer.getHeldItem() == null
                || !(event.entityPlayer.getHeldItem().getItem() instanceof ItemBlock)
                ? null : (ItemBlock)event.entityPlayer.getHeldItem().getItem();
        if (heldBlock == null || heldBlock.getBlock() != Blocks.obsidian
                || this.minecraft.objectMouseOver == null
                || this.minecraft.objectMouseOver.getBlockPos() == null) {
            return;
        }
        BlockPos hit = this.minecraft.objectMouseOver.getBlockPos();
        NativeBridge.queuePrePatchPlacementCheck(
                hit.toLong(), event.face.ordinal());
    }

    private void collectCompletedPlacementChecks(long now) {
        long packed = NativeBridge.pollPrePatchWork(
                WORK_BEGIN_PLACEMENT_CHECKS, now);
        while (packed != Long.MIN_VALUE) {
            this.scanCursor.set(unpackX(packed), unpackY(packed), unpackZ(packed));
            boolean placed = this.minecraft.theWorld.isBlockLoaded(this.scanCursor)
                    && this.minecraft.theWorld.getBlockState(this.scanCursor).getBlock()
                    == Blocks.obsidian;
            packed = NativeBridge.pollPrePatchWork(
                    WORK_PLACEMENT_RESULT | (placed ? 1 : 0), now);
        }
    }

    private void applyPendingLocalRefreshes(boolean eastWest) {
        if (this.minecraft.thePlayer == null || this.minecraft.theWorld == null) {
            return;
        }
        long packed = NativeBridge.pollPrePatchWork(
                WORK_BEGIN_LOCAL_REFRESHES, 0L);
        if (packed == Long.MIN_VALUE) {
            return;
        }
        do {
            packed = refreshNativeAnchorPosition(packed, eastWest);
        } while (packed != Long.MIN_VALUE);
    }

    private long refreshNativeAnchorPosition(long packed, boolean eastWest) {
        int x = unpackX(packed);
        int y = unpackY(packed);
        int z = unpackZ(packed);
        this.scanCursor.set(x, y, z);
        long position = this.scanCursor.toLong();
        boolean valid = this.minecraft.theWorld.isBlockLoaded(this.scanCursor)
                && this.minecraft.theWorld.getBlockState(this.scanCursor).getBlock()
                == Blocks.obsidian;
        boolean firstExposed = valid && !isQuarantined(position, eastWest ? EnumFacing.EAST : EnumFacing.NORTH)
                && isFaceExposed(
                x, y, z, eastWest ? 1 : 0, eastWest ? 0 : -1);
        boolean secondExposed = valid && !isQuarantined(position, eastWest ? EnumFacing.WEST : EnumFacing.SOUTH)
                && isFaceExposed(
                x, y, z, eastWest ? -1 : 0, eastWest ? 0 : 1);
        int result = WORK_LOCAL_REFRESH_RESULT
                | (valid ? 1 : 0)
                | (firstExposed ? 2 : 0)
                | (secondExposed ? 4 : 0);
        return NativeBridge.pollPrePatchWork(result, 0L);
    }

    private void scanNearbyAnchorsIncremental(long batch, boolean eastWest, int centerX,
                                              int centerY, int centerZ) {
        if (batch == Long.MIN_VALUE) {
            return;
        }
        int firstOffset = (int)(batch >> 32);
        int offsetCount = (int)batch;
        int endOffset = Math.min(firstOffset + offsetCount, SCAN_OFFSETS_BY_DISTANCE.length);
        for (int index = firstOffset; index < endOffset; index++) {
            scanOffset(SCAN_OFFSETS_BY_DISTANCE[index], eastWest, centerX, centerY, centerZ);
        }
    }

    private void scanOffset(long offset, boolean eastWest, int centerX, int centerY,
                            int centerZ) {
        int x = centerX + unpackScanOffsetX(offset);
        int y = centerY + unpackScanOffsetY(offset);
        int z = centerZ + unpackScanOffsetZ(offset);
        if (y < 0 || y > 255) {
            return;
        }
        this.scanCursor.set(x, y, z);
        if (!this.minecraft.theWorld.isBlockLoaded(this.scanCursor)
                || this.minecraft.theWorld.getBlockState(this.scanCursor).getBlock()
                != Blocks.obsidian) {
            return;
        }
        long packed = this.scanCursor.toLong();
        NativeBridge.submitPrePatchScanCandidate(packed,
                !isQuarantined(packed, eastWest ? EnumFacing.EAST : EnumFacing.NORTH)
                        && isFaceExposed(x, y, z, eastWest ? 1 : 0, eastWest ? 0 : -1),
                !isQuarantined(packed, eastWest ? EnumFacing.WEST : EnumFacing.SOUTH)
                        && isFaceExposed(x, y, z, eastWest ? -1 : 0, eastWest ? 0 : 1));
    }

    private static long[] buildScanOffsetsByDistance() {
        long[] offsets = new long[SCAN_VOLUME_POSITION_COUNT];
        int index = 0;
        for (int x = SCAN_MIN_OFFSET; x <= SCAN_MAX_OFFSET; x++) {
            for (int y = SCAN_MIN_OFFSET; y <= SCAN_MAX_OFFSET; y++) {
                for (int z = SCAN_MIN_OFFSET; z <= SCAN_MAX_OFFSET; z++) {
                    int distanceSquared = x * x + y * y + z * z;
                    offsets[index++] = ((long)distanceSquared << 18)
                            | ((long)(x + SCAN_HALF_EXTENT) << 12)
                            | ((long)(y + SCAN_HALF_EXTENT) << 6)
                            | (long)(z + SCAN_HALF_EXTENT);
                }
            }
        }
        if (index != offsets.length) {
            throw new IllegalStateException("Invalid PrePatch volume offset count");
        }
        Arrays.sort(offsets);
        return offsets;
    }

    private static int unpackScanOffsetX(long offset) {
        return (int)(offset >> 12 & 0x3FL) - SCAN_HALF_EXTENT;
    }

    private static int unpackScanOffsetY(long offset) {
        return (int)(offset >> 6 & 0x3FL) - SCAN_HALF_EXTENT;
    }

    private static int unpackScanOffsetZ(long offset) {
        return (int)(offset & 0x3FL) - SCAN_HALF_EXTENT;
    }

    private boolean isFaceExposed(int x, int y, int z, int offsetX, int offsetZ) {
        this.scanCursor.set(x + offsetX, y, z + offsetZ);
        if (!this.minecraft.theWorld.isBlockLoaded(this.scanCursor)) {
            return false;
        }
        Block block = this.minecraft.theWorld.getBlockState(this.scanCursor).getBlock();
        Material material = block.getMaterial();
        return material == Material.air || material == Material.water
                || material.isReplaceable();
    }

    public boolean isVisibleAnchor(long position, int face) {
        return this.minecraft.theWorld != null && this.minecraft.thePlayer != null
                && !AnchorQuarantineRegistry.shared().isQuarantined(this.minecraft.theWorld,
                position, face, this.minecraft.thePlayer.ticksExisted)
                && NativeBridge.hasPrePatchVisibleAnchor(position, face);
    }

    private boolean isQuarantined(long position, EnumFacing face) {
        return AnchorQuarantineRegistry.shared().isQuarantined(this.minecraft.theWorld,
                position, face.getIndex(), this.minecraft.thePlayer.ticksExisted);
    }

    public boolean isManualInputSatisfied() {
        return !setting(3).getValBoolean() || Mouse.isButtonDown(1);
    }

    private String flipDirection() {
        setting(1).setValString(setting(1).getModeIndex() == 0
                ? "North/South" : "East/West");
        clearAnchors();
        return setting(1).getValString();
    }

    private void applyPatchPresetOne() {
        setting(1).setValString("East/West");
        setting(3).setValBoolean(true);
        clearAnchors();
        deactivateClicker();
    }

    private void finishTick(int centerX, int centerY, int centerZ) {
        long position = Long.MIN_VALUE;
        int face = 0;
        if (PerfectPatch.instance == null
                || !isManualInputSatisfied()
                || !this.minecraft.inGameHasFocus
                || this.minecraft.currentScreen instanceof ClickGuiObj
                || !heldItemAllowed()) {
            NativeBridge.finishPrePatchTick(centerX, centerY, centerZ,
                    position, face, 0, 0);
            return;
        }
        MovingObjectPosition hit = this.minecraft.objectMouseOver;
        if (prePatchAnchorUnderCrosshair() && hit != null) {
            position = hit.getBlockPos().toLong();
            face = hit.sideHit.ordinal();
        }
        NativeBridge.finishPrePatchTick(centerX, centerY, centerZ,
                position, face,
                Math.round(setting(2).getRangeMinimum()),
                Math.round(setting(2).getRangeMaximum()));
    }

    private void clearAnchors() {
        NativeBridge.resetPrePatchScan();
    }

    /** PrePatch is an input producer owned by SkyPatcher's parent toggle. */
    private boolean isSkyPatcherEnabled() {
        return PerfectPatch.instance != null
                && PerfectPatch.instance.isModuleEnabled(SKY_PATCHER_MODULE_INDEX);
    }

    @XenonEvent
    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!this.enabled || !isSkyPatcherEnabled() || !setting(SETTING_RENDER).getValBoolean()
                || this.minecraft.thePlayer == null || this.minecraft.theWorld == null) {
            return;
        }
        MovingObjectPosition hit = this.minecraft.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null) {
            return;
        }
        double viewX = this.minecraft.getRenderManager().viewerPosX;
        double viewY = this.minecraft.getRenderManager().viewerPosY;
        double viewZ = this.minecraft.getRenderManager().viewerPosZ;
        BlockPos position = hit.getBlockPos();
        int activeFace = NativeBridge.prePatchActiveIntentFace();
        // The face comes from PrePatch's native arbitration, not the generic
        // raycast.  Do not render a broad target hint while no exposed face is
        // actually actionable.
        if (!isHorizontalFace(activeFace)
                || !NativeBridge.matchesPrePatchIntent(position.toLong(), activeFace)) {
            return;
        }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.pushMatrix();
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            // The visual guide is deliberately one face only: it is the exact
            // exposed support face selected by PrePatch, never a full cube nor
            // a preview of the scan volume.
            GL11.glColor4f(0.10F, 0.72F, 1.0F, 0.30F);
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer renderer = tessellator.getWorldRenderer();
            renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
            addFilledFace(renderer, position.getX(), position.getY(), position.getZ(),
                    activeFace, viewX, viewY, viewZ);
            tessellator.draw();

            // Keep a crisp border around that same effective face.
            GL11.glColor4f(0.10F, 0.55F, 1.0F, 1.0F);
            renderer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION);
            addAdjacentFace(renderer, position.getX(), position.getY(), position.getZ(),
                    activeFace, viewX, viewY, viewZ);
            tessellator.draw();

        } finally {
            GlStateManager.popMatrix();
            GL11.glPopAttrib();
        }
    }

    public String getDebugState() {
        return "PrePatch enabled=" + this.enabled
                + " direction=" + setting(1).getValString()
                + " volume=12x12x12"
                + " selected=" + (int)NativeBridge.prePatchRenderCounts()
                + " render=" + setting(SETTING_RENDER).getValBoolean()
                + " manual=" + setting(3).getValBoolean()
                + " manualPressed=" + Mouse.isButtonDown(1)
                + " spreadRange=" + Math.round(setting(2).getRangeMinimum())
                + "-" + Math.round(setting(2).getRangeMaximum())
                + " syntheticSpread=" + NativeBridge.prePatchSyntheticSpread();
    }

    /** Temporary field debugger for PrePatch's independently owned state. */
    private String getAnchorDebugState() {
        MovingObjectPosition hit = this.minecraft.objectMouseOver;
        boolean blockHit = hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos() != null && hit.sideHit != null;
        boolean anchor = blockHit && prePatchAnchorUnderCrosshair();
        long counts = NativeBridge.prePatchRenderCounts();
        int faces = (int)counts;
        int blocks = (int)(counts >>> 32);
        long timing = NativeBridge.prePatchDiagnosticTiming(System.currentTimeMillis());
        long scanAge = timing & 0xFFFFFFFFL;
        int actionIdleTicks = (int)(timing >>> 32 & 0xFFFFL);
        int placements = (int)(timing >>> 48 & 0xFFFFL);
        long scan = NativeBridge.prePatchDiagnosticCounts();
        int cursor = (int)(scan & 0xFFFFL);
        int maximum = (int)(scan >>> 16 & 0xFFFFL);
        int accumulating = (int)(scan >>> 32 & 0xFFFFL);
        return "hit=" + (blockHit ? hit.getBlockPos() + "/" + hit.sideHit : "none")
                + " anchor=" + anchor
                + " syntheticSpread=" + NativeBridge.prePatchSyntheticSpread()
                + " visible=" + blocks + "/" + faces
                + " accumulating=" + accumulating
                + " scan=" + cursor + "/" + maximum
                + " scanAgeMs=" + scanAge
                + " actionIdleTicks=" + actionIdleTicks
                + " placements=" + placements;
    }

    private boolean isVisibleForRender(long packed, ClippingHelper clippingHelper,
                                       double viewX, double viewY, double viewZ) {
        if (packed == Long.MIN_VALUE) {
            return false;
        }
        int x = unpackX(packed);
        int y = unpackY(packed);
        int z = unpackZ(packed);
        if (this.minecraft.thePlayer.getDistanceSq(
                x + 0.5D, y + 0.5D, z + 0.5D) > RENDER_DISTANCE_SQ) {
            return false;
        }
        return clippingHelper.isBoxInFrustum(
                x - viewX, y - viewY, z - viewZ,
                x + 1.0D - viewX, y + 1.0D - viewY, z + 1.0D - viewZ);
    }

    private static int unpackX(long packed) { return (int)(packed >> 38); }
    private static int unpackY(long packed) { return (int)(packed << 26 >> 52); }
    private static int unpackZ(long packed) { return (int)(packed << 38 >> 38); }
    private static long unpackRenderPosition(long packed) {
        return packed & ~(0xFL << 34);
    }
    private static int unpackRenderFace(long packed) { return (int)(packed >> 34 & 0xFL) - 1; }

    private static void addBlockOutline(WorldRenderer renderer, int positionX,
                                        int positionY, int positionZ,
                                        double viewX, double viewY, double viewZ) {
        double epsilon = 0.002D;
        double x1 = positionX - viewX - epsilon;
        double y1 = positionY - viewY - epsilon;
        double z1 = positionZ - viewZ - epsilon;
        double x2 = positionX + 1.0D - viewX + epsilon;
        double y2 = positionY + 1.0D - viewY + epsilon;
        double z2 = positionZ + 1.0D - viewZ + epsilon;

        addLine(renderer, x1, y1, z1, x2, y1, z1);
        addLine(renderer, x2, y1, z1, x2, y1, z2);
        addLine(renderer, x2, y1, z2, x1, y1, z2);
        addLine(renderer, x1, y1, z2, x1, y1, z1);

        addLine(renderer, x1, y2, z1, x2, y2, z1);
        addLine(renderer, x2, y2, z1, x2, y2, z2);
        addLine(renderer, x2, y2, z2, x1, y2, z2);
        addLine(renderer, x1, y2, z2, x1, y2, z1);

        addLine(renderer, x1, y1, z1, x1, y2, z1);
        addLine(renderer, x2, y1, z1, x2, y2, z1);
        addLine(renderer, x2, y1, z2, x2, y2, z2);
        addLine(renderer, x1, y1, z2, x1, y2, z2);
    }

    private static void addLine(WorldRenderer renderer, double x1, double y1, double z1,
                                double x2, double y2, double z2) {
        renderer.pos(x1, y1, z1).endVertex();
        renderer.pos(x2, y2, z2).endVertex();
    }

    private static boolean isHorizontalFace(int face) {
        return face == EnumFacing.NORTH.getIndex() || face == EnumFacing.SOUTH.getIndex()
                || face == EnumFacing.WEST.getIndex() || face == EnumFacing.EAST.getIndex();
    }

    /** Emits one translucent support face in render-relative coordinates. */
    private static void addFilledFace(WorldRenderer renderer, int positionX, int positionY, int positionZ,
                                      int face, double viewX, double viewY, double viewZ) {
        double epsilon = 0.001D;
        double x1 = positionX - viewX;
        double y1 = positionY - viewY;
        double z1 = positionZ - viewZ;
        double x2 = x1 + 1.0D;
        double y2 = y1 + 1.0D;
        double z2 = z1 + 1.0D;
        switch (face) {
            case 2: quad(renderer, x1, y1, z1 - epsilon, x2, y1, z1 - epsilon,
                    x2, y2, z1 - epsilon, x1, y2, z1 - epsilon); break;
            case 3: quad(renderer, x2, y1, z2 + epsilon, x1, y1, z2 + epsilon,
                    x1, y2, z2 + epsilon, x2, y2, z2 + epsilon); break;
            case 4: quad(renderer, x1 - epsilon, y1, z2, x1 - epsilon, y1, z1,
                    x1 - epsilon, y2, z1, x1 - epsilon, y2, z2); break;
            case 5: quad(renderer, x2 + epsilon, y1, z1, x2 + epsilon, y1, z2,
                    x2 + epsilon, y2, z2, x2 + epsilon, y2, z1); break;
            default: break;
        }
    }

    private static void quad(WorldRenderer renderer,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4) {
        renderer.pos(x1, y1, z1).endVertex();
        renderer.pos(x2, y2, z2).endVertex();
        renderer.pos(x3, y3, z3).endVertex();
        renderer.pos(x4, y4, z4).endVertex();
    }

    private Settings setting(int index) {
        return this.sl.get(index);
    }

    private static void addAdjacentFace(WorldRenderer renderer, int positionX,
                                        int positionY, int positionZ, int face,
                                        double viewX, double viewY, double viewZ) {
        double epsilon = 0.004D;
        double x1 = positionX - viewX;
        double y1 = positionY - viewY;
        double z1 = positionZ - viewZ;
        double x2 = x1 + 1.0D;
        double y2 = y1 + 1.0D;
        double z2 = z1 + 1.0D;
        switch (face) {
            case 0:
                addLine(renderer, x1, y1 - epsilon, z1, x2, y1 - epsilon, z1);
                addLine(renderer, x2, y1 - epsilon, z1, x2, y1 - epsilon, z2);
                addLine(renderer, x2, y1 - epsilon, z2, x1, y1 - epsilon, z2);
                addLine(renderer, x1, y1 - epsilon, z2, x1, y1 - epsilon, z1);
                break;
            case 1:
                addLine(renderer, x1, y2 + epsilon, z1, x2, y2 + epsilon, z1);
                addLine(renderer, x2, y2 + epsilon, z1, x2, y2 + epsilon, z2);
                addLine(renderer, x2, y2 + epsilon, z2, x1, y2 + epsilon, z2);
                addLine(renderer, x1, y2 + epsilon, z2, x1, y2 + epsilon, z1);
                break;
            case 2:
                addLine(renderer, x1, y1, z1 - epsilon, x2, y1, z1 - epsilon);
                addLine(renderer, x2, y1, z1 - epsilon, x2, y2, z1 - epsilon);
                addLine(renderer, x2, y2, z1 - epsilon, x1, y2, z1 - epsilon);
                addLine(renderer, x1, y2, z1 - epsilon, x1, y1, z1 - epsilon);
                break;
            case 3:
                addLine(renderer, x1, y1, z2 + epsilon, x2, y1, z2 + epsilon);
                addLine(renderer, x2, y1, z2 + epsilon, x2, y2, z2 + epsilon);
                addLine(renderer, x2, y2, z2 + epsilon, x1, y2, z2 + epsilon);
                addLine(renderer, x1, y2, z2 + epsilon, x1, y1, z2 + epsilon);
                break;
            case 4:
                addLine(renderer, x1 - epsilon, y1, z1, x1 - epsilon, y1, z2);
                addLine(renderer, x1 - epsilon, y1, z2, x1 - epsilon, y2, z2);
                addLine(renderer, x1 - epsilon, y2, z2, x1 - epsilon, y2, z1);
                addLine(renderer, x1 - epsilon, y2, z1, x1 - epsilon, y1, z1);
                break;
            case 5:
                addLine(renderer, x2 + epsilon, y1, z1, x2 + epsilon, y1, z2);
                addLine(renderer, x2 + epsilon, y1, z2, x2 + epsilon, y2, z2);
                addLine(renderer, x2 + epsilon, y2, z2, x2 + epsilon, y2, z1);
                addLine(renderer, x2 + epsilon, y2, z1, x2 + epsilon, y1, z1);
                break;
            default:
                break;
        }
    }
}

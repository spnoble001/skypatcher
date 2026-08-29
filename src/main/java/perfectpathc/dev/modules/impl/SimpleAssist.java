package perfectpathc.dev.modules.impl;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.opengl.GL11;
import perfectpathc.dev.PerfectPatch;
import perfectpathc.dev.modules.impl.events.XenonEvent;
import perfectpathc.dev.modules.impl.simpleassist.SpatialAssistanceEngine;
import perfectpathc.dev.modules.runtime.IModuleEntry;
import perfectpathc.dev.modules.impl.ui.ClickGuiObj;
import perfectpathc.dev.modules.impl.utils.GlobalCps;
import perfectpathc.dev.modules.impl.utils.GlobalReducedKb;
import perfectpathc.dev.modules.impl.utils.GlobalSpatialAssistance;
import perfectpathc.dev.modules.impl.utils.GlobalNaturalMode;
import perfectpathc.dev.modules.impl.utils.AnchorQuarantineRegistry;
import perfectpathc.dev.modules.impl.utils.GlobalRenderHelp;
import perfectpathc.dev.modules.impl.utils.RightClickCoordinator;
import perfectpathc.dev.modules.impl.simpleassist.StateDrivenFriction;

@Mod(keybind = 0)
public class SimpleAssist extends Module implements RightClickCoordinator.Owner, IModuleEntry {
    private static final int INPUT_PRIORITY = 31;
    private static final int SETTING_SPEED = 0;
    private static final int SETTING_REDUCED_KB = 1;
    private static final int SETTING_EXECUTION_MODE = 2;
    private static final int SETTING_NATURAL = 3;
    private static final int SETTING_SPATIAL_ASSISTANCE = 4;
    private static final int SETTING_RENDER_HELP = 5;
    private static final Field RIGHT_CLICK_DELAY_TIMER = ReflectionHelper.findField(
            Minecraft.class, "rightClickDelayTimer", "field_71467_ac");
    private static final Field PRESS_TIME = ReflectionHelper.findField(
            KeyBinding.class, "pressTime", "field_151474_i");
    private final SpatialAssistanceEngine spatialAssistance = new SpatialAssistanceEngine();
    private final StateDrivenFriction stateDrivenFriction = StateDrivenFriction.shared();
    private boolean stateFrictionRegistered;
    private int appliedExecutionMode = -1;
    /** True only for the native capture admitted from PrePatch's live intent. */
    private boolean prePatchExecution;

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        switch (commandId) {
            case 11: onClientTick((TickEvent.ClientTickEvent) args[0]); return null;
            case 15: onRenderWorld((RenderWorldLastEvent) args[0]); return null;
            case 19:
                setting(SETTING_SPEED).setValfloat(15.0F);
                GlobalCps.set(setting(SETTING_SPEED).getValfloat());
                return null;
            default: return super.invokeCommand(commandId, args);
        }
    }
    public SimpleAssist() {
        NativeBridge.configureSimpleAssistAcceptedBlocks(
                SimpleAssistMinecraftAccess.getObsidianBlockId(),
                SimpleAssistMinecraftAccess.getBedrockBlockId());
        this.sl.add(new Settings("CPS global".toCharArray(), this,
                GlobalCps.get(), GlobalCps.MINIMUM,
                Math.min(GlobalCps.MAXIMUM, NativeBridge.simpleAssistMaxCps()), 1.0F));
        this.sl.add(new Settings("KB Reducido".toCharArray(), this, GlobalReducedKb.isEnabled()));
        this.sl.add(new Settings("Modo".toCharArray(), this, "1D", "1D", "3D"));
        this.sl.add(new Settings("Natural".toCharArray(), this, GlobalNaturalMode.isEnabled()));
        // Rendered by ClickGuiObj as an independent ON/OFF switch for this
        // input-pipeline submodule.
        this.sl.add(new Settings("Asistencia espacial".toCharArray(), this, true));
        this.sl.add(new Settings("Renderizar ayuda".toCharArray(), this, GlobalRenderHelp.isEnabled()));
        this.sl.add(new Settings("Keybind".toCharArray(), this));
        registerSettings();
        this.name = "SkyPatcher V1";
    }

    @Override
    public void initialize() {
        // Configuration is established by the constructor; activation remains
        // owned by PerfectPatch's module lifecycle.
    }

    @Override
    public void dsg() {
        super.dsg();
    }

    @Override
    public void onEnable() {
        NativeBridge.isSimpleAssistLicensed();
        resetAssistState();
        applyExecutionMode();
        syncStateFrictionRegistration();
    }

    @Override
    public void onDisable() {
        unregisterStateFriction();
        setThreeDimensionalEnabled(false);
        this.appliedExecutionMode = -1;
        resetAssistState();
    }

    @XenonEvent
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!this.enabled || !NativeBridge.isNativeAvailable()) {
            return;
        }
        // 3D owns only real PatchCrumbs routes.  PrePatch keeps its proven
        // linear executor even while the parent mode is set to 3D.
        if (isThreeDimensionalMode()
                && (!hasPrePatchFlow() || hasPriority3DRoute())) {
            unregisterStateFriction();
            if (event.phase == TickEvent.Phase.START && hasPriority3DRoute()) {
                releaseInput();
                clearCapturedPatch();
            }
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            try {
                if (this.minecraft.thePlayer != null && this.minecraft.theWorld != null) {
                    AnchorQuarantineRegistry.shared().observe(this.minecraft.theWorld,
                            this.minecraft.thePlayer.ticksExisted);
                }
                syncStateFrictionRegistration();
                if (this.stateFrictionRegistered) this.stateDrivenFriction.onTickStart(this.minecraft);
                this.spatialAssistance.onPreMotionUpdate();
                refreshActivePatchesSnapshot();
                updateEarlyInputReservation();
            } catch (RuntimeException exception) {
                abortFlowAfterException();
            }
        } else {
            try {
                if (this.stateFrictionRegistered) this.stateDrivenFriction.onTickEnd(this.minecraft);
                runPatchBuilder();
            } catch (RuntimeException exception) {
                abortFlowAfterException();
            }
        }
    }

    /** Reserves input before lower-priority clickers can synthesize a click. */
    private void updateEarlyInputReservation() {
        int nativePhase = this.minecraft.thePlayer == null ? 3
                : NativeBridge.simpleAssistPhase(this.minecraft.thePlayer.ticksExisted);
        boolean activeFlow = NativeBridge.isSimpleAssistCaptured()
                // Native phase 3 means inactive. Only a pending placement (1)
                // or its backoff (2) may reserve input without a capture.
                || nativePhase == 1 || nativePhase == 2;
        boolean clickGuiOpen = this.minecraft.currentScreen instanceof ClickGuiObj;
        boolean prePatchIntent = !clickGuiOpen && hasPrePatchIntentUnderCrosshair();
        boolean validTrigger;
        if (isThreeDimensionalMode()) {
            validTrigger = isPatchBlockHeld() && !hasPriority3DRoute()
                    && (this.prePatchExecution || prePatchIntent);
        } else {
            validTrigger = isPatchBlockHeld()
                    && ((hasValidRuntime() && (activeFlow || hasPatchTerminalUnderCrosshair()))
                    || prePatchIntent);
        }
        if (validTrigger) {
            if (RightClickCoordinator.acquire(this, INPUT_PRIORITY)) {
                suppressPhysicalRightClick();
            }
        } else if (RightClickCoordinator.isOwner(this)) {
            RightClickCoordinator.release(this);
        }
    }

    private boolean hasPatchTerminalUnderCrosshair() {
        MovingObjectPosition hit = interactionHit();
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null) {
            return false;
        }
        return NativeBridge.matchesSimpleAssistPatchHit(pack(hit.getBlockPos()));
    }

    private boolean hasPrePatchIntentUnderCrosshair() {
        MovingObjectPosition hit = this.minecraft.objectMouseOver;
        return hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos() != null && hit.sideHit != null
                && !AnchorQuarantineRegistry.shared().isQuarantined(this.minecraft.theWorld,
                        pack(hit.getBlockPos()), hit.sideHit.getIndex(),
                        this.minecraft.thePlayer.ticksExisted)
                && NativeBridge.matchesPrePatchIntent(
                        pack(hit.getBlockPos()), hit.sideHit.ordinal());
    }

    /** The input adapter is controlled solely by the spatial submodule switch. */
    private MovingObjectPosition interactionHit() {
        if (!GlobalSpatialAssistance.isEnabled()) {
            return this.minecraft.objectMouseOver;
        }
        return SpatialAssistanceEngine.getActivationHit(this.minecraft,
                SpatialAssistanceEngine.VIRTUAL_CONE_RADIUS,
                new SpatialAssistanceEngine.TargetFilter() {
                    @Override
                    public boolean matches(BlockPos anchorNode, net.minecraft.util.EnumFacing side) {
                        // A generic nearby block must not suppress admission;
                        // only a live PatchCrumbs terminal is a valid fake hit.
                        return NativeBridge.matchesSimpleAssistPatchHit(pack(anchorNode));
                    }
                });
    }

    private void refreshActivePatchesSnapshot() {
        // PatchCrumbs publishes the route frame during its own START tick.
        // 1D only reconciles its optional native capture; it is not the
        // publisher, because 3D must operate when 1D is deliberately gated.
        NativeBridge.reconcileSimpleAssistPatchFrame(
                this.minecraft.thePlayer == null ? 0 : this.minecraft.thePlayer.ticksExisted);
    }

    private void runPatchBuilder() {
        // PatchCrumbs may refresh its directional guide between tick phases.
        refreshActivePatchesSnapshot();
        // A request-generation dead end has no pending placement, therefore it
        // cannot be recovered by the confirmation timeout alone.
        int currentTick = this.minecraft.thePlayer == null ? 0 : this.minecraft.thePlayer.ticksExisted;
        if (isThreeDimensionalMode() && (!hasPrePatchFlow() || hasPriority3DRoute())) {
            releaseInput();
            clearCapturedPatch();
            return;
        }
        if (this.minecraft.thePlayer != null && this.minecraft.thePlayer.isUsingItem()) {
            NativeBridge.suspendSimpleAssistWatchdog(currentTick);
        } else {
            NativeBridge.watchdogSimpleAssist(currentTick);
        }
        if (!hasValidRuntime() || !isPatchBlockHeld()) {
            releaseInput();
            clearCapturedPatch();
            return;
        }
        // Always run the single admission point once per END tick. Real crumbs
        // and PrePatch remain independent producers; an active execution is
        // never preempted.
        arbitratePatchUnderCrosshair();
        int nativePhase = NativeBridge.simpleAssistPhase(this.minecraft.thePlayer.ticksExisted);
        if (nativePhase == 2) {
            suppressPhysicalRightClick();
            return;
        }
        if (nativePhase == 1) {
            if (!acquireInput()) {
                return;
            }
            if (NativeBridge.isSimpleAssistCaptured() && !isCapturedPatchPositionValid()) {
                releaseInput();
                clearCapturedPatch();
                return;
            }
            suppressPhysicalRightClick();
            if (updatePlacementState()) {
                return;
            }
        }

        boolean hasRequest = selectRequest();
        NativeBridge.setSimpleAssistPreview(hasRequest
                ? NativeBridge.pollSimpleAssistRequestTarget() : Long.MIN_VALUE);
        if (!hasRequest) {
            releaseInput();
            return;
        }
        if (!acquireInput()) {
            NativeBridge.setSimpleAssistPreview(Long.MIN_VALUE);
            return;
        }
        suppressPhysicalRightClick();
        if (this.minecraft.thePlayer.isUsingItem() || !placementDelayElapsed()) {
            return;
        }
        executePlacement();
    }

    private boolean selectRequest() {
        for (int handoff = 0; handoff < 2; handoff++) {
            invalidateStaleCapturedPatch();
            if (!isCapturedPatchPositionValid()) {
                clearCapturedPatch();
            }
            arbitratePatchUnderCrosshair();
            if (!NativeBridge.isSimpleAssistCaptured()) {
                return false;
            }
            long target = nextRequiredCellPacked();
            if (target == Long.MIN_VALUE) {
                clearCapturedPatch();
                continue;
            }
            if (capturePlacementRequest(target)) {
                NativeBridge.resetSimpleAssistRequestMisses();
                return true;
            }
            if (NativeBridge.recordSimpleAssistRequestMiss()) {
                clearCapturedPatch();
            }
            return false;
        }
        return false;
    }

    private void invalidateStaleCapturedPatch() {
        // Native reconciliation owns the live-to-virtual transition. A live
        // capture has no grace timer; a stale one gets its virtual window only
        // after its geometry disappears from a published PatchCrumbs frame.
    }

    protected boolean arbitratePatchUnderCrosshair() {
        if (isThreeDimensionalMode() && hasPriority3DRoute()) {
            return false;
        }
        MovingObjectPosition hit = interactionHit();
        long packedHit = hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos() != null ? pack(hit.getBlockPos()) : Long.MIN_VALUE;
        int hitFace = hit != null && hit.sideHit != null ? hit.sideHit.ordinal() : -1;
        int result = NativeBridge.arbitrateSimpleAssistPatch(packedHit, hitFace,
                this.minecraft.thePlayer.posX, this.minecraft.thePlayer.posY,
                this.minecraft.thePlayer.posZ, this.minecraft.thePlayer.ticksExisted);
        if (result == 2) this.prePatchExecution = true;
        else if (result == 1) this.prePatchExecution = false;
        if (result != 0) NativeBridge.resetSimpleAssistRequestMisses();
        return result != 0;
    }

    private long nextRequiredCellPacked() {
        long packed = NativeBridge.pollSimpleAssistCursor();
        if (packed == Long.MIN_VALUE) return Long.MIN_VALUE;
        while (true) {
            int snapshot = SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, packed);
            if (!NativeBridge.isSimpleAssistAcceptedBlock(
                    SimpleAssistMinecraftAccess.blockId(snapshot))) {
                return packed;
            }
            packed = NativeBridge.nextSimpleAssistCursor(true);
            if (packed == Long.MIN_VALUE) return Long.MIN_VALUE;
        }
    }

    private boolean isCapturedPatchPositionValid() {
        return this.minecraft.thePlayer != null && NativeBridge.isSimpleAssistCaptureValid(
                this.minecraft.thePlayer.ticksExisted, this.minecraft.thePlayer.posX,
                this.minecraft.thePlayer.posY, this.minecraft.thePlayer.posZ);
    }

    private boolean capturePlacementRequest(long packedTarget) {
        int targetSnapshot = SimpleAssistMinecraftAccess.getBlockSnapshot(
                this.minecraft.theWorld, packedTarget);
        if (!NativeBridge.isSimpleAssistReplaceableSnapshot(targetSnapshot)) {
            return false;
        }
        NativeBridge.beginSimpleAssistRequest(packedTarget);
        if (GlobalNaturalMode.isEnabled()) {
            captureNaturalPlacementCandidate();
        } else if (GlobalSpatialAssistance.isEnabled()) {
            captureSpatialExecutionCandidate(packedTarget);
        } else {
            captureGuidancePlacementCandidates(packedTarget);
        }
        return NativeBridge.hasSimpleAssistRequest();
    }

    /** Execution geometry: choose a visible support face before native request scoring. */
    private void captureSpatialExecutionCandidate(long packedTarget) {
        SpatialAssistanceEngine.TargetPlacement candidate = SpatialAssistanceEngine.findExecutionPlacement(this.minecraft,
                BlockPos.fromLong(packedTarget), this.minecraft.playerController.getBlockReachDistance());
        if (candidate == null) return;
        BlockPos support = candidate.getAnchorNode();
        Vec3 hitVec = candidate.getHitVector();
        NativeBridge.pushSimpleAssistNaturalCandidate(pack(support), candidate.getSide().getIndex(),
                hitVec.xCoord, hitVec.yCoord, hitVec.zCoord,
                SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, support),
                this.minecraft.thePlayer.posX,
                this.minecraft.thePlayer.posY + this.minecraft.thePlayer.getEyeHeight(),
                this.minecraft.thePlayer.posZ,
                this.minecraft.playerController.getBlockReachDistance());
    }

    /**
     * Non-spatial mode keeps the original native face arbitration, but obtains
     * each candidate's reach/LOS point from the same geometry authority as
     * 3D.  The mode still sends no silent C05 and performs no virtual capture.
     */
    private void captureGuidancePlacementCandidates(long packedTarget) {
        BlockPos target = BlockPos.fromLong(packedTarget);
        double eyeX = this.minecraft.thePlayer.posX;
        double eyeY = this.minecraft.thePlayer.posY + this.minecraft.thePlayer.getEyeHeight();
        double eyeZ = this.minecraft.thePlayer.posZ;
        double reach = this.minecraft.playerController.getBlockReachDistance();
        for (int faceIndex = 0; faceIndex < 6; faceIndex++) {
            net.minecraft.util.EnumFacing face = net.minecraft.util.EnumFacing.getFront(faceIndex);
            SpatialAssistanceEngine.TargetPlacement candidate =
                    SpatialAssistanceEngine.findExecutionPlacement(this.minecraft, target,
                            reach, null, false, face);
            if (candidate == null) continue;
            BlockPos support = candidate.getAnchorNode();
            Vec3 hitVec = candidate.getHitVector();
            NativeBridge.pushSimpleAssistNaturalCandidate(pack(support), faceIndex,
                    hitVec.xCoord, hitVec.yCoord, hitVec.zCoord,
                    SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, support),
                    eyeX, eyeY, eyeZ, reach);
        }
    }

    /** Uses only the block, face and impact point produced by the physical view ray. */
    private void captureNaturalPlacementCandidate() {
        MovingObjectPosition hit = this.minecraft.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null || hit.sideHit == null || hit.hitVec == null) {
            return;
        }
        BlockPos support = hit.getBlockPos();
        int supportSnapshot = SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, support);
        NativeBridge.pushSimpleAssistNaturalCandidate(pack(support), hit.sideHit.getIndex(),
                hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord,
                supportSnapshot,
                this.minecraft.thePlayer.posX,
                this.minecraft.thePlayer.posY + this.minecraft.thePlayer.getEyeHeight(),
                this.minecraft.thePlayer.posZ, this.minecraft.playerController.getBlockReachDistance());
    }

    private boolean hasValidRuntime() {
        if (this.minecraft.thePlayer == null || this.minecraft.theWorld == null
                || this.minecraft.currentScreen instanceof ClickGuiObj
                || PerfectPatch.instance == null) {
            return false;
        }
        boolean realCrumb = PerfectPatch.instance.getModule(0) != null
                && (Boolean.TRUE.equals(PerfectPatch.instance.invokeModuleCommand(0, 8))
                || NativeBridge.simpleAssistPatchCount() > 0);
        boolean prePatch = hasPrePatchIntentUnderCrosshair();
        if (isThreeDimensionalMode()) {
            return !hasPriority3DRoute() && (prePatch || this.prePatchExecution);
        }
        return realCrumb || prePatch || NativeBridge.isSimpleAssistCaptured();
    }

    private boolean isPatchBlockHeld() {
        ItemStack held = this.minecraft.thePlayer.getHeldItem();
        return held != null && held.stackSize > 0 && held.getItem() instanceof ItemBlock
                && NativeBridge.isSimpleAssistAcceptedBlock(
                SimpleAssistMinecraftAccess.getHeldBlockId(held));
    }

    private boolean placementDelayElapsed() {
        float cps = GlobalCps.get();
        int currentTick = this.minecraft.thePlayer.ticksExisted;
        return NativeBridge.hasSimpleAssistCredit(currentTick, cps);
    }

    private void executePlacement() {
        int currentTick = this.minecraft.thePlayer.ticksExisted;
        if (NativeBridge.simpleAssistPhase(currentTick) != 0) {
            return;
        }
        ItemStack held = this.minecraft.thePlayer.getHeldItem();
        if (held == null || held.stackSize <= 0 || !(held.getItem() instanceof ItemBlock)) {
            return;
        }
        long target = NativeBridge.pollSimpleAssistRequestTarget();
        long support = NativeBridge.pollSimpleAssistRequestSupport();
        int faceIndex = NativeBridge.pollSimpleAssistRequestFace();
        if (target == Long.MIN_VALUE || support == Long.MIN_VALUE || faceIndex < 0) {
            return;
        }
        int expectedBlockId = SimpleAssistMinecraftAccess.getHeldBlockId(held);
        BlockPos supportPos = BlockPos.fromLong(support);
        net.minecraft.util.EnumFacing face = net.minecraft.util.EnumFacing.getFront(faceIndex);
        Vec3 hitVec = new Vec3(NativeBridge.pollSimpleAssistRequestHitX(),
                NativeBridge.pollSimpleAssistRequestHitY(), NativeBridge.pollSimpleAssistRequestHitZ());
        // The target can change between request selection and this click.
        if (!NativeBridge.isSimpleAssistReplaceableSnapshot(
                SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, target))) {
            NativeBridge.recordSimpleAssistRejectedInteraction(currentTick);
            return;
        }
        boolean accepted;
        if (GlobalSpatialAssistance.isEnabled()) {
            accepted = this.spatialAssistance.processInteraction(this.minecraft,
                    supportPos, face, hitVec, new SpatialAssistanceEngine.InteractionDispatcher() {
                        @Override
                        public boolean dispatch() {
                            return SimpleAssist.this.minecraft.playerController.onPlayerRightClick(
                                    SimpleAssist.this.minecraft.thePlayer,
                                    SimpleAssist.this.minecraft.theWorld, held, supportPos, face, hitVec);
                        }
                    });
        } else {
            accepted = this.minecraft.playerController.onPlayerRightClick(
                    this.minecraft.thePlayer, this.minecraft.theWorld, held,
                    supportPos, face, hitVec);
        }
        if (!accepted) {
            NativeBridge.recordSimpleAssistRejectedInteraction(currentTick);
            return;
        }
        // Match the original state machine: a rejected interaction must not
        // spend CPS credit, otherwise retries look like a stalled assist.
        if (!NativeBridge.consumeSimpleAssistCredit(currentTick, GlobalCps.get())) {
            return;
        }
        this.minecraft.thePlayer.swingItem();
        NativeBridge.beginSimpleAssistPlacement(target,
                expectedBlockId, currentTick);
        AnchorQuarantineRegistry.shared().expectAssistedPlacement(target,
                face.getIndex(), expectedBlockId, currentTick);
        NativeBridge.setSimpleAssistPreview(Long.MIN_VALUE);
    }

    private boolean updatePlacementState() {
        int currentTick = this.minecraft.thePlayer.ticksExisted;
        if (NativeBridge.simpleAssistPhase(currentTick) != 1) {
            return false;
        }
        long pendingPacked = NativeBridge.pollSimpleAssistPendingTarget();
        int pendingSnapshot = pendingPacked == Long.MIN_VALUE ? -1
                : SimpleAssistMinecraftAccess.getBlockSnapshot(this.minecraft.theWorld, pendingPacked);
        int nativeResult = NativeBridge.confirmSimpleAssistPlacement(
                SimpleAssistMinecraftAccess.blockId(pendingSnapshot), currentTick,
                SimpleAssistMinecraftAccess.getPlayerPing(this.minecraft));
        if (nativeResult == 1) {
            NativeBridge.extendSimpleAssistGrace(currentTick);
            return false;
        }
        if (nativeResult == -1) {
            if (!NativeBridge.isSimpleAssistCaptured()) {
                clearCapturedPatch();
            }
        }
        return true;
    }

    private boolean acquireInput() {
        return RightClickCoordinator.acquire(this, INPUT_PRIORITY);
    }

    private void releaseInput() {
        boolean wasOwner = RightClickCoordinator.isOwner(this);
        RightClickCoordinator.release(this);
        NativeBridge.setSimpleAssistPreview(Long.MIN_VALUE);
        if (wasOwner) {
            setInt(RIGHT_CLICK_DELAY_TIMER, this.minecraft, 4);
        }
    }

    private void clearCapturedPatch() {
        this.prePatchExecution = false;
        NativeBridge.resetSimpleAssist();
        NativeBridge.resetSimpleAssistRequestMisses();
    }

    private static long pack(BlockPos position) {
        return NativeBridge.packBlockPos(position.getX(), position.getY(), position.getZ());
    }

    private void resetAssistState() {
        this.spatialAssistance.reset();
        releaseInput();
        clearCapturedPatch();
    }

    private void suppressPhysicalRightClick() {
        if (this.minecraft.gameSettings != null) {
            KeyBinding useKey = this.minecraft.gameSettings.keyBindUseItem;
            KeyBinding.setKeyBindState(useKey.getKeyCode(), false);
            setInt(PRESS_TIME, useKey, 0);
        }
    }

    @XenonEvent
    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!this.enabled || (isThreeDimensionalMode() && !this.prePatchExecution)
                || !NativeBridge.isNativeAvailable()
                || this.minecraft.thePlayer == null) {
            return;
        }
        long previewPacked = NativeBridge.pollSimpleAssistPreview();
        if (!GlobalRenderHelp.isEnabled()
                || previewPacked == Long.MIN_VALUE) {
            return;
        }
        int previewX = NativeBridge.pollSimpleAssistPreviewX();
        int previewY = NativeBridge.pollSimpleAssistPreviewY();
        int previewZ = NativeBridge.pollSimpleAssistPreviewZ();
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
            drawPreviewBox(previewX - viewX, previewY - viewY, previewZ - viewZ,
                    previewX + 1.0D - viewX, previewY + 1.0D - viewY,
                    previewZ + 1.0D - viewZ);
        } finally {
            GlStateManager.popMatrix();
            GL11.glPopAttrib();
        }
    }

    private Settings setting(int index) {
        return this.sl.get(index);
    }

    @Override
    public void onSettingsChanged() {
        boolean spatialChanged = GlobalSpatialAssistance.isEnabled()
                != setting(SETTING_SPATIAL_ASSISTANCE).getValBoolean();
        GlobalCps.set(setting(SETTING_SPEED).getValfloat());
        GlobalReducedKb.setEnabled(setting(SETTING_REDUCED_KB).getValBoolean());
        GlobalNaturalMode.setEnabled(setting(SETTING_NATURAL).getValBoolean());
        GlobalSpatialAssistance.setEnabled(setting(SETTING_SPATIAL_ASSISTANCE).getValBoolean());
        GlobalRenderHelp.setEnabled(setting(SETTING_RENDER_HELP).getValBoolean());
        applyExecutionMode();
        syncStateFrictionRegistration();
        // A new spatial policy starts a fresh packet quota in both executors;
        // neither mode can inherit a partially spent interaction token.
        if (spatialChanged) {
            this.spatialAssistance.reset();
            if (PerfectPatch.instance != null) {
                PerfectPatch.instance.invokeModuleCommand(10, 21);
            }
        }
    }

    private boolean isThreeDimensionalMode() {
        return setting(SETTING_EXECUTION_MODE).getModeIndex() == 1;
    }


    private boolean hasPrePatchFlow() {
        return this.prePatchExecution || (this.minecraft.thePlayer != null
                && this.minecraft.theWorld != null && hasPrePatchIntentUnderCrosshair());
    }

    /** A real, route-valid 3D PatchCrumbs objective always wins admission. */
    private boolean hasPriority3DRoute() {
        if (!isThreeDimensionalMode() || PerfectPatch.instance == null
                || PerfectPatch.instance.getModule(10) == null) return false;
        return Boolean.TRUE.equals(PerfectPatch.instance.invokeModuleCommand(10, 22));
    }

    /** The parent toggle owns exactly one executor; 1D itself is gated above. */
    private void applyExecutionMode() {
        int desiredMode = isThreeDimensionalMode() ? 1 : 0;
        if (this.appliedExecutionMode != desiredMode) {
            // A request captured by the previous executor must not survive a
            // mode switch in the shared SkyPatcher parent.
            resetAssistState();
            this.appliedExecutionMode = desiredMode;
        }
        setThreeDimensionalEnabled(this.enabled && desiredMode == 1);
    }

    private void setThreeDimensionalEnabled(boolean enabled) {
        if (PerfectPatch.instance == null || PerfectPatch.instance.getModule(10) == null) return;
        PerfectPatch.instance.invokeModuleCommand(10, 3, Boolean.valueOf(enabled));
    }

    private void syncStateFrictionRegistration() {
        boolean shouldRegister = this.enabled && GlobalReducedKb.isEnabled();
        if (shouldRegister && !this.stateFrictionRegistered) {
            this.stateDrivenFriction.activate(StateDrivenFriction.PARTICIPANT_SIMPLE_ASSIST);
            this.stateFrictionRegistered = true;
        } else if (!shouldRegister && this.stateFrictionRegistered) {
            unregisterStateFriction();
        }
    }

    private void unregisterStateFriction() {
        if (!this.stateFrictionRegistered) return;
        this.stateDrivenFriction.deactivate(StateDrivenFriction.PARTICIPANT_SIMPLE_ASSIST,
                this.minecraft);
        this.stateFrictionRegistered = false;
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

    private void abortFlowAfterException() {
        RightClickCoordinator.release(this);
        clearCapturedPatch();
    }

    private static void setInt(Field field, Object owner, int value) {
        try {
            field.setInt(owner, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not update Minecraft click state", exception);
        }
    }

    @Override
    public void onRightClickControlLost() {
        // Losing ownership aborts the entire transaction, including the capture.
        clearCapturedPatch();
    }

    public String getDebugState() {
        return this.name + " activado=" + this.enabled + " state="
                + NativeBridge.simpleAssistPhase(this.minecraft.thePlayer.ticksExisted)
                + " origin=" + NativeBridge.pollSimpleAssistOrigin()
                + " terminal=" + NativeBridge.pollSimpleAssistTerminal()
                + " cursor=" + NativeBridge.pollSimpleAssistCursor()
                + " preview=" + NativeBridge.pollSimpleAssistPreview()
                + " natural=" + setting(SETTING_NATURAL).getValBoolean()
                + " spatial=" + GlobalSpatialAssistance.isEnabled()
                + " pending=" + NativeBridge.pollSimpleAssistPendingTarget() + " owner="
                + RightClickCoordinator.getOwnerName();
    }

    @Override
    public char getSort() {
        return 'g';
    }
}

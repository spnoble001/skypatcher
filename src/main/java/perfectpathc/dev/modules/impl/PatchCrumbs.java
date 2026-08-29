package perfectpathc.dev.modules.impl;

import net.minecraft.block.BlockDispenser;
import net.minecraft.block.BlockGravel;
import net.minecraft.block.BlockSand;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityDispenser;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;
import perfectpathc.dev.modules.impl.events.XenonEvent;
import perfectpathc.dev.modules.impl.utils.GlobalModuleNotifications;
import perfectpathc.dev.modules.impl.utils.TresDPrueba;

@Mod(keybind = 0)
public final class PatchCrumbs extends Module {
    private static final int PATCH_SCAN_RADIUS = 64;
    private static final int PATCH_AXIS_RENDER_RADIUS = 10;
    private static final int PATCH_SCAN_INTERVAL_TICKS = 5;
    private static final long PATCH_PERSISTENCE_MS = 2500L;
    private static final double PATCH_RENDER_DISTANCE_SQ = 96.0D * 96.0D;
    private static final int FLAG_DRAW_NORTH_SOUTH = 1;
    private static final int FLAG_DRAW_EAST_WEST = 1 << 1;
    private static final int FLAG_TRAJECTORY_NORTH_SOUTH = 1 << 2;
    private static final int LABEL_COLOR_ARGB = 0xFFFF0000;
    private static final int SETTING_RENDER_PATCHES = 6;
    private static final int SETTING_RENDER_V2 = 7;
    private static final int SETTING_MODULE_NOTIFICATIONS = 8;
    private final BlockPos.MutableBlockPos scanCursor = new BlockPos.MutableBlockPos();
    private boolean debugRouteVisible;

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        switch (commandId) {
            case 7: pushActivePatchesToNative(); return null;
            case 8: return Boolean.valueOf(hasActiveCrumb());
            case 11: onClientTick((TickEvent.ClientTickEvent) args[0]); return null;
            case 15: onRenderWorld((RenderWorldLastEvent) args[0]); return null;
            case 21: this.debugRouteVisible = !this.debugRouteVisible;
                return Boolean.valueOf(this.debugRouteVisible);
            case 23:
                if (args != null && args.length == 1 && args[0] instanceof Module) {
                    GlobalModuleNotifications.announce((Module)args[0]);
                }
                return null;
            default: return super.invokeCommand(commandId, args);
        }
    }

    public PatchCrumbs() {
        this.sl.add(new Settings("Tracer".toCharArray(), this, false));
        this.sl.add(new Settings("Label".toCharArray(), this, false));
        this.sl.add(new Settings("Dispenser Check".toCharArray(), this, false));
        this.sl.add(new Settings("ABC".toCharArray(), this, false));
        this.sl.add(new Settings("Direction".toCharArray(), this, "Auto",
                "Auto", "Both", "North/South", "East/West"));
        this.sl.add(new Settings("Line Width".toCharArray(), this, 2.0F, 1.0F, 5.0F, 1.0F));
        this.sl.add(new Settings("Render Patches".toCharArray(), this, false));
        this.sl.add(new Settings("Render V2".toCharArray(), this, false));
        this.sl.add(new Settings(text(16, 0x5A335A2E5A355A14L, 0x5A3B5A395A335A3CL,
                0x5A355A375A7A5A28L, 0x5A355A365A2F5A3EL,
                0x0000000000005A29L).toCharArray(), this, true));
        this.sl.add(new Settings("Keybind".toCharArray(), this));
        registerSettings();
        this.name = "Patch Crumbs";
    }

    @Override
    public char getSort() {
        return 'p';
    }

    @Override
    public void onEnable() {
        resetDetectionState();
    }

    @Override
    public void onDisable() {
        this.debugRouteVisible = false;
        resetDetectionState();
        // PatchCrumbs owns the shared route frame.  Clear it together with
        // its detector so neither executor can inherit a stale terminal.
        NativeBridge.syncPatchCrumbsToSimpleAssist();
    }

    @Override
    public void onSettingsChanged() {
        GlobalModuleNotifications.setEnabled(
                setting(SETTING_MODULE_NOTIFICATIONS).getValBoolean());
    }

    @XenonEvent
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!this.enabled || !NativeBridge.isNativeAvailable()
                || event.phase != TickEvent.Phase.START
                || this.minecraft.thePlayer == null || this.minecraft.theWorld == null) {
            return;
        }
        long now = System.currentTimeMillis();
        NativeBridge.expirePatchCrumb(now);
        for (int entityIndex = 0;
             entityIndex < this.minecraft.theWorld.loadedEntityList.size(); entityIndex++) {
            Entity entity = this.minecraft.theWorld.loadedEntityList.get(entityIndex);
            if (setting(2).getValBoolean() && inDispenserRegion(entity)) {
                continue;
            }
            if (entity instanceof EntityTNTPrimed) {
                if (setting(3).getValBoolean()) {
                    crumbSandCheck(entity);
                } else {
                    crumbNoSandCheck(entity);
                }
            }
        }
        // Publish once per producer tick, before SimpleAssist 1D/3D arbitrate.
        // This keeps 3D autonomous when the parent mode intentionally gates
        // the 1D executor off.
        pushActivePatchesToNative();
    }

    private void crumbSandCheck(Entity entity) {
        this.scanCursor.set((int)Math.floor(entity.posX),
                (int)Math.floor(entity.posY - 2.0D),
                (int)Math.floor(entity.posZ));
        net.minecraft.block.Block belowBlock = this.minecraft.theWorld
                .getBlockState(this.scanCursor).getBlock();
        boolean sandBelow = belowBlock instanceof BlockSand || belowBlock instanceof BlockGravel;
        int action = prepareTntCapture(entity, true, sandBelow,
                System.currentTimeMillis());
        if (action == 2) {
            long previous = NativeBridge.patchCrumbTntSnapshotPacked();
            int previousX = unpackX(previous);
            int previousY = unpackY(previous);
            int previousZ = unpackZ(previous);
            checkDirection(entity.posX, entity.posY, entity.posZ);
            int impactY = resolveStableImpactY(previousX, previousY, previousZ);
            NativeBridge.capturePatchCrumb(previousX + 0.5D, impactY, previousZ + 0.5D,
                    System.currentTimeMillis(), 0L);
            NativeBridge.completePatchCrumbTntCapture();
        }
    }

    private void crumbNoSandCheck(Entity entity) {
        long now = System.currentTimeMillis();
        if (prepareTntCapture(entity, false, false, now) != 1) {
            return;
        }
        checkDirection(entity.posX, entity.posY, entity.posZ);
        int impactY = resolveStableImpactY(entity.posX, entity.posY, entity.posZ);
        NativeBridge.capturePatchCrumb(entity.posX, impactY, entity.posZ, now, 1000L);
    }

    private int prepareTntCapture(Entity entity, boolean sandMode, boolean sandBelow,
                                  long now) {
        return NativeBridge.preparePatchCrumbTntCapture(sandMode,
                entity.posX, entity.posY, entity.posZ,
                entity.motionX, entity.motionY, entity.motionZ,
                sandBelow, now);
    }

    private int resolveStableImpactY(double x, double y, double z) {
        return resolveStableImpactY((int)Math.floor(x),
                (int)Math.floor(y + 0.5D), (int)Math.floor(z));
    }

    private int resolveStableImpactY(int blockX, int baseY, int blockZ) {
        int obsidianMask = 0;
        int solidMask = 0;
        for (int index = 0; index < 5; index++) {
            int impactKind = horizontalImpactKind(blockX,
                    baseY + impactOffset(index), blockZ);
            if ((impactKind & 2) != 0) {
                obsidianMask |= 1 << index;
            }
            if ((impactKind & 1) != 0) {
                solidMask |= 1 << index;
            }
        }
        return NativeBridge.resolvePatchCrumbImpactY(baseY, obsidianMask, solidMask);
    }

    private int horizontalImpactKind(int x, int y, int z) {
        int kind = horizontalImpactKindAt(x, y, z - 1)
                | horizontalImpactKindAt(x, y, z + 1)
                | horizontalImpactKindAt(x - 1, y, z)
                | horizontalImpactKindAt(x + 1, y, z);
        return kind;
    }

    private int horizontalImpactKindAt(int x, int y, int z) {
        this.scanCursor.set(x, y, z);
        if (!this.minecraft.theWorld.isBlockLoaded(this.scanCursor)) {
            return 0;
        }
        net.minecraft.block.Block block = this.minecraft.theWorld
                .getBlockState(this.scanCursor).getBlock();
        return block == Blocks.obsidian ? 3 : (block.isFullBlock() ? 1 : 0);
    }

    private static int impactOffset(int index) {
        switch (index) {
            case 1: return -1;
            case 2: return 1;
            case 3: return -2;
            case 4: return 2;
            default: return 0;
        }
    }

    private void checkDirection(double x, double y, double z) {
        int directionMode = setting(4).getModeIndex();
        boolean xCollision = false;
        boolean zCollision = false;
        if (directionMode == 0) {
            xCollision = solid(x - 1.0D, y, z)
                    || solid(x + 1.0D, y, z);
            zCollision = solid(x, y, z - 1.0D)
                    || solid(x, y, z + 1.0D);
        }
        NativeBridge.resolvePatchCrumbDirection(directionMode, xCollision, zCollision);
    }

    public boolean hasActiveCrumb() {
        return this.enabled && NativeBridge.isPatchCrumbActive(System.currentTimeMillis());
    }

    public boolean isTrajectoryNorthSouth() {
        return (NativeBridge.patchCrumbFlags() & FLAG_TRAJECTORY_NORTH_SOUTH) != 0;
    }

    public long getCrumbRevision() {
        return NativeBridge.patchCrumbRevision();
    }

    private void preparePublishedPatches() {
        long crumb = NativeBridge.patchCrumbPackedPos(System.currentTimeMillis());
        if (crumb == Long.MIN_VALUE || this.minecraft.theWorld == null
                || this.minecraft.thePlayer == null) {
            NativeBridge.clearPatchCrumbActivePatches();
            return;
        }
        updatePatchGuide(crumb);
    }

    /** Copies the Rust-owned PatchCrumbs view directly into SimpleAssist's native frame. */
    public void pushActivePatchesToNative() {
        preparePublishedPatches();
        NativeBridge.syncPatchCrumbsToSimpleAssist();
    }

    public String getDebugState() {
        long crumb = NativeBridge.patchCrumbPackedPos(System.currentTimeMillis());
        return "Crumbs enabled=" + this.enabled + " active=" + (crumb != Long.MIN_VALUE)
                + " pos=" + (crumb == Long.MIN_VALUE ? "none"
                : unpackX(crumb) + "," + unpackY(crumb) + "," + unpackZ(crumb))
                + " rev=" + NativeBridge.patchCrumbRevision()
                + " direction=" + (isTrajectoryNorthSouth() ? "N/S" : "E/W")
                + " targeted=" + isTargeted();
    }

    /** Returns the same alignment state used to paint the crumb lines green. */
    public boolean isTargeted() {
        if (!this.enabled || this.minecraft.theWorld == null) {
            return false;
        }
        long crumb = NativeBridge.patchCrumbPackedPos(System.currentTimeMillis());
        return crumb != Long.MIN_VALUE && isCrosshairAtCrumbHeight(unpackY(crumb));
    }

    private boolean solid(double x, double y, double z) {
        this.scanCursor.set((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
        return this.minecraft.theWorld.getBlockState(this.scanCursor).getBlock().isFullBlock();
    }

    private boolean inDispenserRegion(Entity entity) {
        for (int tileIndex = 0;
             tileIndex < this.minecraft.theWorld.loadedTileEntityList.size(); tileIndex++) {
            TileEntity tile = this.minecraft.theWorld.loadedTileEntityList.get(tileIndex);
            if (tile instanceof TileEntityDispenser
                    || tile.getBlockType() instanceof BlockDispenser) {
                BlockPos position = tile.getPos();
                if (Math.abs(position.getX() - entity.posX) <= 16.0D
                        && Math.abs(position.getZ() - entity.posZ) <= 16.0D) {
                    return true;
                }
            }
        }
        return false;
    }

    @XenonEvent
    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!this.enabled || !NativeBridge.isNativeAvailable()
                || this.minecraft.theWorld == null || this.minecraft.thePlayer == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long crumb = NativeBridge.patchCrumbPackedPos(now);
        if (crumb == Long.MIN_VALUE) {
            return;
        }
        double viewX = this.minecraft.getRenderManager().viewerPosX;
        double viewY = this.minecraft.getRenderManager().viewerPosY;
        double viewZ = this.minecraft.getRenderManager().viewerPosZ;
        float width = setting(5).getValfloat();
        int crumbX = unpackX(crumb);
        int crumbY = unpackY(crumb);
        int crumbZ = unpackZ(crumb);
        double x = crumbX;
        double y = crumbY;
        double z = crumbZ;
        int crumbFlags = NativeBridge.patchCrumbFlags();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.pushMatrix();
        try {
            // RenderWorldLastEvent may inherit lighting/fog/color state from another
            // world layer. Establish a complete, deterministic state for the crumb.
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_COLOR_MATERIAL);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
            GL11.glLineWidth(width);
            if (setting(SETTING_RENDER_V2).getValBoolean()) {
                // V2 replaces PatchCrumbs' original cardinal guide lines.
                // It intentionally has no dependency on Render Patches,
                // whose native records are a separate debug subsystem.
                renderOriginalLinesV2(crumb, crumbFlags, viewX, viewY, viewZ, width);
                return;
            }
            GL11.glColor4f(1.0F, 0.0F, 0.0F, 1.0F);

            drawWireBox(x - viewX, y - viewY, z - viewZ,
                    x + 1.0D - viewX, y + 1.0D - viewY, z + 1.0D - viewZ);
            boolean crosshairAligned = isCrosshairAtCrumbHeight(crumbY);
            if (crosshairAligned) {
                GL11.glColor4f(0.0F, 1.0F, 0.0F, 1.0F);
            }
            if ((crumbFlags & FLAG_DRAW_EAST_WEST) != 0) {
                drawWireBox(x - 199.5D - viewX, y - viewY, z - viewZ,
                        x + 200.5D - viewX, y + 1.0D - viewY, z + 1.0D - viewZ);
            }
            if ((crumbFlags & FLAG_DRAW_NORTH_SOUTH) != 0) {
                drawWireBox(x - viewX, y - viewY, z - 199.5D - viewZ,
                        x + 1.0D - viewX, y + 1.0D - viewY, z + 200.5D - viewZ);
            }
            if (setting(SETTING_RENDER_PATCHES).getValBoolean()) {
                renderPatchGuide(crumb, viewX, viewY, viewZ);
            }
            GL11.glColor4f(1.0F, 0.0F, 0.0F, 1.0F);
            drawFilledBox(x - viewX, y - viewY, z - viewZ);

            if (setting(0).getValBoolean()) {
                drawTracer(x + 0.5D - viewX, y + 0.5D - viewY, z + 0.5D - viewZ);
            }
            if (setting(1).getValBoolean()) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                drawLabel(x + 0.5D - viewX, y + 2.5D - viewY, z + 0.5D - viewZ,
                        crumbX, crumbY, crumbZ);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
            }
        } finally {
            GlStateManager.popMatrix();
            GL11.glPopAttrib();
        }
        if (this.debugRouteVisible) {
            TresDPrueba.render(this.minecraft, new BlockPos(crumbX, crumbY, crumbZ),
                    isTrajectoryNorthSouth());
        }
    }

    private boolean isCrosshairAtCrumbHeight(double crumbY) {
        MovingObjectPosition hit = this.minecraft.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null || hit.hitVec == null) {
            return false;
        }
        Material material = this.minecraft.theWorld.getBlockState(hit.getBlockPos()).getBlock().getMaterial();
        if (material == Material.air || material.isLiquid()) {
            return false;
        }
        double hitY = hit.hitVec.yCoord;
        return hitY >= crumbY && hitY <= crumbY + 1.0D;
    }

    private void renderPatchGuide(long crumb, double viewX, double viewY, double viewZ) {
        double crumbCenterX = unpackX(crumb) + 0.5D;
        double crumbY = unpackY(crumb);
        double crumbCenterZ = unpackZ(crumb) + 0.5D;
        if (this.minecraft.thePlayer.getDistanceSq(
                crumbCenterX, crumbY, crumbCenterZ) > PATCH_RENDER_DISTANCE_SQ) {
            return;
        }
        updatePatchGuide(crumb);
        NativeBridge.expirePatchCrumbLines(System.currentTimeMillis());
        GL11.glColor4f(0.0F, 0.75F, 1.0F, 1.0F);
        int lineCount = NativeBridge.patchCrumbLineCount();
        for (int index = 0; index < lineCount; index++) {
            long origin = NativeBridge.patchCrumbLineOrigin(index);
            long terminal = NativeBridge.patchCrumbLineTerminal(index);
            if (origin != Long.MIN_VALUE && terminal != Long.MIN_VALUE) {
                renderPackedPatchLine(origin, terminal, viewX, viewY, viewZ);
            }
        }
    }

    /** Clean V2 rendering for PatchCrumbs' two original cardinal axes. */
    private static void renderOriginalLinesV2(long crumb, int flags,
                                              double viewX, double viewY, double viewZ,
                                              float lineWidth) {
        // Match the original PatchCrumbs visibility: guides are useful even
        // when their anchor sits behind terrain or water walls.
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glLineWidth(lineWidth);
        long pulse = System.currentTimeMillis() / 180L;
        double centerX = unpackX(crumb) + 0.5D - viewX;
        double centerY = unpackY(crumb) + 0.55D - viewY;
        double centerZ = unpackZ(crumb) + 0.5D - viewZ;
        if ((flags & FLAG_DRAW_EAST_WEST) != 0) {
            drawSegmentedAxis(centerX - 200.0D, centerY, centerZ,
                    centerX + 200.0D, centerY, centerZ,
                    0.67F, 0.26F, 1.0F, pulse);
        }
        if ((flags & FLAG_DRAW_NORTH_SOUTH) != 0) {
            drawSegmentedAxis(centerX, centerY, centerZ - 200.0D,
                    centerX, centerY, centerZ + 200.0D,
                    0.10F, 0.82F, 1.0F, pulse);
        }
    }

    private void updatePatchGuide(long crumb) {
        int currentTick = this.minecraft.thePlayer.ticksExisted;
        int crumbX = unpackX(crumb);
        int crumbY = unpackY(crumb);
        int crumbZ = unpackZ(crumb);
        int playerCellX = (int)Math.floor(this.minecraft.thePlayer.posX);
        int playerCellY = (int)Math.floor(this.minecraft.thePlayer.posY);
        int playerCellZ = (int)Math.floor(this.minecraft.thePlayer.posZ);
        long packedPlayerCell = NativeBridge.packBlockPos(
                playerCellX, playerCellY, playerCellZ);
        boolean trajectoryNorthSouth = isTrajectoryNorthSouth();
        int patchSide = trajectoryNorthSouth
                ? (this.minecraft.thePlayer.posX >= crumbX + 0.5D ? -1 : 1)
                : (this.minecraft.thePlayer.posZ >= crumbZ + 0.5D ? -1 : 1);
        long crumbRevision = NativeBridge.patchCrumbRevision();
        if (!NativeBridge.shouldScanPatchCrumbGuide(currentTick, crumbRevision,
                packedPlayerCell, patchSide, PATCH_SCAN_INTERVAL_TICKS)) {
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + PATCH_PERSISTENCE_MS;
        NativeBridge.beginPatchCrumbPatchScan(now);
        int stepX = trajectoryNorthSouth ? patchSide : 0;
        int stepZ = trajectoryNorthSouth ? 0 : patchSide;
        for (int offset = -PATCH_AXIS_RENDER_RADIUS;
                offset <= PATCH_AXIS_RENDER_RADIUS; offset++) {
            int originX = trajectoryNorthSouth ? crumbX : playerCellX + offset;
            int originY = crumbY;
            int originZ = trajectoryNorthSouth ? playerCellZ + offset : crumbZ;
            long obsidianMask = 0L;
            long blockingMask = 0L;
            for (int distance = 1; distance <= PATCH_SCAN_RADIUS; distance++) {
                this.scanCursor.set(originX + stepX * distance, originY,
                        originZ + stepZ * distance);
                long bit = 1L << (distance - 1);
                if (!this.minecraft.theWorld.isBlockLoaded(this.scanCursor)) {
                    blockingMask |= bit;
                    continue;
                }
                net.minecraft.block.Block block = this.minecraft.theWorld
                        .getBlockState(this.scanCursor).getBlock();
                if (block == Blocks.obsidian) {
                    obsidianMask |= bit;
                    blockingMask |= bit;
                } else if (block.isFullBlock()) {
                    blockingMask |= bit;
                }
            }
            NativeBridge.submitPatchCrumbScanLane(
                    NativeBridge.packBlockPos(originX, originY, originZ),
                    obsidianMask, blockingMask, expiresAt);
        }
    }

    private static void renderPackedPatchLine(long origin, long terminal,
            double viewX, double viewY, double viewZ) {
        int originX = unpackX(origin);
        int originY = unpackY(origin);
        int originZ = unpackZ(origin);
        int terminalX = unpackX(terminal);
        int terminalZ = unpackZ(terminal);
        drawWireBox(
                Math.min(originX, terminalX) - viewX,
                originY - viewY,
                Math.min(originZ, terminalZ) - viewZ,
                Math.max(originX, terminalX) + 1.0D - viewX,
                originY + 1.0D - viewY,
                Math.max(originZ, terminalZ) + 1.0D - viewZ);
    }

    /** Draws a low-noise dashed axis with one subtle travelling pulse. */
    private static void drawSegmentedAxis(double startX, double startY, double startZ,
                                          double endX, double endY, double endZ,
                                          float red, float green, float blue, long pulse) {
        final int segments = 100;
        GL11.glBegin(GL11.GL_LINES);
        for (int segment = 0; segment < segments; segment++) {
            double from = (double)segment / (double)segments;
            double to = (double)(segment + 1) / (double)segments;
            boolean pulseSegment = segment % 4 == (int)(pulse % 4L);
            float alpha = pulseSegment ? 0.92F : (segment & 1) == 0 ? 0.58F : 0.30F;
            GL11.glColor4f(red, green, blue, alpha);
            GL11.glVertex3d(startX + (endX - startX) * from, startY + (endY - startY) * from,
                    startZ + (endZ - startZ) * from);
            GL11.glVertex3d(startX + (endX - startX) * to, startY + (endY - startY) * to,
                    startZ + (endZ - startZ) * to);
        }
        GL11.glEnd();
    }

    private static void drawWireBox(double x1, double y1, double z1,
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

    private static int unpackX(long packed) { return (int)(packed >> 38); }
    private static int unpackY(long packed) { return (int)(packed << 26 >> 52); }
    private static int unpackZ(long packed) { return (int)(packed << 38 >> 38); }

    private static String text(int length, long... blocks) {
        char[] output = new char[length];
        for (int index = 0; index < length; index++) {
            long packed = blocks[index >> 2];
            output[index] = (char)(((packed >>> ((index & 3) << 4)) & 0xFFFFL) ^ 0x5A5AL);
        }
        return new String(output);
    }

    private void drawFilledBox(double x, double y, double z) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer renderer = tessellator.getWorldRenderer();
        GL11.glColor4f(1.0F, 0.0F, 0.0F, 0.5F);
        renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        addBoxVertices(renderer, x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
        tessellator.draw();
    }

    private static void addBoxVertices(WorldRenderer wr, double x1, double y1, double z1,
            double x2, double y2, double z2) {
        wr.pos(x1,y1,z2).endVertex(); wr.pos(x2,y1,z2).endVertex(); wr.pos(x2,y1,z1).endVertex(); wr.pos(x1,y1,z1).endVertex();
        wr.pos(x1,y2,z2).endVertex(); wr.pos(x1,y2,z1).endVertex(); wr.pos(x2,y2,z1).endVertex(); wr.pos(x2,y2,z2).endVertex();
        wr.pos(x1,y1,z2).endVertex(); wr.pos(x1,y2,z2).endVertex(); wr.pos(x2,y2,z2).endVertex(); wr.pos(x2,y1,z2).endVertex();
        wr.pos(x1,y1,z1).endVertex(); wr.pos(x2,y1,z1).endVertex(); wr.pos(x2,y2,z1).endVertex(); wr.pos(x1,y2,z1).endVertex();
        wr.pos(x1,y1,z1).endVertex(); wr.pos(x1,y2,z1).endVertex(); wr.pos(x1,y2,z2).endVertex(); wr.pos(x1,y1,z2).endVertex();
        wr.pos(x2,y1,z1).endVertex(); wr.pos(x2,y1,z2).endVertex(); wr.pos(x2,y2,z2).endVertex(); wr.pos(x2,y2,z1).endVertex();
    }

    private void drawTracer(double x, double y, double z) {
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(0.0D, this.minecraft.thePlayer.getEyeHeight(), 0.0D);
        GL11.glVertex3d(x, y, z);
        GL11.glEnd();
    }

    private void drawLabel(double x, double y, double z,
                           int crumbX, int crumbY, int crumbZ) {
        String label = "X: " + (crumbX + 1) + " Y: " + crumbY
                + " Z: " + (crumbZ + 1);
        float scale = Math.max(0.04F, Math.min(1.0F,
                (float)this.minecraft.thePlayer.getDistance(
                        crumbX + 0.5D, crumbY, crumbZ + 0.5D) * 0.003F));
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.rotate(-this.minecraft.getRenderManager().playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(this.minecraft.getRenderManager().playerViewX, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-scale, -scale, scale);
        this.minecraft.fontRendererObj.drawString(label,
                -this.minecraft.fontRendererObj.getStringWidth(label) / 2, 0, LABEL_COLOR_ARGB);
        GlStateManager.popMatrix();
    }

    private void resetDetectionState() {
        NativeBridge.resetPatchCrumbState();
    }

    private Settings setting(int index) {
        return this.sl.get(index);
    }
}

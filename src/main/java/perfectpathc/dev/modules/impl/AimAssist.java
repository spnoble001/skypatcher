package perfectpathc.dev.modules.impl;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import org.lwjgl.input.Mouse;
import perfectpathc.dev.modules.runtime.IModuleEntry;
import perfectpathc.dev.PerfectPatch;

/** Smooth, visibility-aware aim assistance exposed by the rguia interface. */
@Mod(keybind = 0)
public final class AimAssist extends Module implements IModuleEntry {
    private static final int MAX_CANDIDATES = 128;
    private static final int FLAG_VISIBLE = 1;
    private static final int FLAG_TEAMMATE = 2;
    private static final int FLAG_SELF = 4;
    private static final int FLAG_DEAD = 8;
    private static final int FLAG_POINTED = 16;

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        if (commandId == 13) {
            onRenderTick(((Float) args[0]).floatValue());
            return null;
        }
        return super.invokeCommand(commandId, args);
    }

    private static final String T0 = text(3, 0x00005a0c5a155a1cL);
    private static final String T1 = text(5, 0x5a3f5a3f5a2a5a09L, 0x0000000000005a3eL);
    private static final String T2 = text(3, 0x00005a345a335a17L);
    private static final String T3 = text(3, 0x00005a225a3b5a17L);
    private static final String T4 = text(5, 0x5a395a335a365a19L, 0x0000000000005a31L);
    private static final String T5 = text(5, 0x5a285a355a2d5a09L, 0x0000000000005a3eL);
    private static final String T6 = text(3, 0x00005a3f5a225a1bL);
    private static final String T7 = text(4, 0x5a315a395a355a16L);
    private static final String T8 = text(5, 0x5a295a335a355a14L, 0x0000000000005a3fL);
    private static final String T9 = text(11, 0x5a295a335a355a14L, 0x5a2a5a095a7a5a3fL, 0x00005a3e5a3f5a3fL);
    private static final String T10 = text(10, 0x5a7a5a375a335a1bL, 0x5a335a295a295a1bL, 0x000000005a2e5a29L);

    public AimAssist() {
        this.sl.add(new Settings(T0.toCharArray(), this,
                70.0F, 10.0F, 180.0F, true));
        this.sl.add(new Settings(T1.toCharArray(), this,
                1.0F, 1.0F, 20.0F, false));
        this.sl.add(new Settings(T2.toCharArray(), this, 1.8F, 0.1F, 3.0F, false));
        this.sl.add(new Settings(T3.toCharArray(), this, 4.5F, 3.0F, 6.0F, false));
        this.sl.add(new Settings(T4.toCharArray(), this, true));
        this.sl.add(new Settings(T5.toCharArray(), this, false));
        this.sl.add(new Settings(T6.toCharArray(), this, false));
        this.sl.add(new Settings(T7.toCharArray(), this, true));
        this.sl.add(new Settings(T8.toCharArray(), this, 0.35F, 0.0F, 2.0F, false));
        this.sl.add(new Settings(T9.toCharArray(), this, 0.08F, 0.01F, 0.3F, false));
        this.sl.add(new Settings("Keybind".toCharArray(), this));
        registerSettings();
        this.name = T10;
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
    public char getSort() {
        return 'a';
    }

    @Override
    public void onEnable() {
        if (!NativeBridge.isNativeAvailable()) {
            setEnabled(false);
            return;
        }
        try {
            NativeBridge.initializeAimState();
        } catch (IllegalStateException exception) {
            setEnabled(false);
        }
    }

    public void onRenderTick(float partialTick) {
        if (!this.enabled || this.minecraft.theWorld == null || this.minecraft.thePlayer == null
                || this.minecraft.currentScreen != null) {
            discardNativeAimState();
            return;
        }
        if (!NativeBridge.isNativeAvailable()) {
            setEnabled(false);
            return;
        }
        updateAim(partialTick);
    }

    private void updateAim(float partialTick) {
        float fov = setting(0).getValfloat();
        float speed = setting(1).getValfloat();
        float minimumDistance = setting(2).getValfloat();
        float maximumDistance = setting(3).getValfloat();
        boolean click = setting(4).getValBoolean();
        collectCandidates();
        EntityPlayerSP player = this.minecraft.thePlayer;
        long action;
        try {
            action = NativeBridge.nextCapturedAimActionPacked((float) player.posX,
                    (float) player.posY, (float) player.posZ, player.rotationYaw,
                    player.rotationPitch, player.ticksExisted, partialTick,
                    this.minecraft.gameSettings.mouseSensitivity, fov, speed / 10.0F,
                    minimumDistance, maximumDistance, setting(7).getValBoolean(), click,
                    Mouse.isButtonDown(0), setting(5).getValBoolean(),
                    setting(6).getValBoolean(), heldItemKind(), setting(8).getValfloat(),
                    setting(9).getValfloat());
        } catch (IllegalStateException exception) {
            setEnabled(false);
            return;
        }
        if (action == Long.MIN_VALUE) return;
        float yawDelta = Float.intBitsToFloat((int) (action >>> 32));
        float pitch = Float.intBitsToFloat((int) action);
        player.rotationYaw += yawDelta;
        player.prevRotationYaw += yawDelta;
        player.rotationPitch = pitch;
        player.prevRotationPitch = pitch;
    }

    private void collectCandidates() {
        NativeBridge.beginAimCapture();
        for (int index = 0, count = 0; index < this.minecraft.theWorld.playerEntities.size()
                && count < MAX_CANDIDATES; index++) {
            EntityPlayer entity = this.minecraft.theWorld.playerEntities.get(index);
            // The original lock follows a player's UUID, not the ephemeral entity id.
            int entityId = entity.getUniqueID().hashCode();
            int flags = this.minecraft.thePlayer.canEntityBeSeen(entity) ? FLAG_VISIBLE : 0;
            if (PerfectPatch.instance != null && PerfectPatch.instance.getModule(6) != null
                    && Boolean.TRUE.equals(PerfectPatch.instance.invokeModuleCommand(6, 10, entity))) flags |= FLAG_TEAMMATE;
            if (entity == this.minecraft.thePlayer) flags |= FLAG_SELF;
            if (entity.isDead) flags |= FLAG_DEAD;
            if (entity == this.minecraft.pointedEntity) flags |= FLAG_POINTED;
            net.minecraft.util.AxisAlignedBB box = entity.getEntityBoundingBox();
            NativeBridge.captureAimCandidate(entityId, flags, (float) entity.posX,
                    (float) entity.posY, (float) entity.posZ, (float) box.minX,
                    (float) box.maxX, (float) box.minZ, (float) box.maxZ, (float) box.maxY);
            count++;
        }
    }

    private int heldItemKind() {
        if (this.minecraft.thePlayer.getHeldItem() == null) return 0;
        return CombatMinecraftAccess.classifyHeldItem(
                this.minecraft.thePlayer.getHeldItem().getItem());
    }

    private void resetTracking() {
        discardNativeAimState();
    }

    private void discardNativeAimState() {
        if (NativeBridge.isNativeAvailable()) {
            try {
                NativeBridge.resetAimState();
            } catch (IllegalStateException ignored) {
                // Native execution is mandatory; the next render tick disables the module.
            }
        }
    }

    @Override
    public void onDisable() {
        super.onDisable();
        resetTracking();
    }

    @Override
    public void onSettingsChanged() {
    }

    private Settings setting(int index) {
        return this.sl.get(index);
    }

    private static String text(int length, long... blocks) {
        char[] output = new char[length];
        for (int index = 0; index < length; index++) {
            long packed = blocks[index >> 2];
            output[index] = (char) (((packed >>> ((index & 3) << 4)) & 0xFFFFL) ^ 0x5A5AL);
        }
        return new String(output);
    }
}

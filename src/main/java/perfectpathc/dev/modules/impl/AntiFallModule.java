package perfectpathc.dev.modules.impl;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.block.material.Material;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import perfectpathc.dev.modules.impl.events.XenonEvent;
import perfectpathc.dev.modules.runtime.IModuleEntry;

/** Primitive Minecraft adapter for the Rust-owned AntiFall model. */
@Mod(keybind = 0)
public final class AntiFallModule extends Module implements IModuleEntry {
    private static final int STRATEGY_MASK = 3;
    private static final int STRATEGY_JUMP = 1;
    private static final int FLAG_SET_INPUT = 1 << 2;
    private static final int FLAG_SET_MOTION = 1 << 3;
    private static final double INPUT_SPEED = 0.10D;
    /** Production calibration; intentionally not exposed as UI controls. */
    private static final float JUMP_THRESHOLD = 0.10F;
    private static final float SUPPORT_MARGIN = 0.05F;
    private static final boolean GAP_JUMP_ENABLED = true;
    private static final int SETTING_INSTANT = 0;
    /* Intentional look-down gesture for descending a water column. */
    private static final float DESCENT_BYPASS_ENTER_PITCH = 85.0F;
    private static final float DESCENT_BYPASS_EXIT_PITCH = 78.0F;
    private static final double DESCENT_BYPASS_MAX_HORIZONTAL_SPEED_SQ = 0.0016D;
    private boolean descentBypass;

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        if (commandId == 17) {
            onPlayerTick((TickEvent.PlayerTickEvent) args[0]);
            return null;
        }
        return super.invokeCommand(commandId, args);
    }

    private static final String T0 = text(12, 0x5a285a385a375a0fL, 0x5a295a7a5a365a3bL, 0x5a355a2e5a365a3bL);
    private static final String T1 = text(6, 0x5a3d5a285a3b5a17L, 0x000000005a345a3fL);
    private static final String T2 = text(8, 0x5a7a5a2a5a3b5a1dL, 0x5a2a5a375a2f5a10L);
    private static final String T3 = text(9, 0x5a2e5a295a345a13L, 0x5a3f5a2e5a345a3bL, 0x0000000000005a35L);
    private static final String T4 = text(7, 0x5a385a235a3f5a11L, 0x00005a3e5a345a33L);
    private static final String T5 = text(6, 0x5a3b5a3f5a395a15L, 0x000000005a355a34L);
    private static final String T6 = text(15, 0x5a3b5a3f5a395a15L, 0x5a3f5a7a5a355a34L, 0x5a365a385a3b5a34L, 0x00005a675a3e5a3fL);
    private static final String T7 = text(10, 0x5a285a2e5a295a7aL, 0x5a3d5a3f5a2e5a3bL, 0x000000005a675a23L);
    private static final String T8 = text(12, 0x5a195a1f5a025a1fL, 0x5a055a1f5a0e5a0fL, 0x5a0a5a175a0f5a10L);
    private static final String T9 = text(16, 0x5a135a1e5a1f5a08L, 0x5a0e5a195a1f5a08L, 0x5a145a1b5a0e5a05L, 0x5a0e5a145a1f5a1dL);
    private static final String T10 = text(13, 0x5a155a195a1f5a08L, 0x5a055a085a1f5a0cL, 0x5a1f5a0e5a1b5a0dL, 0x0000000000005a08L);
    private static final String T11 = text(14, 0x5a155a165a165a1bL, 0x5a155a175a055a0dL, 0x5a0e5a145a1f5a17L, 0x000000005a175a0fL);

    public AntiFallModule() {
        this.sl.add(new Settings("Instantaneo".toCharArray(), this, false));
        this.sl.add(new Settings(T4.toCharArray(), this));
        registerSettings();
        this.name = T5;
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
        NativeBridge.resetAntiFall();
    }

    @Override
    public void onDisable() {
        this.descentBypass = false;
        NativeBridge.resetAntiFall();
    }

    @XenonEvent
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        EntityPlayerSP player = event.player instanceof EntityPlayerSP
                ? (EntityPlayerSP) event.player : null;
        World world = this.minecraft.theWorld;
        if (!this.enabled || event.phase != TickEvent.Phase.START || player == null
                || world == null || !NativeBridge.isNativeAvailable()) return;
        if (updateDescentBypass(player, world)) {
            // Cancel any recovery state captured before the deliberate descent.
            // The module toggle remains untouched and resumes automatically.
            NativeBridge.resetAntiFall();
            return;
        }
        if (player.isInWater() || player.capabilities.isFlying
                || player.isOnLadder() || player.isSneaking()) return;

        boolean onGround = player.onGround;
        boolean falling = !onGround && player.motionY < 0.0D && player.fallDistance > 0.5F;
        if (!onGround && !falling) return;

        float forward = player.movementInput.moveForward;
        float strafe = player.movementInput.moveStrafe;
        float radians = player.rotationYaw * (float) Math.PI / 180.0F;
        double moveX = (-forward * Math.sin(radians) + strafe * Math.cos(radians)) * INPUT_SPEED;
        double moveZ = (forward * Math.cos(radians) + strafe * Math.sin(radians)) * INPUT_SPEED;

        boolean supported = false;
        boolean xSupported = false;
        boolean zSupported = false;
        long grid = 0L;
        long recoveryLow = 0L;
        long recoveryHigh = 0L;

        int baseX = (int) Math.floor(player.posX);
        int baseY = (int) Math.floor(player.posY);
        int baseZ = (int) Math.floor(player.posZ);
        if (onGround) {
            double margin = SUPPORT_MARGIN;
            supported = MovementMinecraftAccess.isSupported(player, world, moveX, moveZ, margin);
            xSupported = MovementMinecraftAccess.isSupported(player, world, moveX, 0.0D, margin);
            zSupported = MovementMinecraftAccess.isSupported(player, world, 0.0D, moveZ, margin);
            int gridY = baseY - 1;
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    int index = (x + 2) * 5 + z + 2;
                    int kind = MovementMinecraftAccess.blockKind(
                            world, baseX + x, gridY, baseZ + z);
                    grid |= (long) kind << (index << 1);
                }
            }
        } else {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (x == 0 && z == 0) continue;
                    int encodedDrop = 0;
                    for (int drop = 0; drop <= 3; drop++) {
                        if (MovementMinecraftAccess.isWater(
                                world, baseX + x, baseY - drop, baseZ + z)) {
                            encodedDrop = drop + 1;
                            break;
                        }
                    }
                    if (encodedDrop == 0) continue;
                    int index = (x + 2) * 5 + z + 2;
                    if (index < 16) recoveryLow |= (long) encodedDrop << (index << 2);
                    else recoveryHigh |= (long) encodedDrop << ((index - 16) << 2);
                }
            }
        }

        int action;
        try {
            action = NativeBridge.nextAntiFallAction(onGround, player.fallDistance,
                    player.rotationYaw, forward, strafe,
                    player.posX, player.posY, player.posZ,
                    player.motionX, player.motionY, player.motionZ,
                    moveX, moveZ, supported, xSupported, zSupported,
                    JUMP_THRESHOLD, GAP_JUMP_ENABLED,
                    setting(SETTING_INSTANT).getValBoolean(), grid, recoveryLow, recoveryHigh);
        } catch (IllegalStateException ignored) {
            return;
        }

        if ((action & STRATEGY_MASK) == STRATEGY_JUMP) player.jump();
        if ((action & FLAG_SET_INPUT) != 0) {
            player.movementInput.moveForward = ((action >>> 8) & 3) - 1;
            player.movementInput.moveStrafe = ((action >>> 10) & 3) - 1;
        }
        if ((action & FLAG_SET_MOTION) != 0) {
            player.motionX = NativeBridge.antiFallMotionX();
            player.motionZ = NativeBridge.antiFallMotionZ();
        }
    }

    private Settings setting(int index) {
        return this.sl.get(index);
    }

    /**
     * A near-vertical look while calmly falling through open air is an
     * explicit descent gesture.  Do not arm at a ledge or while travelling:
     * both are normal navigation states where Oceano must remain available.
     */
    private boolean updateDescentBypass(EntityPlayerSP player, World world) {
        if (!isCalmOpenAir(player, world)) {
            this.descentBypass = false;
            return false;
        }
        if (this.descentBypass) {
            if (player.rotationPitch <= DESCENT_BYPASS_EXIT_PITCH) {
                this.descentBypass = false;
            }
        } else if (player.rotationPitch >= DESCENT_BYPASS_ENTER_PITCH) {
            this.descentBypass = true;
        }
        return this.descentBypass;
    }

    private static boolean isCalmOpenAir(EntityPlayerSP player, World world) {
        if (player.onGround || player.isInWater() || player.isOnLadder()) return false;
        if (player.motionX * player.motionX + player.motionZ * player.motionZ
                > DESCENT_BYPASS_MAX_HORIZONTAL_SPEED_SQ) return false;
        BlockPos feet = new BlockPos(player.posX, player.posY, player.posZ);
        BlockPos head = feet.up();
        return world.getBlockState(feet).getBlock().getMaterial() == Material.air
                && world.getBlockState(head).getBlock().getMaterial() == Material.air;
    }

    public String getDebugState() {
        int strategy = NativeBridge.isNativeAvailable() ? NativeBridge.antiFallStrategy() : 0;
        return T6 + this.enabled + T7 + strategyName(strategy);
    }

    private static String strategyName(int strategy) {
        switch (strategy) {
            case 1: return T8;
            case 2: return T9;
            case 3: return T10;
            default: return T11;
        }
    }

    @Override
    public char getSort() {
        return 'm';
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

package perfectpathc.dev.modules.impl;

import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/** Minecraft-only sampling used by the primitive AntiFall adapter. */
final class MovementMinecraftAccess {
    private static final BlockPos.MutableBlockPos POSITION = new BlockPos.MutableBlockPos();

    private MovementMinecraftAccess() {
    }

    static int blockKind(World world, int x, int y, int z) {
        POSITION.set(x, y, z);
        Material material = world.getBlockState(POSITION).getBlock().getMaterial();
        if (material == Material.water) return 1;
        if (material == Material.air) return 0;
        return material.isSolid() ? 2 : 0;
    }

    static boolean isWater(World world, int x, int y, int z) {
        POSITION.set(x, y, z);
        return world.getBlockState(POSITION).getBlock().getMaterial() == Material.water;
    }

    static boolean isSupported(EntityPlayerSP player, World world,
                               double offsetX, double offsetZ, double margin) {
        AxisAlignedBB ground = player.getEntityBoundingBox()
                .offset(offsetX, 0.0D, offsetZ)
                .contract(margin, 0.0D, margin)
                .offset(0.0D, -0.5D, 0.0D);
        return !world.getCollidingBoundingBoxes(player, ground).isEmpty();
    }
}

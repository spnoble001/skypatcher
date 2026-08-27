package perfectpathc.dev.modules.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/** Stateless Minecraft boundary for the primitive data SimpleAssist sends to Rust. */
final class SimpleAssistMinecraftAccess {
    private static final int SNAPSHOT_REPLACEABLE = 1 << 16;
    private static final int SNAPSHOT_COLLIDABLE = 1 << 17;
    private static final BlockPos.MutableBlockPos SNAPSHOT_CURSOR =
            new BlockPos.MutableBlockPos();
    private SimpleAssistMinecraftAccess() {
    }

    static int getPlayerPing(Minecraft minecraft) {
        if (minecraft == null || minecraft.thePlayer == null
                || minecraft.getNetHandler() == null) {
            return 0;
        }
        NetworkPlayerInfo info = minecraft.getNetHandler()
                .getPlayerInfo(minecraft.thePlayer.getUniqueID());
        return info == null ? 0 : Math.max(0, info.getResponseTime());
    }

    static int getBlockSnapshot(World world, long packedPosition) {
        SNAPSHOT_CURSOR.set(
                (int)(packedPosition >> 38),
                (int)(packedPosition << 26 >> 52),
                (int)(packedPosition << 38 >> 38));
        return getBlockSnapshot(world, SNAPSHOT_CURSOR);
    }

    static int getBlockSnapshot(World world, BlockPos position) {
        if (world == null || position == null) {
            return -1;
        }
        Block block = world.getBlockState(position).getBlock();
        int snapshot = Block.getIdFromBlock(block) & 0xFFFF;
        if (block.isReplaceable(world, position)) {
            snapshot |= SNAPSHOT_REPLACEABLE;
        }
        if (block.canCollideCheck(world.getBlockState(position), false)) {
            snapshot |= SNAPSHOT_COLLIDABLE;
        }
        return snapshot;
    }

    static int blockId(int snapshot) {
        return snapshot < 0 ? -1 : snapshot & 0xFFFF;
    }

    static int getHeldBlockId(ItemStack held) {
        return held != null && held.getItem() instanceof ItemBlock
                ? Block.getIdFromBlock(((ItemBlock)held.getItem()).getBlock()) : -1;
    }

    static int getObsidianBlockId() {
        return Block.getIdFromBlock(Blocks.obsidian);
    }

    static int getBedrockBlockId() {
        return Block.getIdFromBlock(Blocks.bedrock);
    }
}

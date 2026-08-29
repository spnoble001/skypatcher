package perfectpathc.dev.modules.impl.utils;

import net.minecraft.block.Block;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Small, allocation-free quarantine for faces recently created by an assisted
 * placement.  A manual RMB action never reaches {@link #expectAssistedPlacement},
 * so it remains immediately available to PrePatch.
 *
 * <p>The key deliberately remains split into position and face: BlockPos uses
 * all 64 bits of its packed representation, therefore shifting it to append a
 * face would silently create collisions.</p>
 */
public final class AnchorQuarantineRegistry {
    private static final int CAPACITY = 64;
    private static final int QUARANTINE_TICKS = 20;
    private static final int CONFIRM_TIMEOUT_TICKS = 12;
    private static final byte EMPTY = 0;
    private static final byte PENDING = 1;
    private static final byte QUARANTINED = 2;
    private static final byte TOMBSTONE = 3;
    private static final byte ORIGIN_PREPATCH = 1;
    private static final byte ORIGIN_SIMPLE_ASSIST = 2;
    private static final AnchorQuarantineRegistry SHARED = new AnchorQuarantineRegistry();

    private final long[] positions = new long[CAPACITY];
    private final byte[] faces = new byte[CAPACITY];
    private final byte[] states = new byte[CAPACITY];
    private final byte[] origins = new byte[CAPACITY];
    private final int[] expectedBlockIds = new int[CAPACITY];
    private final int[] expiresAt = new int[CAPACITY];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private World world;

    private AnchorQuarantineRegistry() {
    }

    public static AnchorQuarantineRegistry shared() {
        return SHARED;
    }

    /** Records only a dispatch produced by SimpleAssist 1D or 3D. */
    public void expectAssistedPlacement(long position, int face, int blockId, int tick) {
        expect(position, face, blockId, tick, ORIGIN_SIMPLE_ASSIST);
    }

    /** Records the synthetic dispatch emitted by PrePatch's own clicker. */
    public void expectPrePatchPlacement(long position, int face, int blockId, int tick) {
        expect(position, face, blockId, tick, ORIGIN_PREPATCH);
    }

    private void expect(long position, int face, int blockId, int tick, byte origin) {
        if (position == Long.MIN_VALUE || face < 0 || face > 5 || blockId < 0) return;
        int index = findSlot(position, face, true);
        if (index < 0) return;
        this.positions[index] = position;
        this.faces[index] = (byte)face;
        this.expectedBlockIds[index] = blockId;
        this.origins[index] = origin;
        this.expiresAt[index] = tick + CONFIRM_TIMEOUT_TICKS;
        this.states[index] = PENDING;
    }

    /**
     * Promotes only confirmed local world mutations.  Rejected packets simply
     * leave their pending expectation to expire; no face is ever quarantined.
     */
    public void observe(World nextWorld, int tick) {
        if (nextWorld == null) {
            clear();
            return;
        }
        if (this.world != nextWorld) {
            clear();
            this.world = nextWorld;
        }
        for (int index = 0; index < CAPACITY; index++) {
            byte state = this.states[index];
            if (state != PENDING && state != QUARANTINED) continue;
            if (tick >= this.expiresAt[index]) {
                remove(index);
                continue;
            }
            unpack(this.positions[index]);
            if (!nextWorld.isBlockLoaded(this.cursor)) continue;
            int actualBlockId = Block.getIdFromBlock(nextWorld.getBlockState(this.cursor).getBlock());
            if (state == PENDING) {
                if (actualBlockId == this.expectedBlockIds[index]) {
                    this.states[index] = QUARANTINED;
                    this.expiresAt[index] = tick + QUARANTINE_TICKS;
                }
            } else if (actualBlockId != this.expectedBlockIds[index]) {
                // Destruction or replacement is an eager eviction.
                remove(index);
            }
        }
    }

    /** O(1) expected lookup by the exact block face. */
    public boolean isQuarantined(World nextWorld, long position, int face, int tick) {
        observe(nextWorld, tick);
        int index = findSlot(position, face, false);
        return index >= 0 && this.states[index] == QUARANTINED;
    }

    public void clear() {
        for (int index = 0; index < CAPACITY; index++) this.states[index] = EMPTY;
        this.world = null;
    }

    private int findSlot(long position, int face, boolean create) {
        int start = mix(position, face) & (CAPACITY - 1);
        int firstTombstone = -1;
        for (int step = 0; step < CAPACITY; step++) {
            int index = (start + step) & (CAPACITY - 1);
            byte state = this.states[index];
            if (state == EMPTY) return create ? (firstTombstone >= 0 ? firstTombstone : index) : -1;
            if (state == TOMBSTONE) {
                if (firstTombstone < 0) firstTombstone = index;
            } else if (this.positions[index] == position && this.faces[index] == (byte)face) {
                return index;
            }
        }
        return create ? firstTombstone : -1;
    }

    private void remove(int index) {
        this.states[index] = TOMBSTONE;
        this.expectedBlockIds[index] = -1;
        this.origins[index] = 0;
    }

    private void unpack(long packed) {
        this.cursor.set((int)(packed >> 38), (int)(packed << 26 >> 52),
                (int)(packed << 38 >> 38));
    }

    private static int mix(long position, int face) {
        long value = position ^ (position >>> 33) ^ ((long)face * 0x9E3779B97F4A7C15L);
        value ^= value >>> 29;
        return (int)(value ^ (value >>> 32));
    }
}

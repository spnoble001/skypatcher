package perfectpathc.dev.modules.impl.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

/**
 * Local Forge equivalent of Orbit's connected voxel outline helper.
 * It intentionally has no renderer/event dependency so modules can choose
 * whether to draw the generated segments with OpenGL or RenderGlobal.
 */
public final class ConnectedVoxelOutlineUtil {
    private static final EnumFacing[] FACINGS = EnumFacing.values();

    private ConnectedVoxelOutlineUtil() {
    }

    public static final class LineSegmentBuffer {
        private float[] coords = new float[24576];
        private int segmentCount;

        public void clear() {
            this.segmentCount = 0;
        }

        public int getSegmentCount() {
            return this.segmentCount;
        }

        public float[] getCoords() {
            return this.coords;
        }

        public void addSegment(double x1, double y1, double z1,
                               double x2, double y2, double z2) {
            ensureCapacity(this.segmentCount + 1);
            int offset = this.segmentCount * 6;
            this.coords[offset] = (float)x1;
            this.coords[offset + 1] = (float)y1;
            this.coords[offset + 2] = (float)z1;
            this.coords[offset + 3] = (float)x2;
            this.coords[offset + 4] = (float)y2;
            this.coords[offset + 5] = (float)z2;
            this.segmentCount++;
        }

        private void ensureCapacity(int segments) {
            int required = segments * 6;
            if (this.coords.length >= required) return;
            this.coords = Arrays.copyOf(this.coords,
                    Math.max(required, this.coords.length * 2));
        }
    }

    public static List<List<BlockPos>> splitConnected(List<BlockPos> nodes,
                                                       Set<Long> nodeKeys) {
        List<List<BlockPos>> components = new ArrayList<List<BlockPos>>();
        if (nodes == null || nodes.isEmpty()) return components;
        Set<Long> visited = new HashSet<Long>();
        ArrayDeque<Long> queue = new ArrayDeque<Long>();
        for (BlockPos start : nodes) {
            long startKey = start.toLong();
            if (!nodeKeys.contains(startKey) || !visited.add(startKey)) continue;
            List<BlockPos> component = new ArrayList<BlockPos>();
            queue.clear();
            queue.addLast(startKey);
            while (!queue.isEmpty()) {
                long currentKey = queue.removeLast();
                BlockPos current = BlockPos.fromLong(currentKey);
                component.add(current);
                for (EnumFacing facing : FACINGS) {
                    long neighborKey = offsetKey(currentKey, facing);
                    if (nodeKeys.contains(neighborKey) && visited.add(neighborKey)) {
                        queue.addLast(neighborKey);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    private static long offsetKey(long key, EnumFacing facing) {
        BlockPos pos = BlockPos.fromLong(key);
        return new BlockPos(pos.getX() + facing.getFrontOffsetX(),
                pos.getY() + facing.getFrontOffsetY(),
                pos.getZ() + facing.getFrontOffsetZ()).toLong();
    }

    public static Set<Long> buildNodeKeySet(List<BlockPos> nodes) {
        Set<Long> keys = new HashSet<Long>();
        if (nodes != null) {
            for (BlockPos pos : nodes) keys.add(pos.toLong());
        }
        return keys;
    }

    public static void emitConnectedBoxShell(Map<BlockPos, AxisAlignedBB> boxes,
                                             LineSegmentBuffer buffer) {
        if (boxes == null || boxes.isEmpty() || buffer == null) return;
        Map<Long, double[]> activeEdges = new HashMap<Long, double[]>(boxes.size() * 12);
        Set<Long> parity = new HashSet<Long>();
        for (AxisAlignedBB box : boxes.values()) {
            if (box != null) toggleBoxEdges(parity, activeEdges, box);
        }
        for (double[] segment : activeEdges.values()) {
            buffer.addSegment(segment[0], segment[1], segment[2],
                    segment[3], segment[4], segment[5]);
        }
    }

    public static void emitPerBlockOutline(AxisAlignedBB box,
                                            LineSegmentBuffer buffer) {
        if (box == null || buffer == null) return;
        double x = box.minX, y = box.minY, z = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;
        buffer.addSegment(x, y, z, x2, y, z);
        buffer.addSegment(x, y, z, x, y, z2);
        buffer.addSegment(x2, y, z, x2, y, z2);
        buffer.addSegment(x, y, z2, x2, y, z2);
        buffer.addSegment(x, y2, z, x2, y2, z);
        buffer.addSegment(x, y2, z, x, y2, z2);
        buffer.addSegment(x2, y2, z, x2, y2, z2);
        buffer.addSegment(x, y2, z2, x2, y2, z2);
        buffer.addSegment(x, y, z, x, y2, z);
        buffer.addSegment(x2, y, z, x2, y2, z);
        buffer.addSegment(x, y, z2, x, y2, z2);
        buffer.addSegment(x2, y, z2, x2, y2, z2);
    }

    private static void toggleBoxEdges(Set<Long> parity, Map<Long, double[]> active,
                                       AxisAlignedBB box) {
        double x = box.minX, y = box.minY, z = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;
        toggleEdge(parity, active, x, y, z, x2, y, z);
        toggleEdge(parity, active, x, y, z, x, y, z2);
        toggleEdge(parity, active, x2, y, z, x2, y, z2);
        toggleEdge(parity, active, x, y, z2, x2, y, z2);
        toggleEdge(parity, active, x, y2, z, x2, y2, z);
        toggleEdge(parity, active, x, y2, z, x, y2, z2);
        toggleEdge(parity, active, x2, y2, z, x2, y2, z2);
        toggleEdge(parity, active, x, y2, z2, x2, y2, z2);
        toggleEdge(parity, active, x, y, z, x, y2, z);
        toggleEdge(parity, active, x2, y, z, x2, y2, z);
        toggleEdge(parity, active, x, y, z2, x, y2, z2);
        toggleEdge(parity, active, x2, y, z2, x2, y2, z2);
    }

    private static void toggleEdge(Set<Long> parity, Map<Long, double[]> active,
                                   double ax, double ay, double az,
                                   double bx, double by, double bz) {
        long key = edgeKey(ax, ay, az, bx, by, bz);
        if (parity.add(key)) {
            active.put(key, new double[] {ax, ay, az, bx, by, bz});
        } else {
            active.remove(key);
        }
    }

    private static long edgeKey(double ax, double ay, double az,
                                double bx, double by, double bz) {
        long first = packQuantized(quantize(ax), quantize(ay), quantize(az));
        long second = packQuantized(quantize(bx), quantize(by), quantize(bz));
        if (first > second) {
            long swap = first;
            first = second;
            second = swap;
        }
        return first ^ second * -7046029254386353131L;
    }

    private static long packQuantized(int x, int y, int z) {
        return (x & 0x1FFFFF) << 42L
                | (y & 0x1FFFFF) << 21L
                | (z & 0x1FFFFF);
    }

    private static int quantize(double coordinate) {
        return (int)Math.round(coordinate * 16.0D);
    }
}

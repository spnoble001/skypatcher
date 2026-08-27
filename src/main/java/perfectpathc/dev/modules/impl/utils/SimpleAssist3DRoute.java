package perfectpathc.dev.modules.impl.utils;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.BlockPos;

/**
 * Small, deterministic 3D route planner for SimpleAssist.
 *
 * The source block is an already-solid anchor and is therefore not included
 * in the returned list.  The goal is a PatchCrumbs plane: X/Y for a north/
 * south trajectory and Z/Y for an east/west trajectory; depth stays under
 * player control by retaining the anchor's depth coordinate.
 */
public final class SimpleAssist3DRoute {
    /** The 3D guide deliberately operates only in the crumb's local Y band. */
    public static final int VERTICAL_GUIDE_RADIUS = 1;
    /** Straight vertical patches may bridge a short three-block height gap. */
    public static final int PURE_VERTICAL_GUIDE_RADIUS = 3;
    private static final double STEP_COST = 1.0D;
    /** A concentrated L turn is visually/kinematically much more abrupt. */
    private static final double L_SHARP_TURN_PENALTY = 16.0D;

    private SimpleAssist3DRoute() {
    }

    public static long[] build(BlockPos anchor, BlockPos crumb,
                               boolean trajectoryNorthSouth, double eyeY) {
        if (anchor == null || crumb == null) return new long[0];
        if (!isWithinGuideBand(anchor, crumb, trajectoryNorthSouth)) {
            return new long[0];
        }
        int goalX = trajectoryNorthSouth ? crumb.getX() : anchor.getX();
        int goalZ = trajectoryNorthSouth ? anchor.getZ() : crumb.getZ();
        int goalY = crumb.getY();
        List<Long> staircase = new ArrayList<Long>();
        List<Long> lRoute = new ArrayList<Long>();
        buildStaircase(staircase, anchor.getX(), anchor.getY(), anchor.getZ(), goalX, goalY, goalZ);
        buildLRoute(lRoute, anchor.getX(), anchor.getY(), anchor.getZ(), goalX, goalY, goalZ);
        // Both candidates have equal Manhattan distance. The route weight
        // models camera comfort, so the S wins over the concentrated L turn.
        List<Long> cells = routeWeight(staircase, false) <= routeWeight(lRoute, true)
                ? staircase : lRoute;
        long[] packed = new long[cells.size()];
        for (int index = 0; index < cells.size(); index++) packed[index] = cells.get(index);
        return packed;
    }

    /** Mixed routes remain local; only an already aligned vertical column gets ±3. */
    public static boolean isWithinGuideBand(BlockPos anchor, BlockPos crumb,
                                            boolean trajectoryNorthSouth) {
        if (anchor == null || crumb == null) return false;
        int goalX = trajectoryNorthSouth ? crumb.getX() : anchor.getX();
        int goalZ = trajectoryNorthSouth ? anchor.getZ() : crumb.getZ();
        boolean pureVertical = anchor.getX() == goalX && anchor.getZ() == goalZ;
        int radius = pureVertical ? PURE_VERTICAL_GUIDE_RADIUS : VERTICAL_GUIDE_RADIUS;
        return anchor.getY() >= crumb.getY() - radius && anchor.getY() <= crumb.getY() + radius;
    }

    private static void buildStaircase(List<Long> cells, int startX, int startY, int startZ,
                                       int goalX, int goalY, int goalZ) {
        int x = startX;
        int y = startY;
        int z = startZ;
        // Alternating steps distribute the pitch/yaw correction rather than
        // concentrating it into one abrupt L-shaped transition.
        while (x != goalX || z != goalZ || y != goalY) {
            if (x != goalX) {
                x += step(x, goalX);
                add(cells, x, y, z);
            } else if (z != goalZ) {
                z += step(z, goalZ);
                add(cells, x, y, z);
            }
            if (y != goalY) {
                y += step(y, goalY);
                add(cells, x, y, z);
            }
        }
    }

    private static void buildLRoute(List<Long> cells, int startX, int startY, int startZ,
                                    int goalX, int goalY, int goalZ) {
        int x = startX;
        int y = startY;
        int z = startZ;
        while (x != goalX) {
            x += step(x, goalX);
            add(cells, x, y, z);
        }
        while (z != goalZ) {
            z += step(z, goalZ);
            add(cells, x, y, z);
        }
        while (y != goalY) {
            y += step(y, goalY);
            add(cells, x, y, z);
        }
    }

    private static double routeWeight(List<Long> route, boolean isLRoute) {
        return route.size() * STEP_COST + (isLRoute ? L_SHARP_TURN_PENALTY : 0.0D);
    }

    private static int step(int value, int target) {
        return value < target ? 1 : -1;
    }

    private static void add(List<Long> cells, int x, int y, int z) {
        cells.add(new BlockPos(x, y, z).toLong());
    }
}

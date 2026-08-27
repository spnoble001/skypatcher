package perfectpathc.dev.modules.impl.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.opengl.GL11;

/**
 * Read-only 3D route visualizer used exclusively by PatchCrumbs debug mode.
 * It intentionally does not validate supports or mutate SimpleAssist state.
 */
public final class TresDPrueba {
    private static final int GOAL_LINE_RADIUS = 12;
    /** Within this eye-height band, a stepped route has the lower preview weight. */
    private static final double STAIRCASE_HEIGHT_WEIGHT_BAND = 1.25D;

    private TresDPrueba() {
    }

    public static void render(Minecraft minecraft, BlockPos crumb,
                              boolean trajectoryNorthSouth) {
        if (minecraft == null || minecraft.theWorld == null || crumb == null) return;
        MovingObjectPosition hit = minecraft.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null
                || minecraft.theWorld.getBlockState(hit.getBlockPos()).getBlock() != Blocks.obsidian) {
            return;
        }

        BlockPos anchor = hit.getBlockPos();
        if (!SimpleAssist3DRoute.isWithinGuideBand(anchor, crumb, trajectoryNorthSouth)) {
            return;
        }
        double viewX = minecraft.getRenderManager().viewerPosX;
        double viewY = minecraft.getRenderManager().viewerPosY;
        double viewZ = minecraft.getRenderManager().viewerPosZ;
        double startX = anchor.getX() + 0.5D;
        double startY = anchor.getY() + 0.5D;
        double startZ = anchor.getZ() + 0.5D;
        // PatchCrumbs uses X as the horizontal progress axis for its N/S
        // trajectory and Z for E/W. The remaining horizontal axis is depth.
        double goalX = trajectoryNorthSouth ? crumb.getX() + 0.5D : startX;
        double goalZ = trajectoryNorthSouth ? startZ : crumb.getZ() + 0.5D;
        double goalY = crumb.getY() + 0.5D;
        double eyeHeightDelta = minecraft.thePlayer.posY
                + minecraft.thePlayer.getEyeHeight() - goalY;
        boolean preferStaircase = Math.abs(eyeHeightDelta) <= STAIRCASE_HEIGHT_WEIGHT_BAND;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glLineWidth(3.0F);

            if (preferStaircase) {
                drawStaircase(startX, startY, startZ, goalX, goalY, goalZ,
                        viewX, viewY, viewZ);
            } else {
                drawLRoute(startX, startY, startZ, goalX, goalY, goalZ,
                        viewX, viewY, viewZ);
            }
            // Green: the goal line; depth is deliberately unconstrained.
            GL11.glColor4f(0.2F, 1.0F, 0.35F, 0.85F);
            if (trajectoryNorthSouth) {
                drawLine(goalX - viewX, goalY - viewY, goalZ - GOAL_LINE_RADIUS - viewZ,
                        goalX - viewX, goalY - viewY, goalZ + GOAL_LINE_RADIUS - viewZ);
            } else {
                drawLine(goalX - GOAL_LINE_RADIUS - viewX, goalY - viewY, goalZ - viewZ,
                        goalX + GOAL_LINE_RADIUS - viewX, goalY - viewY, goalZ - viewZ);
            }
            drawMarker(goalX - viewX, goalY - viewY, goalZ - viewZ);
        } finally {
            GL11.glPopAttrib();
        }
    }

    private static void drawLine(double x1, double y1, double z1,
                                 double x2, double y2, double z2) {
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
        GL11.glEnd();
    }

    /** Renders the lower-weight alternating horizontal/vertical preview route. */
    private static void drawStaircase(double startX, double startY, double startZ,
                                      double goalX, double goalY, double goalZ,
                                      double viewX, double viewY, double viewZ) {
        int horizontalSteps = (int)Math.round(Math.abs(goalX - startX)
                + Math.abs(goalZ - startZ));
        int verticalSteps = (int)Math.round(Math.abs(goalY - startY));
        double horizontalStepX = horizontalSteps == 0 ? 0.0D
                : (goalX - startX) / horizontalSteps;
        double horizontalStepZ = horizontalSteps == 0 ? 0.0D
                : (goalZ - startZ) / horizontalSteps;
        double verticalStep = verticalSteps == 0 ? 0.0D
                : (goalY - startY) / verticalSteps;
        double x = startX;
        double y = startY;
        double z = startZ;
        int horizontalDone = 0;
        int verticalDone = 0;
        while (horizontalDone < horizontalSteps || verticalDone < verticalSteps) {
            if (horizontalDone < horizontalSteps) {
                double nextX = x + horizontalStepX;
                double nextZ = z + horizontalStepZ;
                GL11.glColor4f(0.0F, 0.8F, 1.0F, 1.0F);
                drawLine(x - viewX, y - viewY, z - viewZ,
                        nextX - viewX, y - viewY, nextZ - viewZ);
                x = nextX;
                z = nextZ;
                horizontalDone++;
            }
            if (verticalDone < verticalSteps) {
                double nextY = y + verticalStep;
                GL11.glColor4f(1.0F, 0.85F, 0.0F, 1.0F);
                drawLine(x - viewX, y - viewY, z - viewZ,
                        x - viewX, nextY - viewY, z - viewZ);
                y = nextY;
                verticalDone++;
            }
        }
    }

    /** Renders the fallback L route when the player is far from crumb height. */
    private static void drawLRoute(double startX, double startY, double startZ,
                                   double goalX, double goalY, double goalZ,
                                   double viewX, double viewY, double viewZ) {
        GL11.glColor4f(0.0F, 0.8F, 1.0F, 1.0F);
        drawLine(startX - viewX, startY - viewY, startZ - viewZ,
                goalX - viewX, startY - viewY, goalZ - viewZ);
        GL11.glColor4f(1.0F, 0.85F, 0.0F, 1.0F);
        drawLine(goalX - viewX, startY - viewY, goalZ - viewZ,
                goalX - viewX, goalY - viewY, goalZ - viewZ);
    }

    private static void drawMarker(double x, double y, double z) {
        double radius = 0.18D;
        drawLine(x - radius, y, z, x + radius, y, z);
        drawLine(x, y - radius, z, x, y + radius, z);
        drawLine(x, y, z - radius, x, y, z + radius);
    }
}

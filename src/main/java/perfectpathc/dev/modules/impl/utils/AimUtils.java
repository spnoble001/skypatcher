package perfectpathc.dev.modules.impl.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/** Rotation helpers adapted from Xenon for point targets. */
public final class AimUtils {
    private static final Minecraft MINECRAFT = Minecraft.getMinecraft();

    private AimUtils() {
    }

    public static float getAngleDifference(float first, float second) {
        return ((first - second) % 360.0F + 540.0F) % 360.0F - 180.0F;
    }

    public static XObject<float[]> getRotations(XObject<Vec3> wrappedVector) {
        if (wrappedVector == null || wrappedVector.get() == null
                || MINECRAFT.thePlayer == null) {
            return new XObject<float[]>(new float[] {0.0F, 0.0F});
        }
        Vec3 target = wrappedVector.get();
        Vec3 eyes = new Vec3(
                MINECRAFT.thePlayer.posX,
                MINECRAFT.thePlayer.getEntityBoundingBox().minY
                        + MINECRAFT.thePlayer.getEyeHeight(),
                MINECRAFT.thePlayer.posZ);
        double differenceX = target.xCoord - eyes.xCoord;
        double differenceY = target.yCoord - eyes.yCoord;
        double differenceZ = target.zCoord - eyes.zCoord;
        float yaw = MathHelper.wrapAngleTo180_float(
                (float)Math.toDegrees(Math.atan2(differenceZ, differenceX)) - 90.0F);
        float pitch = MathHelper.wrapAngleTo180_float(
                (float)-Math.toDegrees(Math.atan2(differenceY,
                        Math.sqrt(differenceX * differenceX
                                + differenceZ * differenceZ))));
        return new XObject<float[]>(new float[] {yaw, pitch});
    }
}

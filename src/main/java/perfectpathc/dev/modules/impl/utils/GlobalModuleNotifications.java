package perfectpathc.dev.modules.impl.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import perfectpathc.dev.modules.impl.Module;

/** Shared, opt-out chat feedback for deliberate user module toggles. */
public final class GlobalModuleNotifications {
    private static volatile boolean enabled = true;
    private static final String PREFIX = text(8, 0x5A2E5A3B5A0A5A01L, 0x5A7A5A075A325A39L);
    private static final String ON = text(4, 0x5A145A155A7A5A60L);
    private static final String OFF = text(5, 0x5A1C5A155A7A5A60L, 0x0000000000005A1CL);

    private GlobalModuleNotifications() { }

    public static void setEnabled(boolean value) { enabled = value; }

    public static void announce(Module module) {
        // Momentary actions such as ThrowPot self-disable after completing and
        // may be triggered many times per fight. They are not meaningful
        // persistent state changes and should not flood chat on every press.
        if (!enabled || module == null || !module.isPersistentToggle()) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.thePlayer == null) return;
        minecraft.thePlayer.addChatMessage(new ChatComponentText(PREFIX + module.getName()
                + (module.isEnabled() ? ON : OFF)));
    }

    private static String text(int length, long... blocks) {
        char[] output = new char[length];
        for (int index = 0; index < length; index++) {
            long packed = blocks[index >> 2];
            output[index] = (char)(((packed >>> ((index & 3) << 4)) & 0xFFFFL) ^ 0x5A5AL);
        }
        return new String(output);
    }
}

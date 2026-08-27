package perfectpathc.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import perfectpathc.dev.nativecore.NativeBridge;

/** Minimal input bridge; command tokens are matched by length and lowercase hash. */
public final class InputRouter {
    private static final int USER_MODULE_COUNT = 11;
    private static final String COMMAND_FLIP = text(5, 0x5A335A365A3C5A74L,
            0x0000000000005A2AL);
    private static final String COMMAND_PATCH = text(6, 0x5A2E5A3B5A2A5A74L,
            0x000000005A325A39L);
    private static final String PRESET_ONE = text(1, 0x0000000000005A6BL);
    private static final String PREPATCH_PREFIX = text(23,
            0x5A3F5A285A0A5A01L, 0x5A395A2E5A3B5A0AL,
            0x5A1E5A7A5A075A32L, 0x5A395A3F5A285A33L,
            0x5AE95A995A335A39L, 0x00005A7A5A605A34L);
    private static final String UNAVAILABLE = text(11, 0x5A2C5A3B5A345A2FL,
            0x5A3B5A365A335A3BL, 0x00005A3F5A365A38L);
    private static final String PRESET_LOADED = text(31,
            0x5A285A3F5A0A5A01L, 0x5A2E5A395A3F5A3CL,
            0x5A395A2E5A3B5A0AL, 0x5A0A5A7A5A075A32L,
            0x5A3F5A295A3F5A28L, 0x5A7A5A6B5A7A5A2EL,
            0x5A3D5A285A3B5A39L, 0x00005A355A3E5A3BL);
    private final PerfectPatch client;
    private final boolean[] moduleKeyDown = new boolean[USER_MODULE_COUNT];

    public InputRouter(PerfectPatch client) {
        this.client = client;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        dispatchClientTick(event);
        if (event.phase != TickEvent.Phase.END
                || Minecraft.getMinecraft().thePlayer == null) {
            return;
        }
        handleModuleKeybinds();
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        dispatchEnabled(3, 17, event);
    }

    /** Cancellable physical right-click hook before vanilla consumes use-item. */
    @SubscribeEvent
    public void onMouseInput(MouseEvent event) {
        if (event.button == 1 && event.buttonstate && this.client.isModuleEnabled(9)
                && bool(this.client.invokeModuleCommand(9, 21))) {
            event.setCanceled(true);
        }
    }

    /** Keyboard-bound use-item has no cancellable event; the module clears the
     *  binding before the next vanilla keybind processing pass. */
    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (this.client.isModuleEnabled(9)) this.client.invokeModuleCommand(9, 22);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (this.client.isModuleEnabled(7)) {
            this.client.invokeModuleCommand(7, 13, Float.valueOf(event.renderTickTime));
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent event) {
        if (event instanceof RenderGameOverlayEvent.Post
                && ((RenderGameOverlayEvent.Post) event).type
                == RenderGameOverlayEvent.ElementType.ALL
                && this.client.isModuleEnabled(7)) {
            this.client.invokeModuleCommand(7, 14, event);
        }
        if (this.client.isModuleEnabled(2)) {
            this.client.invokeModuleCommand(2, 14, event);
        }
        if (event instanceof RenderGameOverlayEvent.Post
                && ((RenderGameOverlayEvent.Post) event).type
                == RenderGameOverlayEvent.ElementType.ALL
                && this.client.isModuleEnabled(8)) {
            this.client.invokeModuleCommand(8, 14);
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        dispatchEnabled(0, 15, event);
        dispatchEnabled(2, 15, event);
        dispatchEnabled(1, 15, event);
        dispatchEnabled(10, 15, event);
        dispatchEnabled(6, 16);
        dispatchEnabled(9, 15, event);
    }

    private void dispatchClientTick(TickEvent.ClientTickEvent event) {
        dispatchEnabled(0, 11, event);
        dispatchEnabled(2, 11, event);
        dispatchEnabled(1, 11, event);
        dispatchEnabled(10, 11, event);
        if (event.phase == TickEvent.Phase.START) {
            dispatchEnabled(5, 12);
        } else {
            dispatchEnabled(4, 12);
            dispatchEnabled(8, 12);
        }
    }

    private void dispatchEnabled(int moduleIndex, int commandId, Object... arguments) {
        if (this.client.isModuleEnabled(moduleIndex)) {
            this.client.invokeModuleCommand(moduleIndex, commandId, arguments);
        }
    }

    /** Toggles every configurable embedded module once per keyboard/mouse press. */
    private void handleModuleKeybinds() {
        boolean canToggle = Minecraft.getMinecraft().currentScreen == null;
        for (int index = 0; index < USER_MODULE_COUNT; index++) {
            int key = this.client.getModuleKeybinding(index);
            boolean down = key > 0 ? Keyboard.isKeyDown(key)
                    : key < 0 && -key < Mouse.getButtonCount() && Mouse.isButtonDown(-key);
            if (down && !this.moduleKeyDown[index] && canToggle) {
                this.client.toggleModule(index);
            }
            this.moduleKeyDown[index] = down;
        }
    }

    @SubscribeEvent
    public void onChatKeyboard(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!(event.gui instanceof GuiChat) || !Keyboard.getEventKeyState()
                || (Keyboard.getEventKey() != Keyboard.KEY_RETURN
                && Keyboard.getEventKey() != Keyboard.KEY_NUMPADENTER)) {
            return;
        }
        GuiTextField input = ReflectionHelper.getPrivateValue(GuiChat.class, (GuiChat) event.gui,
                text(10, 0x5a2f5a2a5a345a33L, 0x5a3f5a335a1c5a2eL, 0x000000005a3e5a36L),
                text(14, 0x5a365a3f5a335a3cL, 0x5a6e5a6b5a055a3eL,
                        0x5a6f5a6b5a6e5a6cL, 0x000000005a3b5a05L));
        if (input == null) return;
        String command = input.getText();
        int firstStart = skipWhitespace(command, 0);
        int firstEnd = tokenEnd(command, firstStart);
        int secondStart = skipWhitespace(command, firstEnd);
        int secondEnd = tokenEnd(command, secondStart);
        int thirdStart = skipWhitespace(command, secondEnd);
        if (matchesToken(command, firstStart, firstEnd, COMMAND_FLIP)
                && secondStart == command.length()) {
            event.setCanceled(true);
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.displayGuiScreen(null);
            Object direction = this.client.invokeModuleCommand(2, 19);
            if (minecraft.thePlayer != null) {
                minecraft.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                        PREPATCH_PREFIX + (direction instanceof String
                                ? direction : UNAVAILABLE)));
            }
            return;
        }
        if (matchesToken(command, firstStart, firstEnd, COMMAND_PATCH)
                && matchesToken(command, secondStart, secondEnd, PRESET_ONE)
                && thirdStart == command.length()) {
            event.setCanceled(true);
            Minecraft minecraft = Minecraft.getMinecraft();
            minecraft.displayGuiScreen(null);
            this.client.invokeModuleCommand(1, 19);
            this.client.invokeModuleCommand(2, 20);
            if (minecraft.thePlayer != null) {
                minecraft.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                        PRESET_LOADED));
            }
            return;
        }
        int firstHash = tokenHash(command, firstStart, firstEnd);
        int firstLength = firstEnd - firstStart;
        int secondHash = tokenHash(command, secondStart, secondEnd);
        int secondLength = secondEnd - secondStart;
        boolean trailing = thirdStart != command.length();
        boolean patch = !trailing && firstLength == 6 && firstHash == 1423379674;
        boolean combatGui = !trailing && firstLength == 3 && firstHash == 47843
                && (secondLength == 0 || secondLength == 8 && secondHash == 570403011);
        boolean unload = !trailing && firstLength == 9 && firstHash == -520810230;
        int action = patch ? 1 : combatGui ? 2 : unload ? 8
                : (int) NativeBridge.invoke(224, new long[] {
                        firstHash, firstLength, secondHash, secondLength,
                        trailing ? 1L : 0L
                });
        boolean handled = unload || patch || combatGui || ((action & 4) != 0
                && (bool(this.client.invokeModuleCommand(4, 4, command))
                || bool(this.client.invokeModuleCommand(5, 4, command))
                || bool(this.client.invokeModuleCommand(6, 4, command))));
        if (!handled) return;
        event.setCanceled(true);
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.displayGuiScreen(null);
        if (unload) {
            this.client.quiesceNativeCore();
        } else if (patch) {
            this.client.openClickGui(false);
        } else if (combatGui) {
            this.client.openClickGui(true);
        }
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static boolean matchesToken(String value, int start, int end, String expected) {
        return end - start == expected.length()
                && value.regionMatches(true, start, expected, 0, expected.length());
    }

    private static int tokenEnd(String value, int index) {
        while (index < value.length() && !Character.isWhitespace(value.charAt(index))) index++;
        return index;
    }

    private static int tokenHash(String value, int start, int end) {
        int hash = 0;
        for (int index = start; index < end; index++) {
            hash = 31 * hash + Character.toLowerCase(value.charAt(index));
        }
        return hash;
    }

    private static String text(int length, long... values) {
        char[] output = new char[length];
        for (int index = 0; index < length; index++) {
            output[index] = (char) (((values[index >> 2] >>> ((index & 3) << 4)) & 0xFFFFL) ^ 0x5A5AL);
        }
        return new String(output);
    }
}

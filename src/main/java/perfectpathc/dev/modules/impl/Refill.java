package perfectpathc.dev.modules.impl;

import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

@Mod(keybind = 0)
public final class Refill extends Module {
    private static final String T0 = text(5, 0x5a3f5a3f5a2a5a09L, 0x0000000000005a3eL);

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        if (commandId == 4) return Boolean.valueOf(handleCommand((String) args[0]));
        if (commandId == 12) { onClientTick(); return null; }
        return super.invokeCommand(commandId, args);
    }
    private static final String T1 = text(3, 0x00005a2e5a355a0aL);
    private static final String T2 = text(8, 0x5a335a295a385a15L, 0x5a345a3b5a335a3eL);
    private static final String T3 = text(6, 0x5a3e5a345a3b5a08L, 0x000000005a375a35L);
    private static final String T4 = text(7, 0x5a385a235a3f5a11L, 0x00005a3e5a345a33L);
    private static final String T5 = text(6, 0x5a335a3c5a3f5a08L, 0x000000005a365a36L);
    private static final String T6 = text(43, 0x5a7a5a3b5a295a0fL, 0x5a2f5a3d5a285a74L,
            0x5a335a7a5a3b5a33L, 0x5a285a3f5a2e5a34L, 0x5a7a5a205a3b5a3cL,
            0x5a3b5a285a3b5a2aL, 0x5a345a355a395a7aL, 0x5a2f5a3d5a335a3cL,
            0x5a7a5a285a3b5a28L, 0x5a335a3c5a3f5a08L, 0x00005a745a365a36L);
    private static final String T7 = text(41, 0x5a7a5a3b5a295a0fL, 0x5a7a5a295a285a74L,
            0x5a285a745a7a5a35L, 0x5a3f5a365a365a3fL, 0x5a2a5a7a5a355a34L,
            0x5a7a5a3b5a285a3bL, 0x5a285a7a5a365a3fL, 0x5a365a335a3c5a3fL,
            0x5a355a345a7a5a36L, 0x5a365a3b5a375a28L, 0x0000000000005a74L);
    private static final String T8 = text(13, 0x5a7a5a3b5a295a0fL, 0x5a7a5a295a285a74L,
            0x5a685a6b5a775a6bL, 0x0000000000005a74L);
    private static final String T9 = text(37, 0x5a2c5a7a5a3b5a16L, 0x5a395a355a365a3fL,
            0x5a3e5a3b5a3e5a33L, 0x5a385a3f5a3e5a7aL, 0x5a295a3f5a7a5a3fL,
            0x5a7a5a285a3b5a2eL, 0x5a285a2e5a345a3fL, 0x5a7a5a6b5a7a5a3fL,
            0x5a685a6b5a7a5a23L, 0x0000000000005a74L);
    private static final String T10 = text(27, 0x5a335a3c5a3f5a08L, 0x5a3b5a7a5a365a36L,
            0x5a2c5a335a2e5a39L, 0x5a605a355a3e5a3bL, 0x5a365a3f5a2c5a7aL,
            0x5a3e5a335a395a35L, 0x00005a7a5a3e5a3bL);
    private static final String T11 = text(55, 0x5a7a5a3b5a295a0fL, 0x5a365a3f5a285a74L,
            0x5a355a345a3f5a36L, 0x5a2e5a355a2a5a7aL, 0x5a745a7a5a765a29L,
            0x5a365a365a3f5a28L, 0x5a7a5a355a345a3fL, 0x5a335a295a385a35L,
            0x5a345a3b5a335a3eL, 0x5a7a5a355a7a5a3bL, 0x5a365a3f5a285a74L,
            0x5a355a345a3f5a36L, 0x5a225a335a375a7aL, 0x00005a745a355a2eL);
    private static final String T12 = text(21, 0x5a335a3c5a3f5a08L, 0x5a3b5a7a5a365a36L,
            0x5a2c5a335a2e5a39L, 0x5a605a355a3e5a3bL, 0x5a2e5a355a2a5a7aL,
            0x0000000000005a29L);
    private static final String T13 = text(26, 0x5a335a3c5a3f5a08L, 0x5a3b5a7a5a365a36L,
            0x5a2c5a335a2e5a39L, 0x5a605a355a3e5a3bL, 0x5a295a385a355a7aL,
            0x5a3b5a335a3e5a33L, 0x000000005a3b5a34L);
    private static final String T14 = text(22, 0x5a335a3c5a3f5a08L, 0x5a3b5a7a5a365a36L,
            0x5a2c5a335a2e5a39L, 0x5a605a355a3e5a3bL, 0x5a225a335a375a7aL,
            0x000000005a355a2eL);
    private static final String T15 = text(15, 0x5a285a3f5a0a5a01L, 0x5a2e5a395a3f5a3cL,
            0x5a395a2e5a3b5a0aL, 0x00005a7a5a075a32L);

    public Refill() {
        this.sl.add(new Settings(T0.toCharArray(), this, 4.0F, 1.0F, 12.0F, 0.5F));
        this.sl.add(new Settings(T1.toCharArray(), this, true));
        this.sl.add(new Settings(T2.toCharArray(), this, false));
        this.sl.add(new Settings(T3.toCharArray(), this, false));
        // Equal slots mean that only one hotbar slot is reserved.  This keeps
        // both controls in the same 1..9 range while allowing one or two
        // protected obsidian positions.
        this.sl.add(new Settings(new char[] { 'S', 'l', 'o', 't', ' ', '1' }, this,
                1.0F, 1.0F, 9.0F, 1.0F));
        this.sl.add(new Settings(new char[] { 'S', 'l', 'o', 't', ' ', '2' }, this,
                1.0F, 1.0F, 9.0F, 1.0F));
        this.sl.add(new Settings(T4.toCharArray(), this));
        registerSettings();
        this.name = T5;
    }

    @Override
    public char getSort() {
        return 'r';
    }

    @Override
    public void onDisable() {
        if (NativeBridge.isNativeAvailable()) NativeBridge.setRefillDelay(0);
    }

    public void onClientTick() {
        if (!this.enabled || this.minecraft.theWorld == null || this.minecraft.thePlayer == null) return;
        if (!(this.minecraft.currentScreen instanceof GuiInventory)) return;
        boolean refillPotions = setting(1).getValBoolean();
        boolean refillObsidian = setting(2).getValBoolean();
        if (!refillPotions && !refillObsidian) return;
        if (!NativeBridge.advanceRefillDelay()) return;
        if (refillObsidian) {
            if (refillObsidianSlots()) return;
        }
        if (refillPotions) refillAvailablePotionSlot(refillObsidian);
    }

    public boolean handleCommand(String command) {
        if (command == null) return false;
        int commandStart = skipWhitespace(command, 0);
        int commandEnd = tokenEnd(command, commandStart);
        int commandLength = commandEnd - commandStart;
        int commandHash = tokenHash(command, commandStart, commandEnd);
        boolean shortCommand = commandLength == 3 && commandHash == 47855;
        boolean modeCommand = commandLength == 8 && commandHash == -344296155;
        boolean guideCommand = commandLength == 7 && commandHash == 1235638244;
        if (!shortCommand && !modeCommand && !guideCommand) return false;

        int argumentStart = skipWhitespace(command, commandEnd);
        if (guideCommand) {
            announce(argumentStart == command.length() ? T6 : T7);
            return true;
        }

        int argumentEnd = tokenEnd(command, argumentStart);
        if (argumentStart == command.length()
                || skipWhitespace(command, argumentEnd) != command.length()) {
            announce(shortCommand ? T8 : T11);
            return true;
        }

        if (shortCommand) {
            float speed = parseFloat(command, argumentStart, argumentEnd);
            if (speed != speed) {
                announce(T8);
            } else if (speed < 1.0F || speed > 12.0F) {
                announce(T9);
            } else {
                setting(0).setValfloat(speed);
                setEnabled(true);
                announce(T10 + setting(0).getValfloat());
            }
            return true;
        }

        int argumentLength = argumentEnd - argumentStart;
        int argumentHash = tokenHash(command, argumentStart, argumentEnd);
        if (argumentLength == 4 && argumentHash == 3446974) {
            setting(1).setValBoolean(true);
            setting(2).setValBoolean(false);
            announceMode(T12);
        } else if (argumentLength == 9 && argumentHash == -1976882842) {
            setting(1).setValBoolean(false);
            setting(2).setValBoolean(true);
            announceMode(T13);
        } else if (argumentLength == 5 && argumentHash == 103910871) {
            setting(1).setValBoolean(true);
            setting(2).setValBoolean(true);
            announceMode(T14);
        } else {
            announce(T11);
        }
        return true;
    }

    private void announceMode(String message) {
        setEnabled(true);
        announce(message);
    }

    /** @return true when an obsidian swap was issued and consumed this tick. */
    private boolean refillObsidianSlots() {
        ItemStack[] inventory = this.minecraft.thePlayer.inventory.mainInventory;
        int candidateMask = 0;
        int obsidianHotbarMask = 0;
        for (int slot = 9; slot < inventory.length && slot < 36; slot++) {
            ItemStack stack = inventory[slot];
            if (isObsidian(stack)) candidateMask |= 1 << (slot - 9);
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isObsidian(inventory[slot])) obsidianHotbarMask |= 1 << slot;
        }
        int firstSlot = (int) setting(4).getValfloat() - 1;
        int secondSlot = (int) setting(5).getValfloat() - 1;
        int targetMask = (1 << firstSlot) | (1 << secondSlot);
        try {
            long action = NativeBridge.selectObsidianRefillAction(candidateMask, targetMask,
                    obsidianHotbarMask, setting(0).getValfloat());
            if (action < 0) return false;
            swapIntoHotbar((int) ((action >>> 8) & 0xFFL), (int) (action & 0xFFL),
                    (int) ((action >>> 16) & 0xFFL));
            return true;
        } catch (IllegalStateException exception) {
            setEnabled(false);
            return false;
        }
    }

    /** Preserves the original path with Obsidian off. When it is enabled,
     *  Rust directs the potion only to a free non-reserved hotbar slot. */
    private void refillAvailablePotionSlot(boolean obsidianReservationsActive) {
        ItemStack[] inventory = this.minecraft.thePlayer.inventory.mainInventory;
        int candidateMask = 0;
        for (int slot = 9; slot < inventory.length && slot < 36; slot++) {
            ItemStack stack = inventory[slot];
            if (isPotion(stack)) candidateMask |= 1 << (slot - 9);
        }
        if (candidateMask == 0) return;
        try {
            if (obsidianReservationsActive) {
                int emptyHotbarMask = 0;
                for (int slot = 0; slot < 9; slot++) {
                    if (inventory[slot] == null) emptyHotbarMask |= 1 << slot;
                }
                int reservationMask = (1 << ((int) setting(4).getValfloat() - 1))
                        | (1 << ((int) setting(5).getValfloat() - 1));
                long action = NativeBridge.selectPotionRefillAction(candidateMask,
                        emptyHotbarMask & ~reservationMask, setting(0).getValfloat(),
                        setting(3).getValBoolean());
                if (action < 0) return;
                swapIntoHotbar((int) ((action >>> 8) & 0xFFL), (int) (action & 0xFFL),
                        (int) ((action >>> 16) & 0xFFL));
                return;
            }
            if (isHotbarFull()) return;
            long action = NativeBridge.selectRefillAction(candidateMask, setting(0).getValfloat(),
                    setting(3).getValBoolean());
            if (action < 0) return;
            movePotionToHotbar((int) (action & 0xFFL), (int) ((action >>> 8) & 0xFFL));
        } catch (IllegalStateException exception) {
            setEnabled(false);
        }
    }

    private boolean isObsidian(ItemStack stack) {
        return stack != null && (CombatMinecraftAccess.classifyRefillItem(stack) & 2) != 0;
    }

    private boolean isPotion(ItemStack stack) {
        return stack != null && (CombatMinecraftAccess.classifyRefillItem(stack) & 1) != 0;
    }

    private boolean isHotbarFull() {
        for (int slot = 0; slot < 9; slot++) {
            if (this.minecraft.thePlayer.inventory.mainInventory[slot] == null) return false;
        }
        return true;
    }

    /** Click mode 2 swaps the selected inventory slot with this exact hotbar
     *  index, including when the protected slot currently contains another
     *  item.  Shift-click cannot provide that guarantee. */
    private void swapIntoHotbar(int sourceSlot, int hotbarSlot, int delay) {
        this.minecraft.playerController.windowClick(
                this.minecraft.thePlayer.inventoryContainer.windowId,
                sourceSlot, hotbarSlot, 2, (EntityPlayer) this.minecraft.thePlayer);
        NativeBridge.setRefillDelay(delay);
    }

    private void movePotionToHotbar(int sourceSlot, int delay) {
        this.minecraft.playerController.windowClick(
                this.minecraft.thePlayer.inventoryContainer.windowId,
                sourceSlot, 0, 1, (EntityPlayer) this.minecraft.thePlayer);
        NativeBridge.setRefillDelay(delay);
    }


    private static float parseFloat(String value, int start, int end) {
        if (start == end) return 0.0F / 0.0F;
        int index = start;
        boolean negative = false;
        char first = value.charAt(index);
        if (first == '-' || first == '+') {
            negative = first == '-';
            if (++index == end) return 0.0F / 0.0F;
        }
        double result = 0.0D;
        int digits = 0;
        while (index < end) {
            int digit = value.charAt(index) - '0';
            if (digit < 0 || digit > 9) break;
            result = result * 10.0D + digit;
            digits++;
            index++;
        }
        if (index < end && value.charAt(index) == '.') {
            double scale = 0.1D;
            index++;
            while (index < end) {
                int digit = value.charAt(index) - '0';
                if (digit < 0 || digit > 9) break;
                result += digit * scale;
                scale *= 0.1D;
                digits++;
                index++;
            }
        }
        if (digits == 0 || index != end) return 0.0F / 0.0F;
        return (float) (negative ? -result : result);
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return index;
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

    private Settings setting(int index) {
        return this.sl.get(index);
    }

    private void announce(String message) {
        if (this.minecraft.thePlayer != null) {
            this.minecraft.thePlayer.addChatMessage(
                    new net.minecraft.util.ChatComponentText(T15 + message));
        }
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

package perfectpathc.dev.modules.impl;

import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

@Mod(keybind = 0)
public final class Refill extends Module {
    private static final String T0 = text(5, 0x5a3f5a3f5a2a5a09L, 0x0000000000005a3eL);

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        if (commandId == 12) { onClientTick(); return null; }
        if (commandId == 24) { applyZeroPreset(); return null; }
        return super.invokeCommand(commandId, args);
    }
    private static final String T1 = text(3, 0x00005a2e5a355a0aL);
    private static final String T2 = text(8, 0x5a335a295a385a15L, 0x5a345a3b5a335a3eL);
    private static final String T3 = text(6, 0x5a3e5a345a3b5a08L, 0x000000005a375a35L);
    private static final String T4 = text(7, 0x5a385a235a3f5a11L, 0x00005a3e5a345a33L);

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
    }

    /**
     * The UI label is intentionally materialized only while the GUI draws it;
     * retaining it as module state makes an otherwise cosmetic value permanent
     * in heap snapshots.
     */
    @Override
    public String getName() {
        return new String(new char[] { 'R', 'e', 'f', 'i', 'l', 'l' });
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


    private Settings setting(int index) {
        return this.sl.get(index);
    }

    private void applyZeroPreset() {
        setting(0).setValfloat(5.0F);
        setting(1).setValBoolean(true);
        setting(2).setValBoolean(true);
        setting(3).setValBoolean(false);
        setting(4).setValfloat(2.0F);
        setting(5).setValfloat(2.0F);
        setKeybinding(0);
        if (NativeBridge.isNativeAvailable()) NativeBridge.setRefillDelay(0);
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

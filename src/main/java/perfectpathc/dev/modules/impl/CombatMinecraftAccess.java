package perfectpathc.dev.modules.impl;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import sun.misc.Unsafe;

/**
 * Cached primitive accessors for mapped Minecraft fields. Field names are
 * compared from XOR-packed data only during class initialization; tick paths
 * use direct Unsafe reads/writes and contain no mapping or reflection lookup.
 */
final class CombatMinecraftAccess {
    private static final Unsafe UNSAFE;
    private static final long CURRENT_SLOT_OFFSET;
    private static final long RIGHT_CLICK_DELAY_OFFSET;
    private static final long PRESS_TIME_OFFSET;

    static {
        try {
            Field unsafeField = findField(Unsafe.class, 9,
                    0x5a0f5a3f5a325a2eL, 0x5a3c5a3b5a295a34L,
                    0x0000000000005a3fL);
            unsafeField.setAccessible(true);
            UNSAFE = (Unsafe) unsafeField.get(null);
            CURRENT_SLOT_OFFSET = UNSAFE.objectFieldOffset(findField(InventoryPlayer.class, 11,
                    0x5a285a285a2f5a39L, 0x5a135a2e5a345a3fL,
                    0x00005a375a3f5a2eL, 13,
                    0x5a365a3f5a335a3cL, 0x5a6a5a6d5a055a3eL,
                    0x5a055a6b5a6c5a6eL, 0x0000000000005a39L));
            RIGHT_CLICK_DELAY_OFFSET = UNSAFE.objectFieldOffset(findField(Minecraft.class, 20,
                    0x5a325a3d5a335a28L, 0x5a335a365a195a2eL,
                    0x5a3f5a1e5a315a39L, 0x5a0e5a235a3b5a36L,
                    0x5a285a3f5a375a33L, 14,
                    0x5a365a3f5a335a3cL, 0x5a6b5a6d5a055a3eL,
                    0x5a055a6d5a6c5a6eL, 0x000000005a395a3bL));
            PRESS_TIME_OFFSET = UNSAFE.objectFieldOffset(findField(KeyBinding.class, 9,
                    0x5a295a3f5a285a2aL, 0x5a375a335a0e5a29L,
                    0x0000000000005a3fL, 14,
                    0x5a365a3f5a335a3cL, 0x5a6f5a6b5a055a3eL,
                    0x5a6e5a6d5a6e5a6bL, 0x000000005a335a05L));
        } catch (Throwable throwable) {
            throw new ExceptionInInitializerError(throwable);
        }
    }

    private CombatMinecraftAccess() {
    }

    static int getCurrentSlot(InventoryPlayer inventory) {
        return UNSAFE.getInt(inventory, CURRENT_SLOT_OFFSET);
    }

    static void setCurrentSlot(InventoryPlayer inventory, int slot) {
        UNSAFE.putInt(inventory, CURRENT_SLOT_OFFSET, slot);
    }

    static void setRightClickDelay(Minecraft minecraft, int ticks) {
        UNSAFE.putInt(minecraft, RIGHT_CLICK_DELAY_OFFSET, ticks);
    }

    static void clearPressTime(KeyBinding keyBinding) {
        UNSAFE.putInt(keyBinding, PRESS_TIME_OFFSET, 0);
    }

    static int classifyHeldItem(Object value) {
        if (!(value instanceof Item)) return 0;
        int flags = NativeBridge.classifyItemPacked(Item.getIdFromItem((Item) value), 0, 0);
        return (flags >>> 2) & 3;
    }

    static int classifyRefillItem(Object value) {
        if (!(value instanceof ItemStack)) return 0;
        ItemStack stack = (ItemStack) value;
        Item item = stack.getItem();
        if (item == null) return 0;
        return NativeBridge.classifyItemPacked(Item.getIdFromItem(item),
                stack.getMetadata(), stack.stackSize);
    }

    static void sendHeldItemChange(Object value, int slot) {
        if (!(value instanceof EntityPlayerSP)) return;
        EntityPlayerSP player = (EntityPlayerSP) value;
        if (player.sendQueue != null) {
            player.sendQueue.addToSendQueue(new C09PacketHeldItemChange(slot));
        }
    }

    static void dispatchUseItem(Object value) {
        if (!(value instanceof Minecraft)) return;
        KeyBinding useKey = ((Minecraft) value).gameSettings.keyBindUseItem;
        useKey.onTick(useKey.getKeyCode());
    }

    static void dispatchInput(int button) {
        if (button == 0) ClickerUtils.sendFakeLeft();
        else if (button == 1) ClickerUtils.sendFakeRight();
    }

    private static Field findField(Class<?> owner, int firstLength, long first0,
                                   long first1, long first2, int secondLength,
                                   long second0, long second1, long second2, long second3) {
        Field[] fields = owner.getDeclaredFields();
        for (int index = 0; index < fields.length; index++) {
            Field field = fields[index];
            if (matches(field.getName(), firstLength, first0, first1, first2, 0L, 0L)
                    || matches(field.getName(), secondLength, second0, second1, second2, second3, 0L)) {
                return field;
            }
        }
        throw new IllegalStateException(owner.getName());
    }


    private static Field findField(Class<?> owner, int firstLength, long first0,
                                   long first1, long first2, long first3, int secondLength,
                                   long second0, long second1, long second2, long second3) {
        Field[] fields = owner.getDeclaredFields();
        for (int index = 0; index < fields.length; index++) {
            Field field = fields[index];
            if (matches(field.getName(), firstLength, first0, first1, first2, first3, 0L)
                    || matches(field.getName(), secondLength, second0, second1, second2, second3, 0L)) {
                return field;
            }
        }
        throw new IllegalStateException(owner.getName());
    }

    private static Field findField(Class<?> owner, int firstLength, long first0,
                                   long first1, long first2, long first3, long first4,
                                   int secondLength, long second0, long second1,
                                   long second2, long second3) {
        Field[] fields = owner.getDeclaredFields();
        for (int index = 0; index < fields.length; index++) {
            Field field = fields[index];
            if (matches(field.getName(), firstLength, first0, first1, first2, first3, first4)
                    || matches(field.getName(), secondLength, second0, second1, second2, second3, 0L)) {
                return field;
            }
        }
        throw new IllegalStateException(owner.getName());
    }

    private static Field findField(Class<?> owner, int length,
                                   long first, long second, long third) {
        Field[] fields = owner.getDeclaredFields();
        for (int index = 0; index < fields.length; index++) {
            Field field = fields[index];
            if (matches(field.getName(), length, first, second, third, 0L, 0L)) return field;
        }
        throw new IllegalStateException(owner.getName());
    }

    private static boolean matches(String value, int length,
                                   long first, long second, long third, long fourth, long fifth) {
        if (value.length() != length) return false;
        for (int index = 0; index < length; index++) {
            long packed = index < 4 ? first : index < 8 ? second
                    : index < 12 ? third : index < 16 ? fourth : fifth;
            if (value.charAt(index) != (char) (((packed >>> ((index & 3) << 4)) & 0xFFFFL) ^ 0x5A5AL)) {
                return false;
            }
        }
        return true;
    }
}

package perfectpathc.dev.modules.impl;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import org.lwjgl.input.Keyboard;
import perfectpathc.dev.modules.impl.utils.PerlinNoise;
import perfectpathc.dev.modules.impl.utils.RightClickCoordinator;

/**
 * One-shot splash-potion thrower. Timing runs on a daemon scheduler while all
 * Minecraft interaction is marshalled back onto the client thread.
 */
@Mod(keybind = 0)
public final class ThrowPot extends Module implements RightClickCoordinator.Owner {
    private static final int INPUT_PRIORITY = 100;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "PerfectPatch-ThrowPot-Timer");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private volatile boolean throwing;
    private final PerlinNoise transitionNoise = new PerlinNoise(UUID.randomUUID().getMostSignificantBits());
    private long sequence;
    private static final String T0 = text(9, 0x5a355a285a325a0eL, 0x5a355a0a5a7a5a2dL, 0x0000000000005a2eL);

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        if (commandId == 4) return Boolean.valueOf(handleCommand((String) args[0]));
        if (commandId == 12) { onClientTick(); return null; }
        if (commandId == 24) { applyZeroPreset(); return null; }
        return super.invokeCommand(commandId, args);
    }
    private static final String T1 = text(5, 0x5a3f5a3f5a2a5a09L, 0x0000000000005a3eL);
    private static final String T2 = text(6, 0x5a355a2e5a3f5a1eL, 0x000000005a285a2fL);
    private static final String T3 = text(7, 0x5a385a235a3f5a11L, 0x00005a3e5a345a33L);
    private static final String T4 = text(6, 0x5a325a395a285a1bL, 0x000000005a345a35L);
    private static final String T5 = text(5, 0x5a2f5a385a3f5a1eL, 0x0000000000005a3dL);
    private static final String T6 = text(46, 0x5a355a285a325a0eL, 0x5a355a0a5a7a5a2dL,
            0x5a3f5a285a7a5a2eL, 0x5a3f5a335a2f5a2bL, 0x5a365a7a5a3f5a28L,
            0x5a335a365a7a5a3bL, 0x5a285a3f5a285a38L, 0x5a345a7a5a3b5ab7L,
            0x5a2c5a335a2e5a3bL, 0x5a3f5a3e5a7a5a3bL, 0x5a295a2f5a085a7aL,
            0x000000005a745a2eL);
    private static final String T7 = text(36, 0x5a295a7a5a355a14L, 0x5a2f5a2a5a7a5a3fL,
            0x5a335a7a5a355a3eL, 0x5a335a395a335a34L, 0x5a0e5a7a5a285a3bL,
            0x5a2d5a355a285a32L, 0x5a2e5a355a0a5a7aL, 0x5a2e5a3b5a345a7aL,
            0x5a745a355a2c5a33L);
    private static final String T8 = text(10, 0x5a355a285a325a0eL, 0x5a355a0a5a7a5a2dL,
            0x000000005a7a5a2eL);
    private static final String T9 = text(8, 0x5a335a2e5a395a3bL, 0x5a355a3e5a3b5a2cL);
    private static final String T10 = text(11, 0x5a3b5a295a3f5a3eL, 0x5a2c5a335a2e5a39L,
            0x00005a355a3e5a3bL);
    private static final String T11 = text(7, 0x5a355a2e5a3f5a1eL, 0x00005a7a5a285a2fL);
    private static final String T12 = text(7, 0x5a325a395a285a1bL, 0x00005a7a5a345a35L);
    private static final String T13 = text(6, 0x5a2f5a385a3f5a1eL, 0x000000005a7a5a3dL);
    private static final String T14 = text(57, 0x5a365a395a3f5a0eL, 0x5a355a345a7a5a3bL,
            0x5a365abb5a2c5a7aL, 0x5a745a3b5a3e5a33L, 0x5a3b5a295a0f5a7aL,
            0x5a3f5a285a745a7aL, 0x5a3f5a2e5a7a5a3cL, 0x5a7a5a3b5a365a39L,
            0x5a7a5a355a7a5a08L, 0x5a3c5a3f5a285a74L, 0x5a395a3f5a2e5a7aL,
            0x5a375a7a5a3b5a36L, 0x5a3f5a295a2f5a35L, 0x5a6f5a775a6b5a60L,
            0x0000000000005a74L);
    private static final String T15 = text(20, 0x5a365a395a3f5a0eL, 0x5a3f5a3e5a7a5a3bL,
            0x5a285a325a0e5a7aL, 0x5a0a5a7a5a2d5a35L, 0x5a7a5a605a2e5a35L);
    private static final String T16 = text(42, 0x5a2c5a7a5a3b5a16L, 0x5a395a355a365a3fL,
            0x5a3e5a3b5a3e5a33L, 0x5a385a3f5a3e5a7aL, 0x5a295a3f5a7a5a3fL,
            0x5a7a5a285a3b5a2eL, 0x5a285a2e5a345a3fL, 0x5a6a5a6f5a7a5a3fL,
            0x5a6b5a7a5a235a7aL, 0x5a375a7a5a6a5a6fL, 0x000000005a745a29L);
    private static final String T17 = text(24, 0x5a355a365a3f5a0cL, 0x5a3b5a3e5a335a39L,
            0x5a3f5a3e5a7a5a3eL, 0x5a285a325a0e5a7aL, 0x5a0a5a7a5a2d5a35L,
            0x5a7a5a605a2e5a35L);
    private static final String T18 = text(4, 0x5a745a295a375a7aL);
    private static final String T19 = text(85, 0x5a7a5a3b5a295a0fL, 0x5a3c5a3f5a285a74L,
            0x5a285a745a7a5a76L, 0x5a3e5a7a5a3c5a3fL, 0x5a2f5a355a2e5a3fL,
            0x5a745a7a5a765a28L, 0x5a7a5a3c5a3f5a28L, 0x5a325a395a285a3bL,
            0x5a7a5a765a345a35L, 0x5a3c5a3f5a285a74L, 0x5a385a3f5a3e5a7aL,
            0x5a7a5a765a3d5a2fL, 0x5a3c5a3f5a285a74L, 0x5a775a6a5a6f5a7aL,
            0x5a7a5a6a5a6f5a6bL, 0x5a285a745a7a5a35L, 0x5a2e5a7a5a3c5a3fL,
            0x5a3b5a365a395a3fL, 0x5a375a755a085a7aL, 0x5a3f5a295a2f5a35L,
            0x5a6f5a775a6b5a60L, 0x0000000000005a74L);
    private static final String T20 = text(15, 0x5a285a3f5a0a5a01L, 0x5a2e5a395a3f5a3cL,
            0x5a395a2e5a3b5a0aL, 0x00005a7a5a075a32L);

    public ThrowPot() {
        this.name = T0;
        setKeybinding(33);
        this.sl.add(new Settings(T1.toCharArray(), this, 250.0F, 100.0F, 250.0F, true));
        this.sl.add(new Settings(T2.toCharArray(), this, true));
        this.sl.add(new Settings("Keybind".toCharArray(), this));
        registerSettings();
    }

    @Override
    public void onEnable() {
        triggerThrow();
    }

    @Override
    public void onDisable() {
        this.throwing = false;
        RightClickCoordinator.release(this);
    }

    @Override
    public void toggle() {
        setEnabled(!isEnabled());
    }

    /** Kept as an event entry point; the original sequence is clock-driven, not tick-driven. */
    public void onClientTick() { }

    private void triggerThrow() {
        if (this.minecraft.theWorld == null || this.minecraft.thePlayer == null || this.throwing) {
            return;
        }
        int initialSlot = CombatMinecraftAccess.getCurrentSlot(this.minecraft.thePlayer.inventory);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = this.minecraft.thePlayer.inventory.mainInventory[slot];
            if (isSplashPotion(stack)) {
                throwItem(slot, initialSlot);
                return;
            }
        }
        setEnabled(false);
    }

    private void throwItem(final int potionSlot, final int initialSlot) {
        if (!RightClickCoordinator.acquire(this, INPUT_PRIORITY)) return;
        this.throwing = true;
        final long totalDelay = sampleLogNormalDelay(setting(0).getValfloat());
        int candidate = chooseDetourSlot(initialSlot, potionSlot);
        if (setting(1).getValBoolean() && candidate != -1 && shouldUseDetour()) {
            final long firstDelay = detourDelay(totalDelay);
            enqueueMainThread(new Runnable() {
                @Override public void run() {
                    selectSlotNow(candidate);
                    schedule(firstDelay, new Runnable() {
                        @Override public void run() {
                            enqueuePotionThrow(potionSlot, initialSlot, totalDelay - firstDelay);
                        }
                    });
                }
            });
        } else {
            enqueuePotionThrow(potionSlot, initialSlot, totalDelay);
        }
    }

    private void enqueuePotionThrow(final int potionSlot, final int initialSlot, final long restoreDelay) {
        enqueueMainThread(new Runnable() {
            @Override public void run() {
                if (!canContinueThrow()) { finishThrow(initialSlot); return; }
                selectSlotNow(potionSlot);
                throwPotionNow();
                schedule(restoreDelay, new Runnable() {
                    @Override public void run() {
                        enqueueMainThread(new Runnable() {
                            @Override public void run() { finishThrow(initialSlot); }
                        });
                    }
                });
            }
        });
    }

    private static boolean isSplashPotion(ItemStack stack) {
        return (CombatMinecraftAccess.classifyRefillItem(stack) & 1) != 0;
    }

    private void selectSlotNow(int slot) {
        if (this.minecraft.thePlayer != null) {
            CombatMinecraftAccess.setCurrentSlot(this.minecraft.thePlayer.inventory, slot);
            CombatMinecraftAccess.sendHeldItemChange(this.minecraft.thePlayer, slot);
        }
    }

    private void throwPotionNow() {
        CombatMinecraftAccess.setRightClickDelay(this.minecraft, 0);
        int useKey = this.minecraft.gameSettings.keyBindUseItem.getKeyCode();
        KeyBinding.setKeyBindState(useKey, true);
        KeyBinding.onTick(useKey);
        ClickerUtils.sendFakeRight();
        KeyBinding.setKeyBindState(useKey, false);
    }

    private void finishThrow(int initialSlot) {
        if (this.minecraft.thePlayer != null) selectSlotNow(initialSlot);
        this.throwing = false;
        RightClickCoordinator.release(this);
        if (isEnabled()) setEnabled(false);
    }

    private boolean canContinueThrow() {
        return this.throwing && RightClickCoordinator.isOwner(this)
                && this.minecraft.thePlayer != null && this.minecraft.theWorld != null;
    }

    private void enqueueMainThread(final Runnable action) {
        this.minecraft.addScheduledTask(new Runnable() {
            @Override public void run() {
                try {
                    action.run();
                } catch (RuntimeException exception) {
                    throwing = false;
                    RightClickCoordinator.release(ThrowPot.this);
                }
            }
        });
    }

    private static void schedule(long delay, Runnable action) {
        TIMER.schedule(action, Math.max(0L, delay), TimeUnit.MILLISECONDS);
    }

    private long sampleLogNormalDelay(float requestedCenter) {
        double center = Math.max(100.0D, Math.min(250.0D, requestedCenter));
        double sigma = 0.12D;
        double mu = Math.log(center) - sigma * sigma / 2.0D;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 16; attempt++) {
            long sample = Math.round(Math.exp(mu + sigma * random.nextGaussian()));
            if (sample >= 100L && sample <= 250L) return sample;
        }
        return Math.round(center);
    }

    private boolean shouldUseDetour() {
        double correlated = (this.transitionNoise.sample(this.sequence++ * 0.17D) + 1.0D) * 0.5D;
        return correlated > 0.52D;
    }

    private int chooseDetourSlot(int initialSlot, int potionSlot) {
        int start = (int) Math.floor(Math.abs(this.transitionNoise.sample(this.sequence * 0.23D)) * 9.0D) % 9;
        for (int offset = 0; offset < 9; offset++) {
            int candidate = (start + offset) % 9;
            if (candidate != initialSlot && candidate != potionSlot) return candidate;
        }
        return -1;
    }

    private long detourDelay(long totalDelay) {
        double noise = (this.transitionNoise.sample(this.sequence * 0.31D + 41.0D) + 1.0D) * 0.5D;
        double ratio = 0.12D + noise * 0.13D;
        return Math.max(20L, Math.min(totalDelay - 20L, Math.round(totalDelay * ratio)));
    }

    public boolean handleCommand(String command) {
        if (command == null) return false;
        int commandStart = skipWhitespace(command, 0);
        int commandEnd = tokenEnd(command, commandStart);
        if (commandEnd - commandStart != 4
                || tokenHash(command, commandStart, commandEnd) != 1483173) return false;

        int argumentStart = skipWhitespace(command, commandEnd);
        if (argumentStart == command.length()) {
            toggle();
            announce(T8 + (isEnabled() ? T9 : T10));
            return true;
        }

        int argumentEnd = tokenEnd(command, argumentStart);
        int argumentLength = argumentEnd - argumentStart;
        int argumentHash = tokenHash(command, argumentStart, argumentEnd);
        int valueStart = skipWhitespace(command, argumentEnd);

        if (valueStart == command.length()
                && (argumentLength == 2 && argumentHash == 3551
                || argumentLength == 7 && argumentHash == -1161797200)) {
            if (!isEnabled()) setEnabled(true);
            announce(T8 + T9);
            return true;
        }
        if (valueStart == command.length()
                && (argumentLength == 3 && argumentHash == 109935
                || argumentLength == 10 && argumentHash == 1352107358)) {
            if (isEnabled()) setEnabled(false);
            announce(T8 + T10);
            return true;
        }
        if (valueStart == command.length() && argumentLength == 6
                && argumentHash == -1335210407) {
            setting(1).setValBoolean(!setting(1).getValBoolean());
            announce(T11 + (setting(1).getValBoolean() ? T9 : T10));
            return true;
        }
        if (argumentLength == 5 && argumentHash == 110235911
                && valueStart < command.length()) {
            int valueEnd = tokenEnd(command, valueStart);
            if (skipWhitespace(command, valueEnd) != command.length()) {
                announce(T19);
                return true;
            }
            int key = parseKey(command, valueStart, valueEnd);
            if (key <= 0 && !isMouseBinding(key)) {
                announce(T14);
            } else {
                setKeybinding(key);
                announceKey(command, valueStart, valueEnd);
            }
            return true;
        }

        if (valueStart == command.length()) {
            float speed = parseFloat(command, argumentStart, argumentEnd);
            if (Float.isNaN(speed)) {
                announce(T19);
            } else if (speed < 100.0F || speed > 250.0F) {
                announce("La velocidad debe estar entre 100 y 250 ms.");
            } else {
                setting(0).setValfloat(speed);
                announce(T17 + setting(0).getValfloat() + T18);
            }
            return true;
        }

        announce(T19);
        return true;
    }

    private static int parseKey(String value, int start, int end) {
        int length = end - start;
        if (length > 6 && tokenHash(value, start, start + 6) == -1068279755) {
            int button = parsePositiveInt(value, start + 6, end);
            return button >= 1 && button < org.lwjgl.input.Mouse.getButtonCount() ? -button : 0;
        }
        int numeric = parsePositiveInt(value, start, end);
        if (numeric > 0) return numeric;
        for (int key = 1; key < 256; key++) {
            String name = Keyboard.getKeyName(key);
            if (name != null && name.length() == length
                    && name.regionMatches(true, 0, value, start, length)) return key;
        }
        return 0;
    }

    private static int parsePositiveInt(String value, int start, int end) {
        if (start == end) return 0;
        int result = 0;
        for (int index = start; index < end; index++) {
            int digit = value.charAt(index) - '0';
            if (digit < 0 || digit > 9 || result > (0x7FFFFFFF - digit) / 10) return 0;
            result = result * 10 + digit;
        }
        return result;
    }

    private static float parseFloat(String value, int start, int end) {
        if (start == end) return Float.NaN;
        int index = start;
        boolean negative = false;
        char first = value.charAt(index);
        if (first == '-' || first == '+') {
            negative = first == '-';
            if (++index == end) return Float.NaN;
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
        if (digits == 0) return Float.NaN;

        if (index < end && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
            index++;
            boolean negativeExponent = false;
            if (index < end && (value.charAt(index) == '-' || value.charAt(index) == '+')) {
                negativeExponent = value.charAt(index) == '-';
                index++;
            }
            int exponentStart = index;
            int exponent = 0;
            while (index < end) {
                int digit = value.charAt(index) - '0';
                if (digit < 0 || digit > 9) return Float.NaN;
                exponent = exponent > 100 ? 101 : exponent * 10 + digit;
                index++;
            }
            if (index == exponentStart) return Float.NaN;
            double factor = 1.0D;
            for (int power = 0; power < exponent && power <= 100; power++) factor *= 10.0D;
            result = negativeExponent ? result / factor : result * factor;
        }
        if (index != end) return Float.NaN;
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

    private Settings setting(int index) { return this.sl.get(index); }

    private void applyZeroPreset() {
        setting(0).setValfloat(250.0F);
        setting(1).setValBoolean(true);
        setKeybinding(0);
    }

    private void announceKey(String value, int start, int end) {
        StringBuilder message = new StringBuilder(T15.length() + end - start);
        message.append(T15);
        for (int index = start; index < end; index++) {
            message.append(Character.toUpperCase(value.charAt(index)));
        }
        announce(message.toString());
    }

    private void announce(String message) {
        if (this.minecraft.thePlayer != null) {
            this.minecraft.thePlayer.addChatMessage(
                    new net.minecraft.util.ChatComponentText(T20 + message));
        }
    }

    @Override
    public void onRightClickControlLost() {
        this.throwing = false;
    }

    public boolean isThrowing() { return this.throwing; }

    @Override
    public char getSort() { return 't'; }

    @Override
    public boolean isPersistentToggle() { return false; }

    private static String text(int length, long... blocks) {
        char[] output = new char[length];
        for (int index = 0; index < length; index++) {
            long packed = blocks[index >> 2];
            output[index] = (char) (((packed >>> ((index & 3) << 4)) & 0xFFFFL) ^ 0x5A5AL);
        }
        return new String(output);
    }
}

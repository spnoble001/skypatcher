package perfectpathc.dev.modules.impl;


public final class Settings {
    private final String name;
    private final Module owner;
    private final boolean booleanSetting;
    private final boolean modeSetting;
    private final boolean keybindSetting;
    private final boolean rangeSetting;
    private boolean labelSetting;
    private boolean booleanValue;
    private float numberValue;
    private float rangeMinimumValue;
    private float rangeMaximumValue;
    private final float minimum;
    private final float maximum;
    private final float step;
    private final String[] modes;
    private int modeIndex;

    public Settings(char[] name, Module owner, boolean value) {
        this.name = new String(name);
        this.owner = owner;
        this.booleanSetting = true;
        this.modeSetting = false;
        this.keybindSetting = false;
        this.rangeSetting = false;
        this.booleanValue = value;
        this.minimum = 0.0F;
        this.maximum = 1.0F;
        this.step = 0.0F;
        this.modes = new String[0];
    }

    public Settings(char[] name, Module owner, float value, float minimum, float maximum, boolean ignored) {
        this.name = new String(name);
        this.owner = owner;
        this.booleanSetting = false;
        this.modeSetting = false;
        this.keybindSetting = false;
        this.rangeSetting = false;
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = 0.0F;
        this.modes = new String[0];
        setValfloat(value);
    }

    public Settings(char[] name, Module owner, float value, float minimum, float maximum, float step) {
        this.name = new String(name);
        this.owner = owner;
        this.booleanSetting = false;
        this.modeSetting = false;
        this.keybindSetting = false;
        this.rangeSetting = false;
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = Math.max(0.0F, step);
        this.modes = new String[0];
        setValfloat(value);
    }

    public Settings(char[] name, Module owner, float lowerValue, float upperValue,
                    float minimum, float maximum, float step) {
        this.name = new String(name);
        this.owner = owner;
        this.booleanSetting = false;
        this.modeSetting = false;
        this.keybindSetting = false;
        this.rangeSetting = true;
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = Math.max(0.0F, step);
        this.modes = new String[0];
        this.rangeMinimumValue = quantize(lowerValue);
        this.rangeMaximumValue = quantize(upperValue);
        if (this.rangeMinimumValue > this.rangeMaximumValue) {
            float swap = this.rangeMinimumValue;
            this.rangeMinimumValue = this.rangeMaximumValue;
            this.rangeMaximumValue = swap;
        }
    }

    public Settings(char[] name, Module owner, String value, String... modes) {
        this.name = new String(name);
        this.owner = owner;
        this.booleanSetting = false;
        this.modeSetting = true;
        this.keybindSetting = false;
        this.rangeSetting = false;
        this.minimum = 0.0F;
        this.maximum = 0.0F;
        this.step = 0.0F;
        this.modes = modes == null ? new String[0] : modes.clone();
        setValString(value);
    }

    /** Keybind row; the owning module stores the selected LWJGL key code. */
    public Settings(char[] name, Module owner) {
        this.name = new String(name);
        this.owner = owner;
        this.booleanSetting = false;
        this.modeSetting = false;
        this.keybindSetting = true;
        this.rangeSetting = false;
        this.minimum = 0.0F;
        this.maximum = 0.0F;
        this.step = 0.0F;
        this.modes = new String[0];
    }

    /** Non-interactive GUI label used to separate groups of settings. */
    public static Settings label(char[] name, Module owner) {
        return new Settings(name, owner, (byte)1);
    }

    private Settings(char[] name, Module owner, byte labelMarker) {
        this.name = new String(name);
        this.owner = owner;
        this.booleanSetting = false;
        this.modeSetting = false;
        this.keybindSetting = false;
        this.rangeSetting = false;
        this.labelSetting = true;
        this.minimum = 0.0F;
        this.maximum = 0.0F;
        this.step = 0.0F;
        this.modes = new String[0];
    }

    public boolean getValBoolean() {
        return this.booleanValue;
    }

    public void setValBoolean(boolean value) {
        this.booleanValue = value;
    }

    public float getValfloat() {
        return this.numberValue;
    }

    public void setValfloat(float value) {
        this.numberValue = quantize(value);
    }

    public float getRangeMinimum() {
        return this.rangeMinimumValue;
    }

    public float getRangeMaximum() {
        return this.rangeMaximumValue;
    }

    public void setRangeMinimum(float value) {
        this.rangeMinimumValue = Math.min(quantize(value), this.rangeMaximumValue);
    }

    public void setRangeMaximum(float value) {
        this.rangeMaximumValue = Math.max(quantize(value), this.rangeMinimumValue);
    }

    /** Sets both range endpoints together, preserving their requested order. */
    public void setRange(float lowerValue, float upperValue) {
        if (!this.rangeSetting) {
            return;
        }
        float lower = quantize(lowerValue);
        float upper = quantize(upperValue);
        if (lower > upper) {
            float swap = lower;
            lower = upper;
            upper = swap;
        }
        this.rangeMinimumValue = lower;
        this.rangeMaximumValue = upper;
    }

    public String getValString() {
        return this.modes.length == 0 ? "" : this.modes[this.modeIndex];
    }

    public int getModeIndex() {
        return this.modeIndex;
    }

    public void setValString(String value) {
        this.modeIndex = 0;
        for (int i = 0; i < this.modes.length; i++) {
            if (this.modes[i].equalsIgnoreCase(value)) {
                this.modeIndex = i;
                return;
            }
        }
    }

    public void nextMode() {
        if (this.modes.length > 0) {
            this.modeIndex = (this.modeIndex + 1) % this.modes.length;
        }
    }

    public String getName() {
        return this.name;
    }

    public Module getOwner() {
        return this.owner;
    }

    public boolean isBooleanSetting() {
        return this.booleanSetting;
    }

    public boolean isModeSetting() {
        return this.modeSetting;
    }

    public boolean isNumberSetting() {
        return !this.booleanSetting && !this.modeSetting
                && !this.keybindSetting && !this.rangeSetting;
    }

    public boolean isRangeSetting() {
        return this.rangeSetting;
    }

    public boolean isKeybindSetting() {
        return this.keybindSetting;
    }

    public boolean isLabelSetting() {
        return this.labelSetting;
    }

    public float getMin() {
        return this.minimum;
    }

    public float getMax() {
        return this.maximum;
    }

    /** Number settings with unit-sized (or larger) steps are displayed as integers. */
    public boolean isIntegerSetting() {
        return this.isNumberSetting() && this.step >= 1.0F;
    }

    private float quantize(float value) {
        float clamped = Math.max(this.minimum, Math.min(this.maximum, value));
        if (this.step > 0.0F) {
            clamped = this.minimum
                    + Math.round((clamped - this.minimum) / this.step) * this.step;
        }
        return Math.max(this.minimum, Math.min(this.maximum, clamped));
    }
}

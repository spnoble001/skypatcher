package perfectpathc.dev.modules.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Mouse;
import perfectpathc.dev.modules.runtime.IModuleEntry;

public abstract class Module implements IModuleEntry {
    /** Negative keybinding values represent LWJGL mouse buttons. */
    public static final int MOUSE_BINDING_MIN = -16;
    public static final int MOUSE_BINDING_MAX = -1;
    protected final Minecraft minecraft = Minecraft.getMinecraft();
    protected final List<Settings> sl = new ArrayList<Settings>();
    protected final char[] characters = new char[36];
    protected boolean enabled;
    protected String name = getClass().getSimpleName();
    private int keybinding;
    private boolean disposed;

    public final void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (enabled) {
            onEnable();
        } else {
            onDisable();
        }
    }

    public void toggle() {
        setEnabled(!this.enabled);
    }

    public final boolean isEnabled() {
        return this.enabled;
    }

    public String getName() {
        return this.name;
    }

    public List<Settings> getSettings() {
        return Collections.unmodifiableList(this.sl);
    }

    public char[] getCharacters() {
        return this.characters.clone();
    }

    public int getKeybinding() {
        return this.keybinding;
    }

    public void setKeybinding(int keybinding) {
        this.keybinding = isMouseBinding(keybinding) || keybinding > 0 ? keybinding : 0;
    }

    public static boolean isMouseBinding(int keybinding) {
        int button = -keybinding;
        return keybinding < 0 && button >= 1 && button < Mouse.getButtonCount();
    }

    public static int mouseButtonFromBinding(int keybinding) {
        return -keybinding;
    }

    public boolean isPersistentToggle() {
        return true;
    }

    protected void registerSettings() {
        // Punto de extensión para un futuro gestor de configuración.
    }

    /** Called by the ClickGUI after a user changes one of this module's rows. */
    public void onSettingsChanged() {
    }

    @Override
    public void initialize() {
        // Module constructors establish their configuration. The host owns activation.
    }

    /**
     * Common embedded-runtime teardown. Modules are currently dispatched by
     * the root router, but unregistering here also makes stale direct Forge
     * registrations harmless during a dormant transition.
     */
    @Override
    public void dsg() {
        if (this.disposed) return;
        this.disposed = true;
        try {
            setEnabled(false);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(this);
            this.sl.clear();
            Arrays.fill(this.characters, (char) 0);
            this.keybinding = 0;
            this.name = "";
        }
    }

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        switch (commandId) {
            case 0: return Boolean.valueOf(isEnabled());
            case 1: return Integer.valueOf(getKeybinding());
            case 2: toggle(); return null;
            case 3:
                if (args != null && args.length == 1 && args[0] instanceof Boolean) {
                    setEnabled(((Boolean) args[0]).booleanValue());
                }
                return null;
            default: return null;
        }
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    public abstract char getSort();
}

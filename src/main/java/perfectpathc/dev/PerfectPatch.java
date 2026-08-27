package perfectpathc.dev;

import net.minecraftforge.common.MinecraftForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import perfectpathc.dev.nativecore.NativeBridge;
import perfectpathc.dev.modules.runtime.EmbeddedModuleClassLoader;
import perfectpathc.dev.modules.runtime.IModuleEntry;
import perfectpathc.dev.modules.runtime.ModuleLauncher;
import perfectpathc.dev.modules.runtime.ModuleRegistry;
import java.io.IOException;
import java.lang.reflect.Method;

@Mod(
        modid = PerfectPatch.MOD_ID,
        name = PerfectPatch.NAME,
        version = PerfectPatch.VERSION,
        clientSideOnly = true,
        acceptedMinecraftVersions = "[1.8.9]"
)
public final class PerfectPatch {
    public static final String MOD_ID = "Fps";
    public static final String NAME = "SkyPatcher";
    public static final String VERSION = "1.0.0";

    @Mod.Instance(MOD_ID)
    public static PerfectPatch instance;

    private final IModuleEntry[] modules = new IModuleEntry[11];
    private EmbeddedModuleClassLoader embeddedModuleLoader;
    private InputRouter inputRouter;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // The launcher may use an instance directory instead of $HOME/.minecraft.
        // Give the native activation bootstrap the authoritative Minecraft path
        // before embedded modules cause JNI to load.
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft != null && minecraft.mcDataDir != null) {
            System.setProperty("perfectpatch.game.dir", minecraft.mcDataDir.getAbsolutePath());
        }
        loadEmbeddedModules();
        if (this.embeddedModuleLoader == null) return;
        setModuleEnabled(this.modules[0], true);

        this.inputRouter = new InputRouter(this);
        MinecraftForge.EVENT_BUS.register(this.inputRouter);
        FMLCommonHandler.instance().bus().register(this.inputRouter);
    }

    public IModuleEntry getModule(int index) {
        return index >= 0 && index < this.modules.length ? this.modules[index] : null;
    }

    /** Debug/session kill switch: stop adapters first, then wipe the Rust core. */
    public boolean quiesceNativeCore() {
        unregisterInputRouter();
        for (int index = 0; index < this.modules.length; index++) {
            dispose(this.modules[index]);
            this.modules[index] = null;
        }
        if (this.embeddedModuleLoader != null) {
            ModuleRegistry.unload(this.embeddedModuleLoader);
            this.embeddedModuleLoader = null;
        }
        try {
            return NativeBridge.invoke(1, new long[0]) != 0L;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public Object invokeModuleCommand(int moduleIndex, int commandId, Object... args) {
        IModuleEntry module = getModule(moduleIndex);
        return module == null ? null : module.invokeCommand(commandId, args);
    }

    private void loadEmbeddedModules() {
        try {
            this.embeddedModuleLoader = ModuleRegistry.load(PerfectPatch.class,
                    "/assets/modules/core.bin");
            for (int index = 0; index < this.modules.length; index++) this.modules[index] = launchModule(className(index));
        } catch (IOException exception) {
            // The test module is optional during development, before core.bin exists.
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not initialize embedded module", exception);
        }
    }

    public boolean isModuleEnabled(int index) {
        return booleanValue(invokeModuleCommand(index, 0));
    }

    public int getModuleKeybinding(int index) {
        Object value = invokeModuleCommand(index, 1);
        return value instanceof Integer ? ((Integer) value).intValue() : 0;
    }

    public void toggleModule(int index) {
        IModuleEntry module = getModule(index);
        if (module == null) return;
        module.invokeCommand(2);
        // Module implementation classes live in the embedded loader. Route
        // the notification back through PatchCrumbs to avoid crossing that
        // loader boundary from the host class.
        invokeModuleCommand(0, 23, module);
    }

    public void openClickGui(boolean combat) {
        try {
            Class<?> guiClass = Class.forName(guiClassName(),
                    true, this.embeddedModuleLoader);
            Method factory = guiClass.getMethod(new String(combat
                    ? new char[] {99,111,109,98,97,116,71,117,105}
                    : new char[] {112,97,116,99,104,71,117,105}));
            Minecraft.getMinecraft().displayGuiScreen((GuiScreen) factory.invoke(null));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not open embedded click GUI", exception);
        }
    }

    private IModuleEntry launchModule(String className) throws ReflectiveOperationException {
        return ModuleLauncher.launch(this.embeddedModuleLoader, className);
    }

    private static void setModuleEnabled(IModuleEntry module, boolean enabled) {
        if (module != null) module.invokeCommand(3, Boolean.valueOf(enabled));
    }

    private void unregisterInputRouter() {
        if (this.inputRouter == null) return;
        MinecraftForge.EVENT_BUS.unregister(this.inputRouter);
        FMLCommonHandler.instance().bus().unregister(this.inputRouter);
        this.inputRouter = null;
    }

    private static void dispose(IModuleEntry module) {
        if (module == null) return;
        try {
            module.dsg();
        } catch (RuntimeException ignored) {
            // Continue releasing the remaining entries during the panic path.
        }
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static String className(int index) {
        char[][] suffixes = {
                {80,97,116,99,104,67,114,117,109,98,115}, {83,105,109,112,108,101,65,115,115,105,115,116},
                {80,114,101,80,97,116,99,104}, {65,110,116,105,70,97,108,108,77,111,100,117,108,101},
                {82,101,102,105,108,108}, {84,104,114,111,119,80,111,116}, {69,113,117,105,112,111},
                {65,105,109,65,115,115,105,115,116}, {76,101,102,116,67,108,105,99,107,101,114},
                {83,109,97,114,116,80,101,97,114,108,71,97,116,101,107,101,101,112,101,114},
                {83,105,109,112,108,101,65,115,115,105,115,116,51,68}
        };
        return new String(new char[] {112,101,114,102,101,99,116,112,97,116,104,99,46,100,101,118,46,109,111,100,117,108,101,115,46,105,109,112,108,46})
                + new String(suffixes[index]);
    }

    private static String guiClassName() {
        return new String(new char[] {112,101,114,102,101,99,116,112,97,116,104,99,46,100,101,118,46,109,111,100,117,108,101,115,46,105,109,112,108,46,117,105,46,67,108,105,99,107,71,117,105,79,98,106});
    }

}

package perfectpathc.dev;

import net.minecraftforge.common.MinecraftForge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import perfectpathc.dev.nativecore.NativeBridge;
import perfectpathc.dev.modules.runtime.EmbeddedModuleClassLoader;
import perfectpathc.dev.modules.runtime.IEmbeddedRuntime;
import perfectpathc.dev.modules.runtime.IModuleEntry;
import perfectpathc.dev.modules.runtime.ModuleLauncher;
import perfectpathc.dev.modules.runtime.ModuleRegistry;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

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
    private IEmbeddedRuntime embeddedRuntime;
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
        this.embeddedRuntime = null;
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
        byte[] encrypted = null;
        byte[] payload = null;
        try {
            encrypted = readPayloadResource();
            payload = NativeBridge.decryptPayload(encrypted);
            if (payload == null || payload.length == 0) return;
            this.embeddedModuleLoader = ModuleRegistry.loadFromBuffer(PerfectPatch.class, payload);
            this.embeddedRuntime = ModuleLauncher.launchRuntime(this.embeddedModuleLoader);
            for (int index = 0; index < this.modules.length; index++) {
                this.modules[index] = this.embeddedRuntime.createModule(index);
            }
        } catch (IOException exception) {
            // The test module is optional during development, before core.bin exists.
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not initialize embedded module", exception);
        } finally {
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (payload != null) Arrays.fill(payload, (byte) 0);
        }
    }

    /** Reads an inert encrypted blob; its format is understood only by JNI. */
    private static byte[] readPayloadResource() throws IOException {
        InputStream input = PerfectPatch.class.getResourceAsStream("/assets/sky/cache.dat");
        if (input == null) throw new IOException("Embedded runtime data is missing");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int read; (read = input.read(chunk)) >= 0;) output.write(chunk, 0, read);
            return output.toByteArray();
        } finally {
            input.close();
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
        if (this.embeddedRuntime == null) return;
        GuiScreen screen = this.embeddedRuntime.createGui(combat);
        Minecraft.getMinecraft().displayGuiScreen(screen);
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

}

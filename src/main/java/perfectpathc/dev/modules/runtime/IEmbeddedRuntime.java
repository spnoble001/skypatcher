package perfectpathc.dev.modules.runtime;

import net.minecraft.client.gui.GuiScreen;

/** Opaque payload entry point exposed to the fixed bootstrap. */
public interface IEmbeddedRuntime {
    /** Creates and initializes the module associated with a stable numeric ID. */
    IModuleEntry createModule(int moduleId);

    /** Creates one of the payload-owned GUI surfaces by numeric mode. */
    GuiScreen createGui(boolean combat);
}

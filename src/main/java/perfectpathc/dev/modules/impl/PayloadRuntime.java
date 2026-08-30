package perfectpathc.dev.modules.impl;

import net.minecraft.client.gui.GuiScreen;
import perfectpathc.dev.modules.impl.ui.ClickGuiObj;
import perfectpathc.dev.modules.runtime.IEmbeddedRuntime;
import perfectpathc.dev.modules.runtime.IModuleEntry;

/**
 * The sole payload entry point. Its module topology remains inside core.bin;
 * the fixed bootstrap deals exclusively in stable numeric IDs.
 */
public final class PayloadRuntime implements IEmbeddedRuntime {
    @Override
    public IModuleEntry createModule(int moduleId) {
        IModuleEntry entry;
        switch (moduleId) {
            case 0: entry = new PatchCrumbs(); break;
            case 1: entry = new SimpleAssist(); break;
            case 2: entry = new PrePatch(); break;
            case 3: entry = new OceanoV2(); break;
            case 4: entry = new Refill(); break;
            case 5: entry = new ThrowPot(); break;
            case 6: entry = new Equipo(); break;
            case 7: entry = new AimAssist(); break;
            case 8: entry = new LeftClicker(); break;
            case 9: entry = new SmartPearlGatekeeper(); break;
            case 10: entry = new SimpleAssist3D(); break;
            default: return null;
        }
        entry.initialize();
        return entry;
    }

    @Override
    public GuiScreen createGui(boolean combat) {
        return combat ? ClickGuiObj.combatGui() : ClickGuiObj.patchGui();
    }
}

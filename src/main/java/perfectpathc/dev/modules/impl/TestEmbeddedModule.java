package perfectpathc.dev.modules.impl;

import net.minecraftforge.fml.common.FMLLog;
import perfectpathc.dev.modules.runtime.IModuleEntry;

/** Minimal autonomous module used to validate the embedded-module pipeline. */
public final class TestEmbeddedModule implements IModuleEntry {
    @Override
    public void initialize() {
        FMLLog.info("PerfectPatch embedded module loaded successfully.");
    }

    @Override
    public void dsg() {
        // This verification entry owns no listeners or asynchronous resources.
    }

    @Override
    public Object invokeCommand(int commandId, Object... args) {
        return null;
    }
}

package perfectpathc.dev.modules.impl.events;

import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import perfectpathc.dev.modules.impl.Module;
import perfectpathc.dev.modules.impl.ModuleInvoker;

/**
 * Single Forge event entry point for the combat2 runtime.
 *
 * <p>The bridge owns fixed references and performs direct dispatch. It does not
 * allocate collections, iterators, lambdas, or wrapper objects in event paths.</p>
 */
public final class CombatEventBridge {
    private final Module refill;
    private final Module throwPot;
    private final Module aimAssist;
    private final Module leftClicker;
    private final Module equipo;

    public CombatEventBridge(Module refill, Module throwPot, Module aimAssist,
                             Module leftClicker, Module equipo) {
        this.refill = refill;
        this.throwPot = throwPot;
        this.aimAssist = aimAssist;
        this.leftClicker = leftClicker;
        this.equipo = equipo;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            if (this.throwPot.isEnabled()) {
                ModuleInvoker.invoke(this.throwPot, "onClientTick", new Class<?>[0]);
            }
            return;
        }

        if (this.refill.isEnabled()) {
            ModuleInvoker.invoke(this.refill, "onClientTick", new Class<?>[0]);
        }
        if (this.leftClicker.isEnabled()) {
            ModuleInvoker.invoke(this.leftClicker, "onClientTick", new Class<?>[0]);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (this.aimAssist.isEnabled()) {
            ModuleInvoker.invoke(this.aimAssist, "onRenderTick",
                    new Class<?>[] { Float.TYPE }, Float.valueOf(event.renderTickTime));
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        // Preserve registration order for left-click ownership handoff.
        if (this.leftClicker.isEnabled()) {
            ModuleInvoker.invoke(this.leftClicker, "onRenderOverlay", new Class<?>[0]);
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (this.equipo.isEnabled()) {
            ModuleInvoker.invoke(this.equipo, "onRenderWorld", new Class<?>[0]);
        }
    }
}

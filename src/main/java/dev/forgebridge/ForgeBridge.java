package dev.forgebridge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * The bridge's mod entry point.
 *
 * <p>NeoForge 21.1 passes the mod's event bus and container to the constructor, which is exactly
 * what the event and registry bridges need. All real work happens in {@link Bootstrap}; keeping
 * this class trivial means the integration surface with FML is one constructor signature.</p>
 */
@Mod(BridgeConstants.MOD_ID)
public final class ForgeBridge {

    public ForgeBridge(IEventBus modEventBus, ModContainer modContainer) {
        Bootstrap.onModConstructed(modEventBus, modContainer);
    }
}

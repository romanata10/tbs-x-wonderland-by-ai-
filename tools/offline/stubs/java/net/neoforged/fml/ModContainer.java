package net.neoforged.fml;

import net.neoforged.bus.api.IEventBus;

/** Compile-time stub of NeoForge's ModContainer. */
public interface ModContainer {

    IEventBus getEventBus();

    String getModId();
}

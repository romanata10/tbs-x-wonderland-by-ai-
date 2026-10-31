package dev.forgebridge.compat.nbt;

import dev.forgebridge.events.ConsumerProxy;
import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

/**
 * Flushes mutated NBT tags once per tick.
 *
 * <p>A 1.21.1 data component is an immutable value, so the tag a mod receives from
 * {@code getOrCreateTag()} is a copy. Mods mutate it and never tell anyone, which is fine in
 * Forge 1.20.1 (the tag *was* the stored data) but silently loses the change on 1.21.1. Writing
 * the copies back once per tick keeps both worlds consistent without asking the mod to change:
 * the window in which another mod could observe a stale component is one tick, the same window
 * a player cannot perceive.</p>
 *
 * <p>Server and client ticks are separate events, and the bridge subscribes to whichever exist.
 * Outside a game nothing is found and the flush simply never needs to run.</p>
 */
public final class NbtTickBridge {

    private static final String[] TICK_EVENTS = new String[]{
            "net.neoforged.neoforge.event.tick.ServerTickEvent$Post",
            "net.neoforged.neoforge.event.tick.ClientTickEvent$Post",
            "net.neoforged.neoforge.event.TickEvent$ServerTick",
            "net.neoforged.neoforge.event.TickEvent$ClientTick"
    };

    private static boolean installed;
    private static int listeners;

    private NbtTickBridge() {
    }

    /**
     * Subscribes the flush to every tick event present on the class path.
     *
     * @return number of listeners installed
     */
    public static int install() {
        if (installed) {
            return listeners;
        }
        Object bus = SafeReflect.getStatic(
                SafeReflect.findClass("net.neoforged.neoforge.common.NeoForge"), "EVENT_BUS");
        if (bus == null) {
            BridgeLog.debug("No game event bus; NBT flush will rely on explicit writes");
            return 0;
        }
        for (int i = 0; i < TICK_EVENTS.length; i++) {
            Class<?> eventType = SafeReflect.findClass(TICK_EVENTS[i]);
            if (eventType == null) {
                continue;
            }
            Object consumer = ConsumerProxy.create(eventType.getClassLoader(), "NbtFlush",
                    new ConsumerProxy.Callback() {
                        public void accept(Object event) {
                            ItemStackNbtBridge.flushDirty();
                        }
                    });
            if (consumer == null) {
                continue;
            }
            Object registered = SafeReflect.invoke(bus, "addListener", eventType, consumer);
            if (registered == null) {
                registered = SafeReflect.invoke(bus, "addListener",
                        dev.forgebridge.events.EventPriorityCompat.toNeoForgeConstant(
                                dev.forgebridge.events.EventPriorityCompat.LOWEST),
                        eventType, consumer);
            }
            listeners++;
        }
        installed = listeners > 0;
        if (installed) {
            BridgeLog.info("NBT flush attached to " + listeners + " tick event(s)");
        } else {
            BridgeLog.warn("Tick events were not found; mutated NBT is written on explicit setTag calls only");
        }
        return listeners;
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static int getListenerCount() {
        return listeners;
    }

    /** Test hook: forget the installation state. */
    public static void reset() {
        installed = false;
        listeners = 0;
    }
}

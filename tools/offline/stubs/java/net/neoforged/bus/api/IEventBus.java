package net.neoforged.bus.api;

import java.util.function.Consumer;

/** Compile-time stub of NeoForge's IEventBus. */
public interface IEventBus {

    void register(Object target);

    void unregister(Object target);

    <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, Class<T> eventType, Consumer<T> consumer);

    <T extends Event> void post(T event);
}

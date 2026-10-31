package dev.forgebridge.compat.registry;

import java.util.Collection;
import java.util.Set;

/**
 * Stand-in for {@code net.minecraftforge.registries.IForgeRegistry}.
 *
 * <p>The bytecode remapper rewrites every reference to {@code IForgeRegistry} to this
 * interface, so it has to offer the methods 1.21.1 mods actually call. Implementations
 * ({@link ReflectiveLegacyRegistry}) delegate to the real NeoForge registry through
 * reflection, which keeps the bridge free of compile-time NeoForge types and lets it run
 * inside the CLI where no game is present.</p>
 *
 * @param <T> element type, erased at call sites exactly like the Forge interface
 */
public interface LegacyRegistry<T> {

    /** The id the registry is known by, e.g. {@code minecraft:item}. */
    Object getRegistryName();

    /** Reverse lookup: the id of a registered value, or {@code null}. */
    Object getKey(T value);

    T getValue(Object key);

    boolean containsKey(Object key);

    boolean containsValue(T value);

    Set<Object> getKeys();

    Collection<T> getValues();

    /** Registers a value. Returns the value, or the existing one when the id is taken. */
    T register(Object key, T value);

    int size();
}

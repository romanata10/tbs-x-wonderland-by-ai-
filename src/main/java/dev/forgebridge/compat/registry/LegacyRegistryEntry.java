package dev.forgebridge.compat.registry;

/**
 * Stand-in for {@code net.minecraftforge.registries.IForgeRegistryEntry}.
 *
 * <p>In Forge 1.20.1 this interface is already an empty marker (the id lives on the registry),
 * and NeoForge 21.1 dropped it. Translated code still writes
 * {@code implements IForgeRegistryEntry}, so the empty marker is kept to
 * avoid a {@code NoClassDefFoundError} without adding behaviour that never existed.</p>
 */
public interface LegacyRegistryEntry {
}

package dev.forgebridge.compat.registry;

/**
 * Stand-in for {@code net.minecraftforge.registries.IForgeRegistryEntry}.
 *
 * <p>In Forge 1.21.1 this interface carries no methods (the id lives on the registry), but
 * mods still write {@code implements IForgeRegistryEntry}. Keeping a marker interface
 * avoids a {@code NoClassDefFoundError} without changing behaviour.</p>
 */
public interface LegacyRegistryEntry {
}

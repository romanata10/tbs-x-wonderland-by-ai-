package dev.forgebridge.compat.registry;

import dev.forgebridge.util.SafeReflect;

/**
 * Stand-in for {@code RegistryManager}, which let mods look a registry up by name at
 * runtime (used by datapack driven and scripted mods).
 */
public final class LegacyRegistryManager {

    private LegacyRegistryManager() {
    }

    /** @param id a registry id such as {@code minecraft:item} */
    public static LegacyRegistry<Object> getRegistry(String id) {
        if (id == null) {
            return new NullLegacyRegistry("null");
        }
        String path = id;
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        String known = alias(path);
        if (known != null) {
            return RegistryAccess.resolve(known);
        }
        Object registry = lookupReflectively(path, id);
        if (registry == null) {
            return new NullLegacyRegistry(id);
        }
        return new ReflectiveLegacyRegistry(registry, id);
    }

    private static String alias(String path) {
        if ("items".equals(path) || "item".equals(path)) {
            return "items";
        }
        if ("blocks".equals(path) || "block".equals(path)) {
            return "blocks";
        }
        if ("entity_type".equals(path) || "entity_types".equals(path)) {
            return "entityTypes";
        }
        if ("mob_effect".equals(path) || "mob_effects".equals(path)) {
            return "mobEffects";
        }
        if ("sound_event".equals(path) || "sound_events".equals(path)) {
            return "soundEvents";
        }
        if ("fluid".equals(path) || "fluids".equals(path)) {
            return "fluids";
        }
        if ("potion".equals(path) || "potions".equals(path)) {
            return "potions";
        }
        if ("enchantment".equals(path) || "enchantments".equals(path)) {
            return "enchantments";
        }
        if ("particle_type".equals(path) || "particles".equals(path)) {
            return "particleTypes";
        }
        return null;
    }

    private static Object lookupReflectively(String path, String fullId) {
        Class<?> builtIn = SafeReflect.findClass("net.minecraft.core.registries.BuiltInRegistries");
        if (builtIn == null) {
            return null;
        }
        Object direct = SafeReflect.getStatic(builtIn, path.toUpperCase(java.util.Locale.ROOT));
        if (direct != null) {
            return direct;
        }
        Object registryRegistry = SafeReflect.getStatic(builtIn, "REGISTRY");
        if (registryRegistry != null) {
            Object id = ReflectiveLegacyRegistry.createResourceLocation(fullId.indexOf(':') >= 0 ? fullId : "minecraft:" + fullId);
            return SafeReflect.invoke(registryRegistry, "get", id);
        }
        return null;
    }
}

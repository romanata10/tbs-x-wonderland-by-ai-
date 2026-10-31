package dev.forgebridge.compat.registry;

import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

import java.util.HashMap;
import java.util.Map;

/**
 * The bridge's replacement for {@code net.minecraftforge.registries.ForgeRegistries}.
 *
 * <p>{@code RedirectTable} rewrites every {@code GETSTATIC ForgeRegistries.X} into a call to
 * one of these methods, which resolves the matching {@code BuiltInRegistries} field once and
 * caches a {@link ReflectiveLegacyRegistry} around it. That indirection is what makes the
 * Forge registry API usable on NeoForge at all: the field no longer has the same name, type
 * or semantics, but the calls a mod makes still do.</p>
 */
public final class RegistryAccess {

    private static final Map<String, LegacyRegistry<Object>> CACHE = new HashMap<String, LegacyRegistry<Object>>();

    /** bridge method name, NeoForge field, registry id, Forge field name */
    private static final String[][] FIELDS = new String[][]{
            {"items", "ITEM", "minecraft:item", "ITEMS"},
            {"blocks", "BLOCK", "minecraft:block", "BLOCKS"},
            {"entityTypes", "ENTITY_TYPE", "minecraft:entity_type", "ENTITIES"},
            {"soundEvents", "SOUND_EVENT", "minecraft:sound_event", "SOUND_EVENTS"},
            {"fluids", "FLUID", "minecraft:fluid", "FLUIDS"},
            {"mobEffects", "MOB_EFFECT", "minecraft:mob_effect", "MOB_EFFECTS"},
            {"potions", "POTION", "minecraft:potion", "POTIONS"},
            {"enchantments", "ENCHANTMENT", "minecraft:enchantment", "ENCHANTMENTS"},
            {"particleTypes", "PARTICLE_TYPE", "minecraft:particle_type", "PARTICLES"},
            {"creativeModeTabs", "CREATIVE_MODE_TAB", "minecraft:creative_mode_tab", "CREATIVE_MODE_TABS"},
            {"recipeSerializers", "RECIPE_SERIALIZER", "minecraft:recipe_serializer", "RECIPE_SERIALIZERS"},
            {"blockEntityTypes", "BLOCK_ENTITY_TYPE", "minecraft:block_entity_type", "BLOCK_ENTITY_TYPES"},
            {"menuTypes", "MENU", "minecraft:menu", "MENU_TYPES"},
            {"attributes", "ATTRIBUTE", "minecraft:attribute", "ATTRIBUTES"},
            {"dataComponents", "DATA_COMPONENT_TYPE", "minecraft:data_component_type", "DATA_COMPONENT_TYPES"}
    };

    private RegistryAccess() {
    }

    public static LegacyRegistry<Object> items() {
        return resolve("items");
    }

    public static LegacyRegistry<Object> blocks() {
        return resolve("blocks");
    }

    public static LegacyRegistry<Object> entityTypes() {
        return resolve("entityTypes");
    }

    public static LegacyRegistry<Object> soundEvents() {
        return resolve("soundEvents");
    }

    public static LegacyRegistry<Object> fluids() {
        return resolve("fluids");
    }

    public static LegacyRegistry<Object> mobEffects() {
        return resolve("mobEffects");
    }

    public static LegacyRegistry<Object> potions() {
        return resolve("potions");
    }

    public static LegacyRegistry<Object> enchantments() {
        return resolve("enchantments");
    }

    public static LegacyRegistry<Object> particleTypes() {
        return resolve("particleTypes");
    }

    public static LegacyRegistry<Object> creativeModeTabs() {
        return resolve("creativeModeTabs");
    }

    public static LegacyRegistry<Object> recipeSerializers() {
        return resolve("recipeSerializers");
    }

    public static LegacyRegistry<Object> blockEntityTypes() {
        return resolve("blockEntityTypes");
    }

    public static LegacyRegistry<Object> menuTypes() {
        return resolve("menuTypes");
    }

    public static LegacyRegistry<Object> attributes() {
        return resolve("attributes");
    }

    public static LegacyRegistry<Object> dataComponents() {
        return resolve("dataComponents");
    }

    /** Resolves by the bridge-side key (the method name used in the redirect table). */
    public static synchronized LegacyRegistry<Object> resolve(String key) {
        LegacyRegistry<Object> cached = (LegacyRegistry<Object>) CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String field = null;
        String registryName = null;
        for (int i = 0; i < FIELDS.length; i++) {
            if (FIELDS[i][0].equals(key)) {
                field = FIELDS[i][1];
                registryName = FIELDS[i][2];
                break;
            }
        }
        LegacyRegistry<Object> resolved = null;
        if (field != null) {
            Class<?> builtIn = SafeReflect.findClass("net.minecraft.core.registries.BuiltInRegistries");
            Object registry = SafeReflect.getStatic(builtIn, field);
            if (registry != null) {
                resolved = new ReflectiveLegacyRegistry(registry, registryName);
            }
        }
        if (resolved == null) {
            BridgeLog.debug("Registry '" + key + "' is not resolvable yet (game not started?)");
            resolved = new NullLegacyRegistry(key);
        }
        CACHE.put(key, resolved);
        return resolved;
    }


    /** Maps a registry id such as {@code minecraft:item} to the bridge-side key. */
    public static String keyForRegistryId(String registryId) {
        if (registryId == null) {
            return null;
        }
        for (int i = 0; i < FIELDS.length; i++) {
            if (FIELDS[i][2].equals(registryId)) {
                return FIELDS[i][0];
            }
        }
        String path = registryId;
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }
        String modern = LegacyRegistryManager.getRegistry(registryId).getRegistryName() == null ? null : path;
        if (modern == null) {
            return null;
        }
        // Generic fallback: "minecraft:block_entity_type" -> "blockEntityTypes"
        String[] parts = path.split("_");
        StringBuilder camel = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            camel.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        camel.append('s');
        for (int i = 0; i < FIELDS.length; i++) {
            if (FIELDS[i][0].equalsIgnoreCase(camel.toString())) {
                return FIELDS[i][0];
            }
        }
        return null;
    }

    /** The underlying NeoForge {@code Registry} object for a bridge-side key, or {@code null}. */
    public static Object registryObject(String key) {
        LegacyRegistry<Object> registry = resolve(key);
        if (registry instanceof ReflectiveLegacyRegistry) {
            return ((ReflectiveLegacyRegistry) registry).unwrap();
        }
        return null;
    }

    /**
     * The {@code ResourceKey} identifying a registry, which is what NeoForge's
     * {@code DeferredRegister.create(...)} expects in 1.21.1.
     */
    public static Object registryKey(String key) {
        Object registry = registryObject(key);
        if (registry == null) {
            return null;
        }
        Object resourceKey = SafeReflect.invoke(registry, "key");
        if (resourceKey != null) {
            return resourceKey;
        }
        return SafeReflect.invoke(registry, "getRegistryKey");
    }

    /** Forgets resolved handles; called when a world unloads. */
    public static synchronized void clear() {
        CACHE.clear();
    }

    public static String[] knownKeys() {
        String[] keys = new String[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) {
            keys[i] = FIELDS[i][0];
        }
        return keys;
    }

    /** Maps a Forge field constant name (e.g. {@code ITEMS}) to the bridge method name. */
    public static String methodForLegacyField(String legacyField) {
        for (int i = 0; i < FIELDS.length; i++) {
            if (FIELDS[i][3].equals(legacyField)) {
                return FIELDS[i][0];
            }
        }
        return null;
    }
}

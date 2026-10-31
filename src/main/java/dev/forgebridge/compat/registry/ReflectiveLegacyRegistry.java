package dev.forgebridge.compat.registry;

import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Adapter from a NeoForge {@code Registry} (or {@code WritableRegistry}) to the Forge
 * {@code IForgeRegistry} shape.
 *
 * <p>NeoForge's registry interface is close enough to map directly: {@code getKey},
 * {@code getValue}, {@code containsKey}, {@code keySet}. Everything is resolved
 * reflectively so the bridge never pins a NeoForge type.</p>
 */
public final class ReflectiveLegacyRegistry implements LegacyRegistry<Object> {

    private final Object registry;
    private final String registryName;
    private final Object registryId;

    public ReflectiveLegacyRegistry(Object registry, String registryName) {
        this.registry = registry;
        this.registryName = registryName;
        this.registryId = createResourceLocation(registryName);
    }

    public static Object createResourceLocation(String id) {
        if (id == null) {
            return null;
        }
        Class<?> resourceLocation = SafeReflect.findClass("net.minecraft.resources.ResourceLocation");
        if (resourceLocation == null) {
            return null;
        }
        Object parsed = SafeReflect.invokeStatic(resourceLocation, "tryParse", id);
        if (parsed != null && !String.valueOf(parsed).isEmpty()) {
            return parsed;
        }
        return SafeReflect.invokeStatic(resourceLocation, "parse", id);
    }

    public Object getRegistryName() {
        return registryId == null ? registryName : registryId;
    }

    public Object getKey(Object value) {
        return value == null ? null : SafeReflect.invoke(registry, "getKey", value);
    }

    public Object getValue(Object key) {
        return key == null ? null : SafeReflect.invoke(registry, "getValue", key);
    }

    public boolean containsKey(Object key) {
        if (key == null) {
            return false;
        }
        Object result = SafeReflect.invoke(registry, "containsKey", key);
        return result instanceof Boolean && ((Boolean) result).booleanValue();
    }

    public boolean containsValue(Object value) {
        if (value == null) {
            return false;
        }
        Object result = SafeReflect.invoke(registry, "containsValue", value);
        return result instanceof Boolean && ((Boolean) result).booleanValue();
    }

    public Set<Object> getKeys() {
        Object keys = SafeReflect.invoke(registry, "keySet");
        if (keys instanceof Collection) {
            return new HashSet<Object>((Collection<?>) keys);
        }
        Set<Object> result = new HashSet<Object>();
        Object entrySet = SafeReflect.invoke(registry, "entrySet");
        if (entrySet instanceof Collection) {
            for (Object entry : (Collection<?>) entrySet) {
                Object key = SafeReflect.invoke(entry, "getKey");
                if (key != null) {
                    result.add(key);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public Collection<Object> getValues() {
        Object stream = SafeReflect.invoke(registry, "stream");
        if (stream != null) {
            Object collected = SafeReflect.invoke(stream, "toList");
            if (collected instanceof Collection) {
                return (Collection<Object>) collected;
            }
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public Object register(Object key, Object value) {
        Object result = SafeReflect.invoke(registry, "register", key, value);
        if (result == null) {
            BridgeLog.warn("Registry " + registryName + " rejected registration of " + key
                    + " (it is probably frozen - register during RegisterEvent)");
            return value;
        }
        return result;
    }

    public int size() {
        Object size = SafeReflect.invoke(registry, "size");
        return size instanceof Integer ? ((Integer) size).intValue() : 0;
    }

    public Object unwrap() {
        return registry;
    }

    public String describe() {
        return "LegacyRegistry[" + registryName + "]";
    }
}

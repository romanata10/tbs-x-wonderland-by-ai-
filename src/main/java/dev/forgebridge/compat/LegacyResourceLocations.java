package dev.forgebridge.compat;

import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

/**
 * Replacement for the {@code ResourceLocation} constructors Minecraft made private in 1.21.
 *
 * <p>Forge 1.20.1 mods build ids with {@code new ResourceLocation("mod:thing")} and
 * {@code new ResourceLocation("mod", "thing")}. In 1.21 the constructors are private and the
 * supported entry points are {@code ResourceLocation.parse(String)} and
 * {@code ResourceLocation.fromNamespaceAndPath(String, String)}; the redirect table rewrites the
 * constructor call sites into these methods.</p>
 *
 * <p>Every lookup falls back through the older names as well, so the shim keeps working if a
 * future version renames the factories again.</p>
 */
public final class LegacyResourceLocations {

    private static final String LOCATION = "net.minecraft.resources.ResourceLocation";

    private LegacyResourceLocations() {
    }

    /** {@code new ResourceLocation(String)} — a full id such as {@code "mod:thing"}. */
    public static Object parse(String id) {
        Class<?> type = SafeReflect.findClass(LOCATION);
        if (type == null || id == null) {
            return null;
        }
        Object parsed = SafeReflect.invokeStatic(type, "parse", id);
        if (parsed == null) {
            parsed = SafeReflect.invokeStatic(type, "tryParse", id);
        }
        if (parsed == null && hasPublicConstructor(type, 1)) {
            parsed = SafeReflect.newInstance(type, id);
        }
        if (parsed == null) {
            BridgeLog.warn("Could not parse resource location '" + id + "'");
        }
        return parsed;
    }

    /** {@code new ResourceLocation(String namespace, String path)} */
    public static Object fromNamespaceAndPath(String namespace, String path) {
        Class<?> type = SafeReflect.findClass(LOCATION);
        if (type == null || namespace == null || path == null) {
            return null;
        }
        Object created = SafeReflect.invokeStatic(type, "fromNamespaceAndPath", namespace, path);
        if (created == null) {
            created = SafeReflect.invokeStatic(type, "tryBuild", namespace, path);
        }
        if (created == null && hasPublicConstructor(type, 2)) {
            created = SafeReflect.newInstance(type, namespace, path);
        }
        if (created == null) {
            BridgeLog.warn("Could not build resource location '" + namespace + ":" + path + "'");
        }
        return created;
    }

    private static boolean hasPublicConstructor(Class<?> type, int parameterCount) {
        java.lang.reflect.Constructor<?>[] constructors = type.getConstructors();
        for (int i = 0; i < constructors.length; i++) {
            if (constructors[i].getParameterTypes().length == parameterCount) {
                return true;
            }
        }
        return false;
    }
}

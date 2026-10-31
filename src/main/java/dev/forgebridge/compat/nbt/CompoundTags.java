package dev.forgebridge.compat.nbt;

import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

import java.util.Map;

/**
 * Compound-tag operations on an opaque object.
 *
 * <p>1.20.1 mods hand {@code CompoundTag}s around constantly; the bridge must store and read
 * them without compiling against Minecraft. Two implementations back this class:</p>
 *
 * <ul>
 *   <li>inside a game, the real {@code net.minecraft.nbt.CompoundTag}, reached reflectively;</li>
 *   <li>outside a game (CLI, tests), a {@link SimpleTag} map with the same behaviour.</li>
 * </ul>
 *
 * <p>Every method is null-tolerant: a translation pass must never fail because a mod passed a
 * tag that does not exist.</p>
 */
public final class CompoundTags {

    private static final String COMPOUND_TAG = "net.minecraft.nbt.CompoundTag";

    private CompoundTags() {
    }

    /** True when the real Minecraft class is available. */
    public static boolean isGamePresent() {
        return SafeReflect.findClass(COMPOUND_TAG) != null;
    }

    public static Object newCompound() {
        Class<?> type = SafeReflect.findClass(COMPOUND_TAG);
        if (type == null) {
            return new SimpleTag();
        }
        Object created = SafeReflect.newInstance(type);
        if (created == null) {
            BridgeLog.errorOnce("no-compound-tag", "Could not instantiate CompoundTag; falling back to a map");
            return new SimpleTag();
        }
        return created;
    }

    /**
     * A copy of the tag, or the tag itself when it cannot be copied.
     *
     * <p>Used where the value handed to a mod must not alias stored data: 1.21.1 data components
     * are immutable, so a component read yields a copy, and a store that mimics that behaviour has
     * to reproduce it here.</p>
     */
    public static Object copy(Object tag) {
        if (tag == null) {
            return null;
        }
        if (!isGamePresent()) {
            return tag instanceof Map ? new SimpleTag((Map) tag) : tag;
        }
        Object copied = SafeReflect.invoke(tag, "copy");
        return copied == null ? tag : copied;
    }

    /** The nested compound stored under {@code key}, or {@code null} when there is none. */
    public static Object getCompound(Object tag, String key) {
        if (tag == null || key == null) {
            return null;
        }
        if (tag instanceof Map) {
            Map raw = (Map) tag;
            Object value = raw.get(key);
            return value instanceof Map ? value : null;
        }
        if (!contains(tag, key)) {
            return null;
        }
        return SafeReflect.invoke(tag, "getCompound", key);
    }

    public static boolean contains(Object tag, String key) {
        if (tag == null || key == null) {
            return false;
        }
        if (tag instanceof Map) {
            Map raw = (Map) tag;
            return raw.containsKey(key);
        }
        Object result = SafeReflect.invoke(tag, "contains", key);
        return result instanceof Boolean && ((Boolean) result).booleanValue();
    }

    public static void put(Object tag, String key, Object value) {
        if (tag == null || key == null) {
            return;
        }
        if (tag instanceof Map) {
            Map raw = (Map) tag;
            raw.put(key, value);
            return;
        }
        SafeReflect.invoke(tag, "put", key, value);
    }

    public static void remove(Object tag, String key) {
        if (tag == null || key == null) {
            return;
        }
        if (tag instanceof Map) {
            Map raw = (Map) tag;
            raw.remove(key);
            return;
        }
        SafeReflect.invoke(tag, "remove", key);
    }

    public static boolean isEmpty(Object tag) {
        if (tag == null) {
            return true;
        }
        if (tag instanceof Map) {
            Map raw = (Map) tag;
            return raw.isEmpty();
        }
        Object result = SafeReflect.invoke(tag, "isEmpty");
        return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
    }

    public static int size(Object tag) {
        if (tag == null) {
            return 0;
        }
        if (tag instanceof Map) {
            Map raw = (Map) tag;
            return raw.size();
        }
        Object result = SafeReflect.invoke(tag, "size");
        return result instanceof Integer ? ((Integer) result).intValue() : 0;
    }

    /** Structural comparison used by tests and diagnostics; tags are opaque otherwise. */
    public static String describe(Object tag) {
        if (tag == null) {
            return "null";
        }
        if (tag instanceof Map) {
            return tag.toString();
        }
        return String.valueOf(tag);
    }
}

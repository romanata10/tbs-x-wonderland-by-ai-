package dev.forgebridge.compat.nbt;

import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

/**
 * Stores the tag in {@code DataComponents.CUSTOM_DATA} on a real 1.21.1 {@code ItemStack}.
 *
 * <p>This is the replacement for 1.20.1's {@code ItemStack.tag}: 1.20.5 removed the field and
 * the accessors, and the supported place for arbitrary mod data is the {@code custom_data}
 * component, which wraps a {@code CompoundTag} (`minecraft:custom_data` in NBT, kept in sync
 * with {@code ItemStack}'s NBT form).</p>
 *
 * <p>All access is reflective, so the bridge compiles without the game. Reads return a copy:
 * a component is an immutable value, so the bridge tracks the copies it handed out and writes
 * them back when the stack is next flushed. {@code CustomData.update(...)} exists for the same
 * reason, but it needs a lambda and a compile-time type, neither of which the bridge has.</p>
 */
final class CustomDataTagStore implements TagStore {

    private static final String DATA_COMPONENTS = "net.minecraft.core.component.DataComponents";
    private static final String CUSTOM_DATA = "net.minecraft.world.item.component.CustomData";

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static Class<?> customDataType;
    private static Object customDataComponentType;

    private final Object stack;

    CustomDataTagStore(Object stack) {
        this.stack = stack;
    }

    /** True when both the component holder and the component type could be resolved. */
    static boolean available() {
        if (!resolved) {
            synchronized (CustomDataTagStore.class) {
                if (!resolved) {
                    customDataType = SafeReflect.findClass(CUSTOM_DATA);
                    Class<?> holder = SafeReflect.findClass(DATA_COMPONENTS);
                    customDataComponentType = SafeReflect.getStatic(holder, "CUSTOM_DATA");
                    available = customDataType != null && customDataComponentType != null;
                    if (!available) {
                        BridgeLog.warn("CustomData component not found; NBT emulation will be in-memory only");
                    }
                    resolved = true;
                }
            }
        }
        return available;
    }

    public Object read() {
        Object component = SafeReflect.invoke(stack, "get", customDataComponentType);
        if (component == null) {
            return null;
        }
        Object tag = SafeReflect.invoke(component, "copyTag");
        return tag == null ? null : tag;
    }

    public void write(Object tag) {
        if (tag == null) {
            SafeReflect.invoke(stack, "set", customDataComponentType, null);
            return;
        }
        Object component = SafeReflect.invokeStatic(customDataType, "of", tag);
        if (component == null) {
            BridgeLog.errorOnce("custom-data-of", "CustomData.of(tag) failed; the tag cannot be stored");
            return;
        }
        SafeReflect.invoke(stack, "set", customDataComponentType, component);
    }

    public boolean isLive() {
        return false;
    }
}

package dev.forgebridge.compat.nbt;

import dev.forgebridge.util.BridgeLog;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Replacement for the {@code ItemStack} NBT accessors that Minecraft removed in 1.20.5.
 *
 * <p>A Forge 1.20.1 mod writes {@code stack.getOrCreateTag().putString("owner", name)} in dozens
 * of places; none of those methods exist in 1.21.1, where arbitrary mod data belongs in the
 * {@code custom_data} data component. {@link dev.forgebridge.remap.RedirectTable} rewrites each
 * call into the matching static method here, with the receiver turned into the first argument:</p>
 *
 * <pre>
 *   stack.getOrCreateTag()                 -&gt; ItemStackNbtBridge.getOrCreateTag(stack)
 *   stack.getOrCreateTagElement("display") -&gt; ItemStackNbtBridge.getOrCreateTagElement(stack, "display")
 *   stack.setTag(tag)                      -&gt; ItemStackNbtBridge.setTag(stack, tag)
 * </pre>
 *
 * <p><b>Why the dirty list exists.</b> A component is an immutable value: reading it yields a
 * copy. Forge handed out the live tag and mods mutate it after the call
 * ({@code getOrCreateTag().putInt(...)}), so the bridge remembers every tag it handed out and
 * writes those tags back on the next flush ({@link #flushDirty()}, driven by a tick listener).
 * Without a game (CLI, tests) the store is live and the list stays empty.</p>
 */
public final class ItemStackNbtBridge {

    /** Creates the storage for one stack; replaceable so the emulation can be tested. */
    public interface TagStoreFactory {
        TagStore createStore(Object target);
    }

    private static final Map<Object, Object> DIRTY = new WeakHashMap<Object, Object>();
    private static volatile TagStoreFactory factory;
    private static int flushes;
    private static int writes;

    private ItemStackNbtBridge() {
    }

    // ------------------------------------------------------------------ Forge 1.20.1 API

    /** {@code ItemStack.getTag()} */
    public static Object getTag(Object stack) {
        TagStore store = storeFor(stack);
        return store == null ? null : store.read();
    }

    /** {@code ItemStack.hasTag()} */
    public static boolean hasTag(Object stack) {
        Object tag = getTag(stack);
        return tag != null && !CompoundTags.isEmpty(tag);
    }

    /** {@code ItemStack.setTag(CompoundTag)} */
    public static void setTag(Object stack, Object tag) {
        TagStore store = storeFor(stack);
        if (store == null) {
            return;
        }
        if (tag != null && CompoundTags.isEmpty(tag)) {
            store.write(null);
        } else {
            store.write(tag);
        }
        forget(stack);
        writes++;
    }

    /**
     * {@code ItemStack.getOrCreateTag()}: creates an empty tag when the stack has none and
     * returns the mutable tag the caller is about to modify.
     */
    public static Object getOrCreateTag(Object stack) {
        TagStore store = storeFor(stack);
        if (store == null) {
            return null;
        }
        Object tag = store.read();
        if (tag == null) {
            tag = CompoundTags.newCompound();
            store.write(tag);
            writes++;
        } else if (!store.isLive()) {
            remember(stack, tag);
        }
        return tag;
    }

    /** {@code ItemStack.getTagElement(String)} */
    public static Object getTagElement(Object stack, String key) {
        Object tag = getTag(stack);
        return CompoundTags.getCompound(tag, key);
    }

    /** {@code ItemStack.getOrCreateTagElement(String)} */
    public static Object getOrCreateTagElement(Object stack, String key) {
        Object tag = getOrCreateTag(stack);
        if (tag == null) {
            return null;
        }
        Object element = CompoundTags.getCompound(tag, key);
        if (element == null) {
            element = CompoundTags.newCompound();
            CompoundTags.put(tag, key, element);
        }
        rememberIfCopying(stack, tag);
        return element;
    }

    /** {@code ItemStack.removeTagKey(String)} (1.20.1 added it; 1.21 removed it with the rest). */
    public static void removeTagKey(Object stack, String key) {
        TagStore store = storeFor(stack);
        if (store == null) {
            return;
        }
        Object tag = store.read();
        if (tag == null) {
            return;
        }
        CompoundTags.remove(tag, key);
        if (CompoundTags.isEmpty(tag)) {
            store.write(null);
            forget(stack);
        } else {
            rememberIfCopying(stack, tag);
        }
        writes++;
    }

    // ------------------------------------------------------------------ flushing

    /**
     * Writes every tag handed out since the last flush back to its stack.
     *
     * @return number of stacks that were written
     */
    public static int flushDirty() {
        List<Object[]> pending = new ArrayList<Object[]>();
        synchronized (ItemStackNbtBridge.class) {
            Iterator<Object> iterator = DIRTY.keySet().iterator();
            while (iterator.hasNext()) {
                Object stack = iterator.next();
                pending.add(new Object[]{stack, DIRTY.get(stack)});
            }
            DIRTY.clear();
        }
        int written = 0;
        for (int i = 0; i < pending.size(); i++) {
            Object[] pair = (Object[]) pending.get(i);
            TagStore store = storeFor(pair[0]);
            if (store == null) {
                continue;
            }
            store.write(pair[1]);
            written++;
        }
        if (written > 0) {
            flushes++;
            BridgeLog.debug("Flushed " + written + " mutated NBT tag(s) into custom_data components");
        }
        return written;
    }

    /** Number of stacks currently waiting to be flushed. */
    public static int getPendingWrites() {
        synchronized (ItemStackNbtBridge.class) {
            return DIRTY.size();
        }
    }

    public static int getFlushCount() {
        return flushes;
    }

    public static int getWriteCount() {
        return writes;
    }

    // ------------------------------------------------------------------ wiring

    /** Replaces the storage backend. Pass {@code null} to restore the default. */
    public static void setStoreFactory(TagStoreFactory replacement) {
        factory = replacement;
        clear();
    }

    public static void clear() {
        synchronized (ItemStackNbtBridge.class) {
            DIRTY.clear();
        }
        ShadowTagStore.clearAll();
        writes = 0;
        flushes = 0;
    }

    private static TagStore storeFor(Object stack) {
        if (stack == null) {
            return null;
        }
        TagStoreFactory custom = factory;
        if (custom != null) {
            return custom.createStore(stack);
        }
        if (CustomDataTagStore.available()) {
            return new CustomDataTagStore(stack);
        }
        return new ShadowTagStore(stack);
    }

    private static void remember(Object stack, Object tag) {
        synchronized (ItemStackNbtBridge.class) {
            DIRTY.put(stack, tag);
        }
    }

    private static void rememberIfCopying(Object stack, Object tag) {
        TagStore store = storeFor(stack);
        if (store != null && !store.isLive()) {
            remember(stack, tag);
        }
    }

    private static void forget(Object stack) {
        synchronized (ItemStackNbtBridge.class) {
            DIRTY.remove(stack);
        }
    }
}

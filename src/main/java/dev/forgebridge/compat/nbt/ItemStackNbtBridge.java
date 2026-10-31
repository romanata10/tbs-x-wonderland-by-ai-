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

    /**
     * Tags handed out to callers, keyed by stack (weakly, so a dropped stack stops being tracked).
     *
     * <p>A tracking entry is not a queue item: it stays while the stack is alive, and
     * {@link #flushDirty()} writes only the tags whose content changed since the last write. That
     * is what makes a second mutation of the same object survive - Forge had one live tag, so the
     * bridge has to keep looking at the object it handed out instead of dropping it after one
     * write.</p>
     */
    private static final Map<Object, Tracked> DIRTY = new WeakHashMap<Object, Tracked>();
    /** A handed-out tag plus the content that was last written for it. */
    private static final class Tracked {
        private final Object tag;
        private String written;

        Tracked(Object tag, String written) {
            this.tag = tag;
            this.written = written;
        }

        Object getTag() {
            return tag;
        }

        boolean isDirty() {
            return !String.valueOf(written).equals(CompoundTags.describe(tag));
        }

        void markWritten() {
            written = CompoundTags.describe(tag);
        }
    }

    private static volatile TagStoreFactory factory;
    private static int flushes;
    private static int writes;

    private ItemStackNbtBridge() {
    }

    // ------------------------------------------------------------------ Forge 1.20.1 API

    /** {@code ItemStack.getTag()} */
    public static Object getTag(Object stack) {
        flushStack(stack);
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
            forget(stack);
        } else {
            store.write(tag);
            // Forge's setTag stored the tag itself, so later mutations of the same object were
            // visible. A component stores a snapshot, so the object the caller still holds has to
            // be tracked until the next flush.
            rememberIfCopying(stack, tag);
        }
        writes++;
    }

    /**
     * {@code ItemStack.getOrCreateTag()}: creates an empty tag when the stack has none and
     * returns the mutable tag the caller is about to modify.
     */
    public static Object getOrCreateTag(Object stack) {
        flushStack(stack);
        TagStore store = storeFor(stack);
        if (store == null) {
            return null;
        }
        Object tag = store.read();
        if (tag == null) {
            tag = CompoundTags.newCompound();
            store.write(tag);
            writes++;
            // The caller mutates the object it just received (getOrCreateTag().putString(...)).
            // With a copying store that mutation only exists on this object, so it must be
            // flushed back; a live store already holds the same object.
            rememberIfCopying(stack, tag);
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
        flushStack(stack);
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

    // ------------------------------------------------------------------ read/write ordering

    /**
     * Writes a mutated tag back before the same stack is read again.
     *
     * <p>Reading a stack has to close the window rather than widen it: a Forge reader would have
     * seen the mutation immediately (there was one live tag), so a mod that writes
     * {@code getOrCreateTag().putString(...)} and then reads the stack back must not get the
     * pre-mutation value. Only <em>other</em> readers wait for the tick flush.</p>
     */
    private static void flushStack(Object stack) {
        Tracked tracked;
        synchronized (ItemStackNbtBridge.class) {
            tracked = (Tracked) DIRTY.get(stack);
        }
        if (tracked == null || !tracked.isDirty()) {
            return;
        }
        TagStore store = storeFor(stack);
        if (store == null) {
            return;
        }
        try {
            store.write(tracked.getTag());
            tracked.markWritten();
        } catch (Throwable error) {
            BridgeLog.warn("Could not write back NBT for a stack; it stays tracked", error);
        }
    }

    // ------------------------------------------------------------------ flushing

    /**
     * Writes every tracked tag whose content changed since the last write.
     *
     * <p>Attached to the server and client tick ({@link NbtTickBridge}), because a data component
     * is an immutable value: without this, a mutation of a handed-out tag would never reach the
     * stack for anyone who did not read it again.</p>
     *
     * @return number of stacks that were written
     */
    public static int flushDirty() {
        List<Object> dirty = new ArrayList<Object>();
        synchronized (ItemStackNbtBridge.class) {
            Iterator<Object> iterator = DIRTY.keySet().iterator();
            while (iterator.hasNext()) {
                Object stack = iterator.next();
                Tracked tracked = (Tracked) DIRTY.get(stack);
                if (tracked != null && tracked.isDirty()) {
                    dirty.add(stack);
                }
            }
        }
        int written = 0;
        for (int i = 0; i < dirty.size(); i++) {
            Object stack = dirty.get(i);
            Tracked tracked;
            synchronized (ItemStackNbtBridge.class) {
                tracked = (Tracked) DIRTY.get(stack);
            }
            if (tracked == null || !tracked.isDirty()) {
                continue;
            }
            TagStore store = storeFor(stack);
            if (store == null) {
                continue;
            }
            try {
                store.write(tracked.getTag());
                tracked.markWritten();
                written++;
            } catch (Throwable error) {
                BridgeLog.warn("Could not flush NBT for a stack; it stays tracked", error);
            }
        }
        if (written > 0) {
            flushes++;
            BridgeLog.debug("Flushed " + written + " mutated NBT tag(s) into custom_data components");
        }
        return written;
    }

    /** Number of tracked tags whose content differs from what was last written. */
    public static int getPendingWrites() {
        synchronized (ItemStackNbtBridge.class) {
            int pending = 0;
            Iterator<Object> iterator = DIRTY.keySet().iterator();
            while (iterator.hasNext()) {
                Tracked tracked = (Tracked) DIRTY.get(iterator.next());
                if (tracked != null && tracked.isDirty()) {
                    pending++;
                }
            }
            return pending;
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

    /**
     * Starts tracking a tag (and records what was just written for it).
     *
     * <p>An existing entry for the same object keeps its snapshot: the caller may already have
     * mutated the tag between the write and this call ({@code getOrCreateTagElement} nests a new
     * compound into it), and that mutation still has to count as pending.</p>
     */
    private static void remember(Object stack, Object tag) {
        if (stack == null || tag == null) {
            return;
        }
        synchronized (ItemStackNbtBridge.class) {
            Tracked existing = (Tracked) DIRTY.get(stack);
            if (existing != null && existing.getTag() == tag) {
                return;
            }
            DIRTY.put(stack, new Tracked(tag, CompoundTags.describe(tag)));
        }
    }

    /** Tracks a tag only when the store hands out copies; a live store already holds it. */
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

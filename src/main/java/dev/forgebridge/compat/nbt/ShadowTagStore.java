package dev.forgebridge.compat.nbt;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * In-memory tag storage used when the game is not on the class path.
 *
 * <p>Semantics match Forge 1.20.1: the tag handed out is the tag that is stored, so a mod can
 * mutate it in place. Keys are compared by identity because {@code ItemStack} does not override
 * {@code equals} and two stacks with the same contents are still different objects.</p>
 */
final class ShadowTagStore implements TagStore {

    private static final Map<Object, Object> TAGS = new WeakHashMap<Object, Object>();

    private final Object target;

    ShadowTagStore(Object target) {
        this.target = target;
    }

    static void clearAll() {
        TAGS.clear();
    }

    static int size() {
        return TAGS.size();
    }

    public Object read() {
        return TAGS.get(target);
    }

    public void write(Object tag) {
        if (tag == null) {
            TAGS.remove(target);
        } else {
            TAGS.put(target, tag);
        }
    }

    public boolean isLive() {
        return true;
    }
}

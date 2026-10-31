package dev.forgebridge.compat.nbt;

import java.util.LinkedHashMap;

/**
 * A compound tag stand-in used when the game is not on the class path.
 *
 * <p>The NBT bridge talks to {@code net.minecraft.nbt.CompoundTag} through
 * {@link CompoundTags}; outside a game that class does not exist, so a plain map takes its
 * place. Keeping the fallback a real map (rather than refusing to work) is what lets the NBT
 * emulation be verified offline: the same code paths run, only the storage type differs.</p>
 */
public final class SimpleTag extends LinkedHashMap<String, Object> {

    private static final long serialVersionUID = 1L;

    public SimpleTag() {
        super();
    }

    /** Shallow copy; the raw parameter keeps this compilable under Janino. */
    public SimpleTag(java.util.Map source) {
        super();
        if (source != null) {
            putAll(source);
        }
    }

    /**
     * Map ordering is insertion order, so the inherited {@code AbstractMap.toString()} already
     * yields a stable {@code {key=value, ...}} rendering; no override is needed (and Janino
     * cannot compile a typed {@code Map.Entry} loop here).
     */
}

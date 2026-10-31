package net.minecraft.nbt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test double for {@code net.minecraft.nbt.CompoundTag}.
 *
 * <p>Only the names the bridge reaches reflectively are implemented, with the same observable
 * behaviour: {@code copy()} snapshots (a data component is a value), {@code getCompound} returns
 * an empty tag when the key is absent, and the map is insertion ordered. It lives outside the
 * ordinary verification classpath on purpose: putting it there would flip
 * {@code CompoundTags.isGamePresent()} for every other test.</p>
 */
public class CompoundTag {

    private final Map<String, Object> values = new LinkedHashMap<String, Object>();

    public CompoundTag() {
    }

    public CompoundTag copy() {
        CompoundTag copy = new CompoundTag();
        copy.values.putAll(values);
        return copy;
    }

    public CompoundTag getCompound(String key) {
        Object value = values.get(key);
        return value instanceof CompoundTag ? (CompoundTag) value : new CompoundTag();
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public void put(String key, Object value) {
        values.put(key, value);
    }

    public void remove(String key) {
        values.remove(key);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    public String toString() {
        return "CompoundTag" + values;
    }
}

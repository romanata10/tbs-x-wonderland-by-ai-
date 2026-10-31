package net.minecraft.world.item;

import net.minecraft.core.component.DataComponentType;

import java.util.HashMap;
import java.util.Map;

/** Test double for {@code net.minecraft.world.item.ItemStack}: component get/set only. */
public class ItemStack {

    private final Map<DataComponentType<?>, Object> components = new HashMap<DataComponentType<?>, Object>();

    @SuppressWarnings("unchecked")
    public <T> T get(DataComponentType<T> type) {
        return (T) components.get(type);
    }

    @SuppressWarnings("unchecked")
    public <T> T set(DataComponentType<T> type, T value) {
        Object previous = components.remove(type);
        if (value != null) {
            components.put(type, value);
        }
        return (T) previous;
    }
}

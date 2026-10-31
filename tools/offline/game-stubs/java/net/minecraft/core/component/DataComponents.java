package net.minecraft.core.component;

import net.minecraft.world.item.component.CustomData;

/**
 * Test double for {@code net.minecraft.core.component.DataComponents}.
 *
 * <p>Carries the one constant the NBT emulation looks for. That lookup is the first thing the
 * bridge tries at runtime, so exercising it against a stand-in class of the right shape is worth
 * more than asserting that the string is spelled correctly.</p>
 */
public final class DataComponents {

    public static final DataComponentType<CustomData> CUSTOM_DATA = new DataComponentType<CustomData>();

    private DataComponents() {
    }
}

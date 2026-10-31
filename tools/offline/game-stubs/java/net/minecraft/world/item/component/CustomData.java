package net.minecraft.world.item.component;

import net.minecraft.nbt.CompoundTag;

/**
 * Test double for {@code net.minecraft.world.item.component.CustomData}.
 *
 * <p>Immutable value semantics, like the real thing: {@code of(tag)} copies on the way in and
 * {@code copyTag()} copies on the way out, which is exactly why the bridge has to track the tags
 * it handed out.</p>
 */
public final class CustomData {

    private final CompoundTag tag;

    private CustomData(CompoundTag tag) {
        this.tag = tag;
    }

    public static CustomData of(CompoundTag tag) {
        return new CustomData(tag == null ? new CompoundTag() : tag.copy());
    }

    public CompoundTag copyTag() {
        return tag.copy();
    }
}

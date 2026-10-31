package dev.forgebridge.tests;

import dev.forgebridge.compat.nbt.CompoundTags;
import dev.forgebridge.compat.nbt.ItemStackNbtBridge;
import dev.forgebridge.compat.nbt.NbtTickBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the NBT emulation against a stand-in for the parts of Minecraft it talks to.
 *
 * <p>This is a <em>second</em> JVM, started by the harness with
 * {@code tools/offline/game-stubs} on the class path ahead of everything else. That placement is
 * the whole point: {@code CompoundTags.isGamePresent()} is a class-path question, so the
 * component path can only be exercised in a process where {@code CompoundTag} and
 * {@code DataComponents.CUSTOM_DATA} exist. In the ordinary suite they do not, and the bridge
 * correctly falls back to the shadow store.</p>
 *
 * <p>What this proves: the reflective lookups resolve, {@code CustomData.of}/{@code copyTag} are
 * called with the argument shapes the bridge emits, the copies that come back are tracked, and
 * {@code flushDirty()} writes them back. What it does not prove: that the real classes have those
 * members — the stand-ins are written from the same assumptions the bridge makes, so a wrong
 * assumption would be reproduced here rather than caught. Only a real launch can settle that, and
 * {@code docs/TESTING.md} says so.</p>
 */
public final class GamePathMain {

    private static final List<String> FAILURES = new ArrayList<String>();

    private GamePathMain() {
    }

    public static void main(String[] args) {
        System.out.println("ForgeBridge NBT component path (game-shape class path)");
        System.out.println("-------------------------------------------------------------");
        check("CompoundTag is on the class path", new Check() {
            public void run() {
                Assert.isTrue(CompoundTags.isGamePresent(), "game classes are present");
            }
        });
        check("getOrCreateTag goes through custom_data", new Check() {
            public void run() {
                ItemStackNbtBridge.clear();
                Object stack = newStack();
                Object tag = ItemStackNbtBridge.getOrCreateTag(stack);
                Assert.notNull(tag, "a tag was created");
                Assert.isFalse(tag instanceof java.util.Map,
                        "the tag is a real CompoundTag stand-in, not the map fallback");
                Assert.isFalse(ItemStackNbtBridge.hasTag(stack),
                        "an empty tag is not a tag, exactly as in Forge");
                CompoundTags.put(tag, "owner", "Steve");
                Assert.isTrue(ItemStackNbtBridge.hasTag(stack), "hasTag sees the created tag");
            }
        });
        check("mutations of a handed out copy survive the flush", new Check() {
            public void run() {
                ItemStackNbtBridge.clear();
                Object stack = newStack();
                Object first = ItemStackNbtBridge.getOrCreateTag(stack);
                CompoundTags.put(first, "seed", "initial");

                Object copy = ItemStackNbtBridge.getOrCreateTag(stack);
                Assert.notNull(copy, "a copy was handed out");
                Assert.isFalse(copy == first, "the component store hands out copies");
                Assert.equalInt(0, ItemStackNbtBridge.getPendingWrites(),
                        "a fresh copy matches the component, so nothing is pending yet");
                CompoundTags.put(copy, "charge", "full");
                Assert.equalInt(1, ItemStackNbtBridge.getPendingWrites(),
                        "mutating the copy makes it pending");
                Assert.isTrue(CompoundTags.contains(ItemStackNbtBridge.getTag(stack), "charge"),
                        "a read of the same stack sees the pending mutation");
                Assert.equalInt(0, ItemStackNbtBridge.getPendingWrites(),
                        "the read folded the mutation into the component");

                Object third = ItemStackNbtBridge.getOrCreateTag(stack);
                CompoundTags.put(third, "tier", "two");
                Assert.equalInt(1, ItemStackNbtBridge.flushDirty(), "one stack written back");
                Object afterFlush = ItemStackNbtBridge.getTag(stack);
                Assert.isTrue(CompoundTags.contains(afterFlush, "tier"), "tick flush wrote the mutation");
                Assert.isTrue(CompoundTags.contains(afterFlush, "charge"), "the earlier value survived");
                Assert.isTrue(CompoundTags.contains(afterFlush, "seed"), "the first value survived");
                Assert.equalInt(0, ItemStackNbtBridge.getPendingWrites(), "nothing is pending");
            }
        });
        check("nested tag elements round trip", new Check() {
            public void run() {
                ItemStackNbtBridge.clear();
                Object stack = newStack();
                Object element = ItemStackNbtBridge.getOrCreateTagElement(stack, "display");
                Assert.notNull(element, "nested tag created");
                CompoundTags.put(element, "name", "Rune");
                Assert.equalInt(1, ItemStackNbtBridge.flushDirty(), "nested mutation flushed");
                Object readBack = ItemStackNbtBridge.getTagElement(stack, "display");
                Assert.notNull(readBack, "nested tag readable");
                Assert.isTrue(CompoundTags.contains(readBack, "name"), "nested content survived");
            }
        });
        check("setTag(null) clears the component", new Check() {
            public void run() {
                ItemStackNbtBridge.clear();
                Object stack = newStack();
                CompoundTags.put(ItemStackNbtBridge.getOrCreateTag(stack), "owner", "Steve");
                Assert.equalInt(1, ItemStackNbtBridge.flushDirty(), "created tag flushed");
                Assert.isTrue(ItemStackNbtBridge.hasTag(stack), "tag present");
                ItemStackNbtBridge.setTag(stack, null);
                Assert.isFalse(ItemStackNbtBridge.hasTag(stack), "tag removed");
                Assert.isNull(ItemStackNbtBridge.getTag(stack), "read returns null");
            }
        });
        check("no tick events means install() reports zero listeners", new Check() {
            public void run() {
                NbtTickBridge.reset();
                Assert.equalInt(0, NbtTickBridge.install(), "nothing to attach to");
                Assert.isFalse(NbtTickBridge.isInstalled(), "not installed");
            }
        });

        System.out.println("-------------------------------------------------------------");
        if (!FAILURES.isEmpty()) {
            for (int i = 0; i < FAILURES.size(); i++) {
                System.out.println("  ! " + FAILURES.get(i));
            }
            System.out.println("game path failed: " + FAILURES.size());
            System.exit(1);
        }
        System.out.println("game path: all checks passed");
    }

    /** One stack stand-in: the harness uses the stubbed ItemStack, so its type is not named here. */
    private static Object newStack() {
        try {
            Class<?> stackType = Class.forName("net.minecraft.world.item.ItemStack");
            return stackType.getConstructor().newInstance();
        } catch (Exception error) {
            throw new IllegalStateException("ItemStack stand-in missing", error);
        }
    }

    private interface Check {
        void run();
    }

    private static void check(String name, Check check) {
        try {
            check.run();
            System.out.println("  PASS " + name);
        } catch (Throwable error) {
            FAILURES.add(name + ": " + error);
            System.out.println("  FAIL " + name + ": " + error);
        }
    }
}

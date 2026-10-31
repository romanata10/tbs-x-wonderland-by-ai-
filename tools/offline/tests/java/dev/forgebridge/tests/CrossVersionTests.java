package dev.forgebridge.tests;

import dev.forgebridge.compat.nbt.CompoundTags;
import dev.forgebridge.compat.nbt.ItemStackNbtBridge;
import dev.forgebridge.compat.nbt.TagStore;
import dev.forgebridge.compat.registry.LegacyRegistryQueue;
import dev.forgebridge.compat.registry.RegistryEvent;
import dev.forgebridge.compat.registry.RegistryLifecycleBridge;
import dev.forgebridge.remap.AsmSupport;
import dev.forgebridge.remap.BuiltinMappings;
import dev.forgebridge.remap.ClassRemapEngine;
import dev.forgebridge.remap.RedirectTable;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Verification of the 1.20.1 Forge -&gt; 1.21.1 NeoForge migration layer.
 *
 * <p>Everything here runs without a game: the bytecode tests read back what the transformer
 * emitted, the NBT tests drive the emulation through its replaceable store, and the registry
 * tests drive the lifecycle bridge through a recording registrar. What the tests can therefore
 * prove is that the translation produces the intended shapes; what they cannot prove is how the
 * real NeoForge classes behave at runtime. That boundary is recorded in {@code docs/TESTING.md}.</p>
 */
public final class CrossVersionTests {

    private CrossVersionTests() {
    }

    // ------------------------------------------------------------------ fixtures

    private static ClassRemapEngine engine() {
        return new ClassRemapEngine(BuiltinMappings.get(), RedirectTable.builtin());
    }

    private static RemapTests.Capture capture(byte[] bytes) {
        RemapTests.Capture capture = new RemapTests.Capture();
        new org.objectweb.asm.ClassReader(bytes).accept(capture, 0);
        return capture;
    }

    private static boolean anyContains(List<String> instructions, String needle) {
        for (int i = 0; i < instructions.size(); i++) {
            String instruction = (String) instructions.get(i);
            if (instruction.indexOf(needle) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** A class that calls {@code stack.getOrCreateTag()} the 1.20.1 way. */
    private static byte[] virtualCallClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "com/example/NbtUser",
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tag",
                "(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/nbt/CompoundTag;", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/item/ItemStack",
                "getOrCreateTag", "()Lnet/minecraft/nbt/CompoundTag;", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * A class that constructs a {@code ResourceLocation}.
     *
     * @param split when true a label is emitted between {@code DUP} and the constructor call, which
     *              is exactly the situation the redirector must refuse to touch
     */
    private static byte[] constructorClass(boolean split) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "com/example/LocationUser",
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "id",
                "(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "net/minecraft/resources/ResourceLocation");
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        if (split) {
            // A real branch target between the allocation and the constructor call: the rewrite
            // cannot prove the path, so the transformer must leave the sequence alone. A label
            // that nothing jumps to would be dropped by ASM and would not exercise this.
            Label target = new Label();
            method.visitJumpInsn(Opcodes.GOTO, target);
            method.visitLabel(target);
        }
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/resources/ResourceLocation",
                "<init>", "(Ljava/lang/String;)V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    // ------------------------------------------------------------------ ASM transformer

    public static void testNbtAccessorBecomesABridgeCall() {
        AsmSupport.override(AsmSupport.Level.FULL);
        ClassRemapEngine.Result result = engine().remap(virtualCallClass());
        Assert.isFalse(result.hasError(), "no remap error");
        Assert.isTrue(result.isChanged(), "class changed");
        List<String> instructions = capture(result.getBytes()).method("tag");
        Assert.isTrue(anyContains(instructions, "INVOKESTATIC dev/forgebridge/compat/nbt/ItemStackNbtBridge.getOrCreateTag"),
                "getOrCreateTag goes through the NBT bridge, was: " + instructions);
        Assert.isFalse(anyContains(instructions, "INVOKEVIRTUAL net/minecraft/world/item/ItemStack.getOrCreateTag"),
                "the removed accessor is gone");
        Assert.isTrue(anyContains(instructions, "CHECKCAST net/minecraft/nbt/CompoundTag"),
                "the result is cast back to CompoundTag");
        Assert.isTrue(result.getUnappliedRedirects().isEmpty(), "no unapplied redirects");
        Assert.isFalse(result.getAppliedRedirects().isEmpty(), "the redirect is reported as applied");
    }

    public static void testResourceLocationConstructorBecomesAFactory() {
        AsmSupport.override(AsmSupport.Level.FULL);
        ClassRemapEngine.Result result = engine().remap(constructorClass(false));
        Assert.isFalse(result.hasError(), "no remap error");
        List<String> instructions = capture(result.getBytes()).method("id");
        Assert.isTrue(anyContains(instructions, "INVOKESTATIC dev/forgebridge/compat/LegacyResourceLocations.parse"),
                "constructor replaced by parse(), was: " + instructions);
        Assert.isFalse(anyContains(instructions, "INVOKESPECIAL net/minecraft/resources/ResourceLocation.<init>"),
                "the private 1.21 constructor is no longer called");
        Assert.isFalse(anyContains(instructions, "NEW net/minecraft/resources/ResourceLocation"),
                "the allocation was dropped with the constructor");
        Assert.isTrue(anyContains(instructions, "CHECKCAST net/minecraft/resources/ResourceLocation"),
                "the factory result is cast back to ResourceLocation");
    }

    public static void testUnprovableConstructorIsReportedNotRewritten() {
        AsmSupport.override(AsmSupport.Level.FULL);
        ClassRemapEngine.Result result = engine().remap(constructorClass(true));
        Assert.isFalse(result.hasError(), "no remap error even when the redirect is skipped");
        List<String> instructions = capture(result.getBytes()).method("id");
        Assert.isTrue(anyContains(instructions, "INVOKESPECIAL net/minecraft/resources/ResourceLocation.<init>"),
                "the original instruction sequence is replayed intact, was: " + instructions);
        Assert.isFalse(result.getUnappliedRedirects().isEmpty(),
                "the skipped redirect is reported to the patcher");
    }

    // ------------------------------------------------------------------ NBT emulation

    public static void testNbtRoundTripsThroughTheShadowStore() {
        ItemStackNbtBridge.clear();
        Object stack = new Object();
        Assert.isFalse(ItemStackNbtBridge.hasTag(stack), "a fresh stack has no tag");

        Object tag = ItemStackNbtBridge.getOrCreateTag(stack);
        Assert.notNull(tag, "getOrCreateTag creates a tag");
        CompoundTags.put(tag, "owner", "Steve");
        Assert.isTrue(ItemStackNbtBridge.hasTag(stack), "the tag is visible on the stack");
        Assert.contains(CompoundTags.describe(ItemStackNbtBridge.getTag(stack)), "owner",
                "the mutation survives a second read");

        Object element = ItemStackNbtBridge.getOrCreateTagElement(stack, "display");
        Assert.notNull(element, "getOrCreateTagElement creates a nested tag");
        Assert.notNull(ItemStackNbtBridge.getTagElement(stack, "display"), "nested tag readable");
        Assert.isFalse(CompoundTags.isEmpty(ItemStackNbtBridge.getTag(stack)), "parent tag is not empty");

        ItemStackNbtBridge.removeTagKey(stack, "display");
        Assert.isNull(ItemStackNbtBridge.getTagElement(stack, "display"), "nested tag removed");

        ItemStackNbtBridge.setTag(stack, null);
        Assert.isFalse(ItemStackNbtBridge.hasTag(stack), "setTag(null) clears the stack");
        Assert.isTrue(ItemStackNbtBridge.getWriteCount() > 0, "writes are counted");
        ItemStackNbtBridge.clear();
    }

    public static void testCopiedTagsAreWrittenBackOnFlush() {
        ItemStackNbtBridge.clear();
        final Map stores = new HashMap();
        ItemStackNbtBridge.setStoreFactory(new ItemStackNbtBridge.TagStoreFactory() {
            public TagStore createStore(Object target) {
                return new CopyingStore(stores, target);
            }
        });
        try {
            Object stack = new Object();
            Object created = ItemStackNbtBridge.getOrCreateTag(stack);
            Assert.notNull(created, "first getOrCreateTag creates storage");
            Assert.equalInt(0, ItemStackNbtBridge.getPendingWrites(),
                    "a freshly created tag matches what was written");
            // The caller mutates the object it was handed, and the store only kept a snapshot,
            // so the object stays tracked and the mutation shows up as pending.
            CompoundTags.put(created, "seed", "initial");
            Assert.equalInt(1, ItemStackNbtBridge.getPendingWrites(),
                    "the mutation of the created tag is pending");
            Assert.equalInt(1, ItemStackNbtBridge.flushDirty(), "the created tag is written back");
            Assert.isTrue(CompoundTags.contains(ItemStackNbtBridge.getTag(stack), "seed"),
                    "a mutation made right after creation survives");

            Object handedOut = ItemStackNbtBridge.getOrCreateTag(stack);
            Assert.notNull(handedOut, "second getOrCreateTag returns a copy");
            Assert.isTrue(handedOut != created, "the store hands out copies");
            Assert.equalInt(0, ItemStackNbtBridge.getPendingWrites(),
                    "a fresh copy matches the stored content, so nothing is pending yet");
            CompoundTags.put(handedOut, "charge", "full");
            Assert.equalInt(1, ItemStackNbtBridge.getPendingWrites(),
                    "the mutation waits in the pending list");

            // Reading the same stack flushes first, so a mod that mutates and reads back sees its
            // own change immediately; only other readers wait for the tick flush.
            Assert.isTrue(CompoundTags.contains(ItemStackNbtBridge.getTag(stack), "charge"),
                    "a read of the same stack sees the pending mutation");
            Assert.equalInt(0, ItemStackNbtBridge.getPendingWrites(),
                    "the read consumed the pending write");

            Object second = ItemStackNbtBridge.getOrCreateTag(stack);
            CompoundTags.put(second, "tier", "two");
            Assert.equalInt(1, ItemStackNbtBridge.flushDirty(), "one stack flushed");
            Object readBack = ItemStackNbtBridge.getTag(stack);
            Assert.isTrue(CompoundTags.contains(readBack, "tier"), "the flushed mutation is stored");
            Assert.isTrue(CompoundTags.contains(readBack, "charge"), "the earlier value survived");
            Assert.isTrue(CompoundTags.contains(readBack, "seed"), "the first value survived");
            Assert.equalInt(0, ItemStackNbtBridge.getPendingWrites(), "the pending list is empty");
        } finally {
            ItemStackNbtBridge.setStoreFactory(null);
            ItemStackNbtBridge.clear();
        }
    }

    /** A store that behaves like a 1.21.1 component: reads hand out copies. */
    private static final class CopyingStore implements TagStore {
        private final Map storage;
        private final Object key;

        CopyingStore(Map storage, Object key) {
            this.storage = storage;
            this.key = key;
        }

        public Object read() {
            return CompoundTags.copy(storage.get(key));
        }

        public void write(Object tag) {
            storage.put(key, tag);
        }

        public boolean isLive() {
            return false;
        }
    }

    // ------------------------------------------------------------------ registry lifecycle

    /** Records what the bridge asks NeoForge to do. */
    private static final class RecordingRegistrar implements RegistryLifecycleBridge.Registrar {
        final List<String> calls = new ArrayList<String>();
        final Object handle = new Object();
        private final boolean usable;

        RecordingRegistrar(boolean usable) {
            this.usable = usable;
        }

        public Object createDeferredRegister(String registryId, String modId) {
            calls.add("create " + registryId + " by " + modId);
            return usable ? handle : null;
        }

        public Object register(Object deferredRegister, String path, Supplier<Object> supplier) {
            calls.add("register " + path + " -> " + (supplier == null ? "null" : supplier.get()));
            return deferredRegister == handle ? supplier : null;
        }

        public boolean attach(Object deferredRegister, Object eventBus) {
            calls.add("attach " + (deferredRegister == handle) + " bus=" + (eventBus != null));
            return deferredRegister == handle;
        }
    }

    private static Supplier<Object> constant(final String value) {
        return new Supplier<Object>() {
            public Object get() {
                return value;
            }
        };
    }

    public static void testLegacyRegistrationGoesThroughADeferredRegister() {
        LegacyRegistryQueue.clear();
        RegistryLifecycleBridge.setRegistrar(null);
        RecordingRegistrar registrar = new RecordingRegistrar(true);
        RegistryLifecycleBridge.setRegistrar(registrar);
        RegistryLifecycleBridge.install(new Object(), "examplemod");
        try {
            LegacyRegistryQueue.enqueue("minecraft:item", "examplemod:rune", constant("rune-item"));
            int flushed = RegistryLifecycleBridge.flushLegacyRegistrations("minecraft:item", null);
            Assert.equalInt(1, flushed, "one entry registered");
            Assert.contains(registrar.calls.toString(), "create minecraft:item by examplemod",
                    "a deferred register was created for the registry, was: " + registrar.calls);
            Assert.contains(registrar.calls.toString(), "register rune -> rune-item",
                    "the namespace is stripped from the path, was: " + registrar.calls);
            Assert.contains(registrar.calls.toString(), "attach true", "the register was attached to the bus");
            Assert.equalInt(1, RegistryLifecycleBridge.getDeferredRegisterCount(),
                    "the register is reused for the registry");
            Assert.equalInt(1, RegistryLifecycleBridge.getFlushedCount(), "flush counter");
            Assert.equalInt(0, LegacyRegistryQueue.size("minecraft:item"), "the queue is drained");

            LegacyRegistryQueue.enqueue("minecraft:item", "examplemod:scroll", constant("scroll-item"));
            Assert.equalInt(1, RegistryLifecycleBridge.flushLegacyRegistrations("minecraft:item", null),
                    "second flush registers too");
            Assert.equalInt(1, RegistryLifecycleBridge.getDeferredRegisterCount(),
                    "no second register for the same registry");
        } finally {
            RegistryLifecycleBridge.setRegistrar(null);
            RegistryLifecycleBridge.clear();
            LegacyRegistryQueue.clear();
        }
    }

    public static void testFallbackRegistersWhenNoDeferredRegisterExists() {
        LegacyRegistryQueue.clear();
        RecordingRegistrar registrar = new RecordingRegistrar(false);
        RegistryLifecycleBridge.setRegistrar(registrar);
        final List<String> fallbackIds = new ArrayList<String>();
        try {
            LegacyRegistryQueue.enqueue("minecraft:block", "examplemod:runestone", constant("block"));
            LegacyRegistryQueue.enqueue("minecraft:block", "broken", null);
            int flushed = RegistryLifecycleBridge.flushLegacyRegistrations("minecraft:block",
                    new RegistryLifecycleBridge.PendingRegistration() {
                        public boolean register(LegacyRegistryQueue.Pending entry) {
                            fallbackIds.add(String.valueOf(entry.getId()));
                            return true;
                        }
                    });
            Assert.equalInt(1, flushed, "only the usable entry is registered");
            Assert.equalInt(1, fallbackIds.size(), "one entry went through the RegisterEvent fallback");
            Assert.equal("examplemod:runestone", fallbackIds.get(0), "the fallback sees the legacy id");
            Assert.equalInt(0, LegacyRegistryQueue.size("minecraft:block"),
                    "the queue is drained even when an entry cannot be registered");
        } finally {
            RegistryLifecycleBridge.setRegistrar(null);
            RegistryLifecycleBridge.clear();
            LegacyRegistryQueue.clear();
        }
    }

    public static void testCustomRegistryIsAcknowledgedNotPretended() {
        RegistryLifecycleBridge.clear();
        try {
            RegistryEvent.NewRegistry event = new RegistryEvent.NewRegistry("examplemod:rune_type");
            Assert.isNull(event.createRegistry("examplemod rune types"),
                    "createRegistry has no 1.21.1 equivalent and returns null");
            List<String> custom = RegistryLifecycleBridge.getCustomRegistries();
            Assert.equalInt(1, custom.size(), "the attempt is recorded once");
            Assert.equal("examplemod:rune_type", custom.get(0), "the legacy registry id is kept");
            Assert.isFalse(RegistryLifecycleBridge.acknowledgeCustomRegistry("examplemod:other"),
                    "acknowledging never claims success");
            Assert.equalInt(2, RegistryLifecycleBridge.getCustomRegistries().size(), "both are recorded");

            Assert.equal("rune", RegistryLifecycleBridge.pathOf("examplemod:rune"), "namespace stripped");
            Assert.equal("rune", RegistryLifecycleBridge.pathOf("rune"), "unqualified id passes through");
            Assert.isNull(RegistryLifecycleBridge.pathOf(null), "null id has no path");
        } finally {
            RegistryLifecycleBridge.clear();
        }
    }

    public static void testEventCarriesBothForgeAccessorShapes() {
        RegistryEvent.Register<String> register = new RegistryEvent.Register<String>("minecraft:item", null);
        Assert.equal("minecraft:item", register.getRegistryNameString(), "string accessor");
        Object name = register.getRegistryName();
        Assert.notNull(name, "1.20.1 accessor exists so a remapped call site links");
        Assert.equal("minecraft:item", String.valueOf(name),
                "outside a game the id string stands in for the ResourceLocation");
        Assert.isNull(register.getRegistry(), "no registry was supplied");
    }
}

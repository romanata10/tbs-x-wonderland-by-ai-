package dev.forgebridge.tests;

import dev.forgebridge.compat.registry.LegacyRegistry;
import dev.forgebridge.compat.registry.LegacyRegistryManager;
import dev.forgebridge.compat.registry.LegacyRegistryQueue;
import dev.forgebridge.compat.registry.NullLegacyRegistry;
import dev.forgebridge.compat.registry.RegistryAccess;
import dev.forgebridge.compat.registry.RegistryBridge;
import dev.forgebridge.compat.registry.RegistryEvent;
import dev.forgebridge.registries.DeferredRegisterBridge;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Registry shims, including the queue that holds registrations until the game is ready. */
public final class RegistryTests {

    private RegistryTests() {
    }

    public static void testRegistryAccessListsEveryBridgedRegistry() {
        String[] keys = RegistryAccess.knownKeys();
        Assert.equalInt(15, keys.length, "registry keys");
        Assert.equal("items", keys[0], "first key");
        Assert.equal("blocks", keys[1], "second key");
        Assert.equal("dataComponents", keys[14], "last key");
        for (int i = 0; i < keys.length; i++) {
            Object registry = RegistryAccess.resolve(keys[i]);
            Assert.notNull(registry, "registry resolvable: " + keys[i]);
            Assert.isTrue(registry instanceof LegacyRegistry, "registry implements the shim: " + keys[i]);
        }
    }

    public static void testOutsideTheGameEveryRegistryIsAnEmptyStub() {
        RegistryAccess.clear();
        LegacyRegistry<Object> items = RegistryAccess.items();
        Assert.isTrue(items instanceof NullLegacyRegistry, "no game: null registry stub");
        Assert.equalInt(0, items.size(), "empty registry");
        Assert.isNull(items.getValue("minecraft:diamond"), "no values");
        Assert.isTrue(items.getKeys().isEmpty(), "no keys");
        Assert.isTrue(items.getValues().isEmpty(), "no values collection");
        Assert.isFalse(items.containsKey("minecraft:diamond"), "containsKey false");
        // Forge returned a ResourceLocation here; the stub has none to give and logs once instead.
        Assert.isNull(items.getRegistryName(), "the stub has no ResourceLocation to report");
    }

    public static void testResolvedRegistriesAreCachedAndCleared() {
        RegistryAccess.clear();
        LegacyRegistry<Object> first = RegistryAccess.items();
        LegacyRegistry<Object> second = RegistryAccess.items();
        Assert.isTrue(first == second, "same instance from the cache");
        RegistryAccess.clear();
        LegacyRegistry<Object> third = RegistryAccess.items();
        Assert.isFalse(first == third, "clear() drops the cache");
    }

    public static void testLegacyFieldNamesMapToBridgeMethods() {
        Assert.equal("items", RegistryAccess.methodForLegacyField("ITEMS"), "ITEMS");
        Assert.equal("blocks", RegistryAccess.methodForLegacyField("BLOCKS"), "BLOCKS");
        Assert.equal("entityTypes", RegistryAccess.methodForLegacyField("ENTITIES"), "ENTITIES");
        Assert.equal("mobEffects", RegistryAccess.methodForLegacyField("MOB_EFFECTS"), "MOB_EFFECTS");
        Assert.equal("particleTypes", RegistryAccess.methodForLegacyField("PARTICLES"), "PARTICLES");
        Assert.equal("menuTypes", RegistryAccess.methodForLegacyField("MENU_TYPES"), "MENU_TYPES");
    }

    public static void testRegistryIdsRoundTripToBridgeKeys() {
        Assert.equal("items", RegistryAccess.keyForRegistryId("minecraft:item"), "item registry");
        Assert.equal("blocks", RegistryAccess.keyForRegistryId("minecraft:block"), "block registry");
        Assert.equal("entityTypes", RegistryAccess.keyForRegistryId("minecraft:entity_type"), "entity registry");
        Assert.equal(null, RegistryAccess.keyForRegistryId(null), "null id");
        Assert.equal(null, RegistryAccess.registryObject("items"), "no registry object outside the game");
        Assert.equal(null, RegistryAccess.registryKey("items"), "no registry key outside the game");
    }

    public static void testRegistrationQueueHoldsEntriesUntilTheGameIsReady() {
        LegacyRegistryQueue.clear();
        Supplier<Object> supplier = new Supplier<Object>() {
            public Object get() {
                return "registered-object";
            }
        };
        LegacyRegistryQueue.enqueue("minecraft:item", "examplemod:rune", supplier);
        LegacyRegistryQueue.enqueue("minecraft:item", "examplemod:crystal", supplier);
        LegacyRegistryQueue.enqueue("minecraft:block", "examplemod:rune_block", supplier);

        Map<String, Integer> snapshot = LegacyRegistryQueue.snapshot();
        Assert.equalInt(2, snapshot.size(), "two registry ids tracked");
        Assert.equal(Integer.valueOf(2), snapshot.get("minecraft:item"), "two items queued");
        Assert.equal(Integer.valueOf(1), snapshot.get("minecraft:block"), "one block queued");

        List<LegacyRegistryQueue.Pending> drained = LegacyRegistryQueue.drain("minecraft:item");
        Assert.equalInt(2, drained.size(), "item queue drained");
        LegacyRegistryQueue.Pending first = (LegacyRegistryQueue.Pending) drained.get(0);
        Assert.equal("examplemod:rune", first.getId(), "queued id preserved");
        Assert.equal("registered-object", first.getSupplier().get(), "supplier handed over");
        Assert.equal("queued", first.getSource(), "source recorded");
        Assert.equalInt(0, LegacyRegistryQueue.size("minecraft:item"), "queue is empty after draining");
        Assert.equalInt(0, LegacyRegistryQueue.drain("minecraft:item").size(), "draining twice is harmless");
        LegacyRegistryQueue.clear();
        Assert.equalInt(0, LegacyRegistryQueue.snapshot().size(), "clear() empties everything");
    }

    public static void testRegistryManagerFallsBackToAStub() {
        RegistryAccess.clear();
        LegacyRegistry<Object> registry = LegacyRegistryManager.getRegistry("minecraft:item");
        Assert.notNull(registry, "manager always returns a registry");
        Assert.isTrue(registry instanceof LegacyRegistry, "shim type");
    }

    public static void testRegistryEventSubclassesCarryTheirRegistry() {
        LegacyRegistry<Object> registry = RegistryAccess.items();
        RegistryEvent.Register register = new RegistryEvent.Register("minecraft:item", registry);
        Assert.equal("minecraft:item", register.getRegistryNameString(), "registry name");
        Assert.isTrue(register.getRegistry() == registry, "registry handle");
        RegistryEvent.NewRegistry created = new RegistryEvent.NewRegistry("examplemod:custom");
        Assert.equal("examplemod:custom", created.getRegistryNameString(), "new registry name");
        Assert.isTrue(created instanceof RegistryEvent, "NewRegistry is a RegistryEvent");
    }

    public static void testBridgeRefusesToInstallWithoutNeoForge() {
        Assert.isFalse(RegistryBridge.install(null), "no RegisterEvent class: install reports failure");
        Assert.isFalse(RegistryBridge.isInstalled(), "bridge not marked as installed");
        Assert.equalInt(0, RegistryBridge.getEventsSeen(), "no events seen");
    }

    public static void testDeferredRegisterCreateFailsGracefullyWithoutTheGame() {
        Assert.isNull(DeferredRegisterBridge.create(null, "examplemod"), "null registry name");
        Assert.isNull(DeferredRegisterBridge.create("minecraft:item", "examplemod"),
                "no NeoForge on the class path: returns null instead of throwing");
        Assert.isNull(DeferredRegisterBridge.register(null, "rune", null), "register on a null handle");
        Assert.isNull(DeferredRegisterBridge.registerSimple(null, "rune", null), "registerSimple on a null handle");
    }

    public static void testNullRegistryStubImplementsTheFullInterface() {
        NullLegacyRegistry registry = new NullLegacyRegistry("items");
        Assert.equalInt(0, registry.size(), "size");
        Assert.isFalse(registry.containsKey("x"), "containsKey");
        Assert.isFalse(registry.containsValue("x"), "containsValue");
        Assert.isNull(registry.getValue("x"), "getValue");
        Assert.isNull(registry.getKey("x"), "getKey");
        Set<Object> keys = registry.getKeys();
        Assert.notNull(keys, "keys set");
        Assert.equalInt(0, keys.size(), "keys empty");
    }
}

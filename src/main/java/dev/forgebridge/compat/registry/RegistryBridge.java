package dev.forgebridge.compat.registry;

import dev.forgebridge.BridgeConstants;
import dev.forgebridge.events.ConsumerProxy;
import dev.forgebridge.events.LegacyDispatcher;
import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

import java.util.List;
import java.util.function.Supplier;

/**
 * Bridges NeoForge's {@code RegisterEvent} to Forge's per-registry
 * {@code RegistryEvent.Register}.
 *
 * <p>Sequence for one registry:</p>
 * <ol>
 *   <li>NeoForge fires {@code RegisterEvent} on the mod event bus;</li>
 *   <li>the bridge identifies the registry ({@code event.getRegistryKey().location()});</li>
 *   <li>a synthetic {@code RegistryEvent.Register} is posted to legacy listeners, which get a
 *       {@link LegacyRegistry} adapter so {@code event.getRegistry().register(...)} works;</li>
 *   <li>everything queued in {@link LegacyRegistryQueue} is flushed through
 *       {@code RegisterEvent.register(ResourceKey, Supplier)}.</li>
 * </ol>
 */
public final class RegistryBridge {

    private static final String REGISTER_EVENT = "net.neoforged.neoforge.registries.RegisterEvent";
    private static final String RESOURCE_KEY = "net.minecraft.resources.ResourceKey";

    private static boolean installed;
    private static int eventsSeen;
    private static int registrationsFlushed;

    private RegistryBridge() {
    }

    /** Attaches the bridge to a mod event bus. @return true when the NeoForge event was found */
    public static boolean install(Object modEventBus) {
        if (installed) {
            return true;
        }
        Class<?> eventType = SafeReflect.findClass(REGISTER_EVENT);
        if (eventType == null) {
            BridgeLog.warn("RegisterEvent is not present on this class path; registry bridging disabled");
            return false;
        }
        if (modEventBus == null) {
            BridgeLog.warn("No mod event bus available; registry bridging disabled");
            return false;
        }
        Object consumer = ConsumerProxy.create(eventType.getClassLoader(), "RegisterEvent", new ConsumerProxy.Callback() {
            public void accept(Object event) {
                onRegisterEvent(event);
            }
        });
        if (consumer == null) {
            return false;
        }
        Object result = SafeReflect.invoke(modEventBus, "addListener", eventType, consumer);
        if (result == null) {
            result = SafeReflect.invoke(modEventBus, "addListener",
                    dev.forgebridge.events.EventPriorityCompat.toNeoForgeConstant(dev.forgebridge.events.EventPriorityCompat.NORMAL),
                    eventType, consumer);
        }
        installed = true;
        // The lifecycle bridge needs the same bus: it turns queued 1.20.1 registrations into
        // DeferredRegister entries and attaches them here.
        RegistryLifecycleBridge.install(modEventBus, BridgeConstants.MOD_ID);
        BridgeLog.info("Registry bridge attached to the mod event bus");
        return true;
    }

    /** Called for every NeoForge RegisterEvent. */
    public static void onRegisterEvent(final Object event) {
        eventsSeen++;
        final Object registryKey = SafeReflect.invoke(event, "getRegistryKey");
        String registryId = registryIdOf(registryKey);
        if (registryId == null) {
            BridgeLog.warn("RegisterEvent without a resolvable registry key; ignoring");
            return;
        }
        String bridgeKey = RegistryAccess.keyForRegistryId(registryId);
        LegacyRegistry<Object> legacyRegistry = bridgeKey == null
                ? new NullLegacyRegistry(registryId)
                : RegistryAccess.resolve(bridgeKey);

        // 1.20.1 shape first: mods that register imperatively get their Register event.
        RegistryEvent.Register<Object> synthetic = new RegistryEvent.Register<Object>(registryId, legacyRegistry);
        int listeners = LegacyDispatcher.post(synthetic);
        BridgeLog.debug("RegistryEvent.Register for " + registryId + " reached " + listeners + " legacy listener(s)");

        // Then everything that was queued (imperative registrations and deferred entries alike)
        // is flushed: through a DeferredRegister when NeoForge allows it, through the event
        // itself otherwise.
        RegistryLifecycleBridge.PendingRegistration fallback = new RegistryLifecycleBridge.PendingRegistration() {
            public boolean register(LegacyRegistryQueue.Pending entry) {
                Object key = createResourceKey(registryKey, entry.getId());
                Object value = entry.getSupplier() == null ? null : entry.getSupplier().get();
                if (key == null || value == null) {
                    return false;
                }
                return registerDirectly(event, key, value);
            }
        };
        registrationsFlushed += RegistryLifecycleBridge.flushLegacyRegistrations(registryId, fallback);
    }

    private static boolean registerDirectly(Object event, Object key, Object value) {
        final Object captured = value;
        Supplier<Object> supplier = new Supplier<Object>() {
            public Object get() {
                return captured;
            }
        };
        Object result = SafeReflect.invoke(event, "register", key, supplier);
        if (result == null) {
            BridgeLog.warn("RegisterEvent.register failed for " + key);
            return false;
        }
        return true;
    }

    /** {@code ResourceKey.create(registryKey, location)} via reflection. */
    public static Object createResourceKey(Object registryKey, Object resourceLocation) {
        Class<?> resourceKey = SafeReflect.findClass(RESOURCE_KEY);
        if (resourceKey == null) {
            return null;
        }
        java.lang.reflect.Method[] methods = resourceKey.getMethods();
        for (int i = 0; i < methods.length; i++) {
            java.lang.reflect.Method method = methods[i];
            if (!"create".equals(method.getName()) || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 2) {
                continue;
            }
            if (!types[0].isInstance(registryKey) || !types[1].isInstance(resourceLocation)) {
                continue;
            }
            try {
                return method.invoke(null, registryKey, resourceLocation);
            } catch (Throwable error) {
                BridgeLog.debug("ResourceKey.create failed: " + error);
            }
        }
        return null;
    }

    public static String registryIdOf(Object registryKey) {
        if (registryKey == null) {
            return null;
        }
        Object location = SafeReflect.invoke(registryKey, "location");
        if (location == null) {
            location = SafeReflect.invoke(registryKey, "registry");
        }
        return location == null ? null : String.valueOf(location);
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static int getEventsSeen() {
        return eventsSeen;
    }

    public static int getRegistrationsFlushed() {
        return registrationsFlushed;
    }
}

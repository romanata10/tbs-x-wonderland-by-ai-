package dev.forgebridge.registries;

import dev.forgebridge.compat.registry.RegistryAccess;
import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

/**
 * Target of the {@code DeferredRegister.create} call-site redirect.
 *
 * <p>Forge 1.20.1: {@code DeferredRegister.create(ResourceLocation, String)}<br>
 * NeoForge 21.1: {@code DeferredRegister.create(ResourceKey<? extends Registry<T>>, String)}</p>
 *
 * <p>The signatures differ in the first parameter type, so renaming alone produces a
 * {@code NoSuchMethodError}. The remapper rewrites the call to {@link #create(Object, String)}
 * here, which turns the {@code ResourceLocation} into the matching {@code ResourceKey} and then
 * calls the real factory reflectively. The caller's bytecode keeps its
 * {@code DeferredRegister} type, which the emitted {@code CHECKCAST} restores for the
 * verifier.</p>
 */
public final class DeferredRegisterBridge {

    private static final String DEFERRED_REGISTER = "net.neoforged.neoforge.registries.DeferredRegister";

    private DeferredRegisterBridge() {
    }

    /**
     * @param registryName either a {@code ResourceLocation} (Forge form) or a
     *                     {@code Registry} instance (the other Forge overload)
     * @param modId        the owning mod id
     * @return a real NeoForge {@code DeferredRegister}, or {@code null} when the game is not
     *         running (the caller will fail with a bridge-flavoured error message)
     */
    public static Object create(Object registryName, String modId) {
        Class<?> deferredRegister = SafeReflect.findClass(DEFERRED_REGISTER);
        if (deferredRegister == null) {
            BridgeLog.errorOnce("no-deferred-register",
                    "NeoForge DeferredRegister is not on the class path; deferred registrations from legacy mods cannot be created");
            return null;
        }
        if (registryName == null) {
            BridgeLog.error("DeferredRegister.create called with a null registry name");
            return null;
        }
        String registryId = String.valueOf(registryName);
        Object registryKey = null;
        String path = registryId;
        if (path.indexOf(':') >= 0) {
            path = path.substring(path.indexOf(':') + 1);
        }
        // Form 1: the caller passed a ResourceLocation.
        String bridgeKey = RegistryAccess.keyForRegistryId(registryId);
        if (bridgeKey != null) {
            registryKey = RegistryAccess.registryKey(bridgeKey);
        }
        // Form 2: the caller passed a Registry object; ask it for its key.
        if (registryKey == null) {
            Object fromRegistry = SafeReflect.invoke(registryName, "key");
            if (fromRegistry != null) {
                registryKey = fromRegistry;
            }
        }
        if (registryKey == null) {
            BridgeLog.errorOnce("no-registry-key-" + path,
                    "Could not resolve a ResourceKey for registry '" + registryId
                            + "'; the legacy mod's DeferredRegister will not work");
            return null;
        }
        Object result = invokeCreate(deferredRegister, registryKey, modId);
        if (result != null) {
            BridgeLog.debug("DeferredRegister created for " + registryId + " (" + modId + ")");
        }
        return result;
    }

    private static Object invokeCreate(Class<?> deferredRegister, Object registryKey, String modId) {
        Method[] methods = deferredRegister.getMethods();
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            if (!"create".equals(method.getName()) || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 2 || types[1] != String.class) {
                continue;
            }
            if (!types[0].isInstance(registryKey)) {
                continue;
            }
            try {
                return method.invoke(null, registryKey, modId);
            } catch (Throwable error) {
                BridgeLog.error("DeferredRegister.create failed", error);
            }
        }
        BridgeLog.errorOnce("deferred-create-signature",
                "No DeferredRegister.create(ResourceKey, String) found; NeoForge API may have changed");
        return null;
    }

    /** Reflective {@code register(name, supplier)} for bridge code that has no compile-time type. */
    public static Object register(Object deferredRegister, String name, Supplier<?> supplier) {
        if (deferredRegister == null) {
            return null;
        }
        return SafeReflect.invoke(deferredRegister, "register", name, supplier);
    }

    /** Reflective {@code registerSimple} convenience used by mods that skip the builder. */
    public static Object registerSimple(Object deferredRegister, String name, Supplier<?> supplier) {
        if (deferredRegister == null) {
            return null;
        }
        Object result = SafeReflect.invoke(deferredRegister, "register", name, supplier);
        return result == null ? SafeReflect.invoke(deferredRegister, "registerSimple", name, supplier) : result;
    }

    /** Attaches the deferred register to a mod event bus (Forge's register(IEventBus)). */
    public static void attach(Object deferredRegister, Object eventBus) {
        if (deferredRegister == null || eventBus == null) {
            return;
        }
        SafeReflect.invoke(deferredRegister, "register", eventBus);
    }
}

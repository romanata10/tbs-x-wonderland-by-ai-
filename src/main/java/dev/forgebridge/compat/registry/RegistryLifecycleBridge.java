package dev.forgebridge.compat.registry;

import dev.forgebridge.BridgeConstants;
import dev.forgebridge.registries.DeferredRegisterBridge;
import dev.forgebridge.util.BridgeLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Translates the 1.20.1 Forge registry life cycle into NeoForge 21.1 {@code DeferredRegister}s.
 *
 * <p>The two mods are not from the same era, and their registration code does not look alike:</p>
 *
 * <ul>
 *   <li>a <b>1.20.1 Forge</b> mod calls {@code DeferredRegister.create(...)} and
 *       {@code ITEMS.register("rune", ...)}, or registers late through
 *       {@code RegistryEvent.Register};</li>
 *   <li>a <b>1.21.1 NeoForge</b> mod uses {@code DeferredRegister} only, and everything is
 *       expected to be declared before {@code RegisterEvent} fires.</li>
 * </ul>
 *
 * <p>So a legacy registration has to happen in two steps. Entries queued by the translated mod
 * go into a {@code DeferredRegister} for their registry as soon as the bridge knows about them
 * (that is the only shape NeoForge supports), and when {@code RegisterEvent} fires the remaining
 * queue is drained: through the deferred register when one exists, through
 * {@code RegisterEvent.register(...)} when it does not. Either way the legacy
 * {@code RegistryEvent.Register} listeners still run, against a {@link LegacyRegistry} adapter,
 * so mods that register imperatively keep working.</p>
 *
 * <p>{@code NewRegistryEvent} (custom registries) cannot be translated: NeoForge registers datapack
 * registries from a {@code RegistryBuilder} that the bridge would have to reproduce, and a
 * half-built registry is worse than a clear diagnostic. Those events are recorded and reported.</p>
 */
public final class RegistryLifecycleBridge {

    /** The two operations the bridge needs from NeoForge; replaceable so it can be tested. */
    public interface Registrar {
        /** A {@code DeferredRegister} for the given registry id, or {@code null} if unavailable. */
        Object createDeferredRegister(String registryId, String modId);

        /** Registers one value, keyed by its path, on a deferred register. */
        Object register(Object deferredRegister, String path, Supplier<Object> supplier);

        /** Attaches a deferred register to the mod event bus. */
        boolean attach(Object deferredRegister, Object eventBus);
    }

    private static final Registrar DEFAULT = new DefaultRegistrar();

    private static final Map<String, Object> DEFERRED = new LinkedHashMap<String, Object>();
    private static final List<String> CUSTOM_REGISTRIES = new ArrayList<String>();
    private static volatile Registrar registrar = DEFAULT;
    private static String modId = BridgeConstants.MOD_ID;
    private static Object eventBus;
    private static int flushed;

    private RegistryLifecycleBridge() {
    }

    /** Default registrar: the reflective {@code DeferredRegister} support. */
    static final class DefaultRegistrar implements Registrar {

        public Object createDeferredRegister(String registryId, String modId) {
            String bridgeKey = RegistryAccess.keyForRegistryId(registryId);
            if (bridgeKey == null) {
                return null;
            }
            Object registryKey = RegistryAccess.registryKey(bridgeKey);
            if (registryKey == null) {
                return null;
            }
            return DeferredRegisterBridge.create(registryKey, modId);
        }

        public Object register(Object deferredRegister, String path, Supplier<Object> supplier) {
            return DeferredRegisterBridge.register(deferredRegister, path, supplier);
        }

        public boolean attach(Object deferredRegister, Object eventBus) {
            if (deferredRegister == null || eventBus == null) {
                return false;
            }
            DeferredRegisterBridge.attach(deferredRegister, eventBus);
            return true;
        }
    }

    /** Remembers the bus and mod id; safe to call more than once. */
    public static synchronized void install(Object bus, String ownerModId) {
        eventBus = bus;
        if (ownerModId != null && !ownerModId.isEmpty()) {
            modId = ownerModId;
        }
    }

    public static String getModId() {
        return modId;
    }

    /**
     * The deferred register for a registry id, created and attached on first use.
     *
     * @return {@code null} when NeoForge's {@code DeferredRegister} is not available
     */
    public static synchronized Object deferredRegisterFor(String registryId) {
        if (registryId == null) {
            return null;
        }
        Object existing = DEFERRED.get(registryId);
        if (existing != null) {
            return existing;
        }
        Object created = registrar.createDeferredRegister(registryId, modId);
        if (created == null) {
            return null;
        }
        DEFERRED.put(registryId, created);
        if (eventBus != null && registrar.attach(created, eventBus)) {
            BridgeLog.debug("DeferredRegister for " + registryId + " attached to the mod bus");
        }
        return created;
    }

    /**
     * Registers every queued entry of one registry, preferring the deferred register.
     *
     * @param fallback invoked for entries that could not go through a deferred register; may be
     *                 {@code null}
     * @return number of entries registered
     */
    public static int flushLegacyRegistrations(String registryId, PendingRegistration fallback) {
        List<LegacyRegistryQueue.Pending> queue = LegacyRegistryQueue.drain(registryId);
        if (queue.isEmpty()) {
            return 0;
        }
        Object deferred = deferredRegisterFor(registryId);
        int registered = 0;
        for (int i = 0; i < queue.size(); i++) {
            LegacyRegistryQueue.Pending entry = (LegacyRegistryQueue.Pending) queue.get(i);
            String path = pathOf(entry.getId());
            Supplier<Object> supplier = entry.getSupplier();
            if (path == null || supplier == null) {
                BridgeLog.warn("Dropping legacy registration '" + entry.getId() + "' for registry '"
                        + registryId + "': " + (path == null ? "no usable path" : "no value supplier"));
                continue;
            }
            boolean done = false;
            if (deferred != null) {
                done = registrar.register(deferred, path, supplier) != null;
                if (!done) {
                    BridgeLog.warn("DeferredRegister.register('" + path + "') failed for registry '"
                            + registryId + "'");
                }
            }
            if (!done && fallback != null) {
                done = fallback.register(entry);
            }
            if (done) {
                registered++;
            }
        }
        flushed += registered;
        return registered;
    }

    /**
     * Fallback used when no deferred register can be created for a registry: the entry is
     * registered directly on the {@code RegisterEvent}.
     */
    public interface PendingRegistration {
        boolean register(LegacyRegistryQueue.Pending entry);
    }

    /** {@code examplemod:rune} becomes {@code rune}: deferred registers own the namespace. */
    public static String pathOf(Object id) {
        if (id == null) {
            return null;
        }
        String text = String.valueOf(id);
        int colon = text.indexOf(':');
        String path = colon < 0 ? text : text.substring(colon + 1);
        return path.isEmpty() ? null : path;
    }

    /**
     * Records a 1.20.1 custom registry that cannot be translated.
     *
     * @return always {@code false}: there is no silent success path here
     */
    public static synchronized boolean acknowledgeCustomRegistry(String registryId) {
        if (registryId == null) {
            return false;
        }
        if (!CUSTOM_REGISTRIES.contains(registryId)) {
            CUSTOM_REGISTRIES.add(registryId);
        }
        BridgeLog.warn("Custom registry '" + registryId + "' cannot be translated: 1.20.1 created registries"
                + " through NewRegistryEvent, 1.21.1 builds them from a data pack definition."
                + " The mod's entries for it will not be registered.");
        return false;
    }

    public static synchronized List<String> getCustomRegistries() {
        return new ArrayList<String>(CUSTOM_REGISTRIES);
    }

    public static synchronized int getFlushedCount() {
        return flushed;
    }

    public static synchronized int getDeferredRegisterCount() {
        return DEFERRED.size();
    }

    /** Replaces the NeoForge-facing implementation (tests, embedded use). */
    public static synchronized void setRegistrar(Registrar replacement) {
        registrar = replacement == null ? DEFAULT : replacement;
        clear();
    }

    public static synchronized void clear() {
        DEFERRED.clear();
        CUSTOM_REGISTRIES.clear();
        flushed = 0;
        eventBus = null;
    }
}

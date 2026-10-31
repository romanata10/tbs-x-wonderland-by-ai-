package dev.forgebridge.compat.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Holds registrations a legacy mod queued outside of a registration window.
 *
 * <p>Forge mods that use {@code IForgeRegistry.register(...)} directly (rather than
 * {@code DeferredRegister}) can only do so while the registry event is being fired. Because
 * the bridge fires its synthetic {@code RegistryEvent.Register} at exactly that moment, the
 * {@link ReflectiveLegacyRegistry#register} path works. Anything registered before or after
 * that window is parked here and flushed at the next opportunity instead of throwing.</p>
 */
public final class LegacyRegistryQueue {

    /** One queued registration. */
    public static final class Pending {
        private final Object id;
        private final Supplier<Object> supplier;
        private final String source;

        Pending(Object id, Supplier<Object> supplier, String source) {
            this.id = id;
            this.supplier = supplier;
            this.source = source;
        }

        public Object getId() {
            return id;
        }

        public Supplier<Object> getSupplier() {
            return supplier;
        }

        public String getSource() {
            return source;
        }
    }

    private static final Map<String, List<Pending>> QUEUES = new LinkedHashMap<String, List<Pending>>();

    private LegacyRegistryQueue() {
    }

    public static synchronized void enqueue(String registryId, Object id, Supplier<Object> supplier) {
        List<Pending> queue = (List<Pending>) QUEUES.get(registryId);
        if (queue == null) {
            queue = new ArrayList<Pending>();
            QUEUES.put(registryId, queue);
        }
        queue.add(new Pending(id, supplier, "queued"));
    }

    public static synchronized List<Pending> drain(String registryId) {
        List<Pending> queue = (List<Pending>) QUEUES.remove(registryId);
        return queue == null ? new ArrayList<Pending>() : queue;
    }

    public static synchronized int size(String registryId) {
        List<Pending> queue = (List<Pending>) QUEUES.get(registryId);
        return queue == null ? 0 : queue.size();
    }

    public static synchronized Map<String, Integer> snapshot() {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (Object element : QUEUES.entrySet()) {
            Map.Entry entry = (Map.Entry) element;
            result.put(String.valueOf(entry.getKey()), Integer.valueOf(((List) entry.getValue()).size()));
        }
        return result;
    }

    public static synchronized void clear() {
        QUEUES.clear();
    }
}

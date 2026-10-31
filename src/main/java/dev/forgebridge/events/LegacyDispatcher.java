package dev.forgebridge.events;

import dev.forgebridge.util.BridgeLog;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, ordered event dispatcher used for legacy events that no longer exist on the
 * NeoForge bus ({@code RegistryEvent.Register}, {@code FMLPreInitializationEvent}, ...).
 *
 * <p>Kept deliberately small: because the bridge synthesises these events itself, it only
 * needs listener bookkeeping and priority ordering, not the full cancellation machinery.
 * Events that <em>do</em> exist in NeoForge (all {@code net.neoforged.bus.api.Event}
 * subclasses) are handed to the real bus instead - see {@link EventBusBridge}.</p>
 */
public final class LegacyDispatcher {

    /** One registered listener method. */
    public static final class Entry {
        private final Class<?> eventType;
        private final Object target;
        private final Method method;
        private final int priority;

        Entry(Class<?> eventType, Object target, Method method, int priority) {
            this.eventType = eventType;
            this.target = target;
            this.method = method;
            this.priority = priority;
            method.setAccessible(true);
        }

        public Class<?> getEventType() {
            return eventType;
        }

        int getPriority() {
            return priority;
        }

        boolean matches(Object event) {
            return event != null && eventType.isAssignableFrom(event.getClass());
        }

        void dispatch(Object event) {
            try {
                method.invoke(target, event);
            } catch (InvocationTargetException error) {
                BridgeLog.error("Listener " + target.getClass().getName() + "." + method.getName()
                        + " threw while handling " + event.getClass().getSimpleName(), error.getCause());
            } catch (Throwable error) {
                BridgeLog.error("Could not dispatch " + event.getClass().getSimpleName()
                        + " to " + target.getClass().getName() + "." + method.getName(), error);
            }
        }
    }

    private static final Map<Class<?>, List<Entry>> LISTENERS = new LinkedHashMap<Class<?>, List<Entry>>();

    private LegacyDispatcher() {
    }

    public static synchronized void addListener(Class<?> eventType, Object target, Method method, int priority) {
        List<Entry> entries = (List<Entry>) LISTENERS.get(eventType);
        if (entries == null) {
            entries = new ArrayList<Entry>();
            LISTENERS.put(eventType, entries);
        }
        entries.add(new Entry(eventType, target, method, priority));
        sortByPriority(entries);
        BridgeLog.debug("Legacy listener " + target.getClass().getName() + "." + method.getName()
                + " -> " + eventType.getSimpleName() + " (priority " + priority + ")");
    }

    /** @return the number of listeners that were invoked */
    public static int post(Object event) {
        if (!(event instanceof dev.forgebridge.events.BridgeEvent)) {
            // still allowed: the bridge may replay plain objects for compatibility shims
        }
        List<Entry> snapshot = new ArrayList<Entry>();
        synchronized (LegacyDispatcher.class) {
            for (List<Entry> entries : LISTENERS.values()) {
                for (int i = 0; i < entries.size(); i++) {
                    Entry entry = (Entry) entries.get(i);
                    if (entry.matches(event)) {
                        snapshot.add(entry);
                    }
                }
            }
        }
        sortByPriority(snapshot);
        for (int i = 0; i < snapshot.size(); i++) {
            Entry entry = (Entry) snapshot.get(i);
            entry.dispatch(event);
        }
        return snapshot.size();
    }

    /** Insertion sort by priority: listener lists are tiny, and this avoids a Comparator. */
    private static void sortByPriority(List<Entry> entries) {
        for (int i = 1; i < entries.size(); i++) {
            Entry current = (Entry) entries.get(i);
            int j = i - 1;
            while (j >= 0 && ((Entry) entries.get(j)).getPriority() > current.getPriority()) {
                entries.set(j + 1, entries.get(j));
                j--;
            }
            entries.set(j + 1, current);
        }
    }

    public static synchronized int listenerCount() {
        int total = 0;
        for (List<Entry> entries : LISTENERS.values()) {
            total += entries.size();
        }
        return total;
    }

    public static synchronized void clear() {
        LISTENERS.clear();
    }

    public static synchronized List<String> describeListeners() {
        List<String> result = new ArrayList<String>();
        for (Object element : LISTENERS.entrySet()) {
            Map.Entry entry = (Map.Entry) element;
            Class<?> eventType = (Class<?>) entry.getKey();
            List entries = (List) entry.getValue();
            for (int i = 0; i < entries.size(); i++) {
                Entry listener = (Entry) entries.get(i);
                result.add(eventType.getSimpleName() + " <- " + listener.target.getClass().getName()
                        + "." + listener.method.getName());
            }
        }
        return result;
    }
}

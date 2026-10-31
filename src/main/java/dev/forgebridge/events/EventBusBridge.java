package dev.forgebridge.events;

import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes listeners to the right bus.
 *
 * <p>After bytecode translation a legacy mod's {@code @SubscribeEvent} methods carry
 * NeoForge's annotation, but their parameter types split into two groups:</p>
 *
 * <ul>
 *   <li>events that exist in NeoForge - registered on the real bus through
 *       {@code addListener(priority, type, Consumer)}, using a dynamic proxy so the bridge
 *       needs no compile-time dependency on the bus;</li>
 *   <li>events the bridge synthesises ({@code RegistryEvent.Register}, lifecycle events) -
 *       registered on {@link LegacyDispatcher}.</li>
 * </ul>
 *
 * <p>Doing it per method rather than calling {@code register(target)} matters: a single
 * class often mixes both groups, and handing it to NeoForge's bus would fail on the
 * synthetic ones with an "invalid parameter type" error.</p>
 */
public final class EventBusBridge {

    private static final String NEO_BUS_SUBSCRIBE = "net.neoforged.bus.api.SubscribeEvent";
    private static final String LEGACY_SUBSCRIBE = "net.minecraftforge.eventbus.api.SubscribeEvent";
    private static final String NEO_EVENT = "net.neoforged.bus.api.Event";

    private static int registered;


    private EventBusBridge() {
    }

    /** The game event bus ({@code NeoForge.EVENT_BUS}), or {@code null} outside a game. */
    public static Object gameBus() {
        Class<?> neoForge = SafeReflect.findClass("net.neoforged.neoforge.common.NeoForge");
        if (neoForge == null) {
            return null;
        }
        return SafeReflect.getStatic(neoForge, "EVENT_BUS");
    }

    public static Object modBus(Object modContainer) {
        Object bus = SafeReflect.invoke(modContainer, "getEventBus");
        return bus;
    }

    /** Subscribes every annotated method of {@code target}. @return number of listeners added */
    public static int subscribe(Object target) {
        return subscribe(target, gameBus());
    }

    public static int subscribe(Object target, Object bus) {
        if (target == null) {
            return 0;
        }
        int added = 0;
        Method[] methods = target.getClass().getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Annotation subscribe = findSubscribeAnnotation(method);
            if (subscribe == null) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 1) {
                BridgeLog.warn("@SubscribeEvent method " + target.getClass().getName() + "."
                        + method.getName() + " must take exactly one parameter; skipping");
                continue;
            }
            int priority = EventPriorityCompat.fromAnnotation(subscribe);
            Class<?> eventType = parameters[0];
            if (isNeoForgeEvent(eventType)) {
                if (registerOnNeoBus(bus, target, method, eventType, priority)) {
                    added++;
                }
            } else {
                LegacyDispatcher.addListener(eventType, target, method, priority);
                added++;
            }
        }
        registered += added;
        BridgeLog.debug("Subscribed " + added + " listener method(s) of " + target.getClass().getName());
        return added;
    }

    public static boolean isNeoForgeEvent(Class<?> type) {
        Class<?> base = SafeReflect.findClass(NEO_EVENT);
        return base != null && base.isAssignableFrom(type);
    }

    private static Annotation findSubscribeAnnotation(Method method) {
        Annotation[] annotations = method.getDeclaredAnnotations();
        for (int i = 0; i < annotations.length; i++) {
            String name = annotations[i].annotationType().getName();
            if (NEO_BUS_SUBSCRIBE.equals(name) || LEGACY_SUBSCRIBE.equals(name)
                    || "SubscribeEvent".equals(annotations[i].annotationType().getSimpleName())) {
                return annotations[i];
            }
        }
        return null;
    }

    private static boolean registerOnNeoBus(Object bus, Object target, Method method, Class<?> eventType, int priority) {
        if (bus == null) {
            BridgeLog.once("no-bus-" + target.getClass().getName(),
                    "No NeoForge event bus available; listener " + target.getClass().getName()
                            + "." + method.getName() + " cannot be registered yet");
            return false;
        }
        Object consumer = consumerFor(target, method);
        Object priorityConstant = EventPriorityCompat.toNeoForgeConstant(priority);
        Object result = priorityConstant == null
                ? SafeReflect.invoke(bus, "addListener", eventType, consumer)
                : SafeReflect.invoke(bus, "addListener", priorityConstant, eventType, consumer);
        if (result == null && !hasAddListener(bus)) {
            Object fallback = SafeReflect.invoke(bus, "register", target);
            if (fallback == null) {
                BridgeLog.warn("Could not register " + target.getClass().getName() + " on the event bus");
                return false;
            }
        }
        return true;
    }

    private static boolean hasAddListener(Object bus) {
        return SafeReflect.findMethod(bus.getClass(), "addListener", 3) != null
                || SafeReflect.findMethod(bus.getClass(), "addListener", 2) != null;
    }

    /**
     * Wraps a listener method in a {@code java.util.function.Consumer} that the NeoForge bus
     * accepts. A dynamic proxy avoids referencing NeoForge's functional interfaces at build
     * time and works with both the 2- and 3-argument {@code addListener} overloads.
     */
    private static Object consumerFor(final Object target, final Method method) {
        ClassLoader loader = target.getClass().getClassLoader();
        if (loader == null) {
            loader = EventBusBridge.class.getClassLoader();
        }
        InvocationHandler handler = new InvocationHandler() {
            public Object invoke(Object proxy, Method called, Object[] args) {
                String name = called.getName();
                if ("accept".equals(name) && args != null && args.length == 1) {
                    try {
                        method.setAccessible(true);
                        method.invoke(target, args[0]);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        BridgeLog.error("Listener threw: " + target.getClass().getName() + "." + method.getName(),
                                error.getCause());
                    } catch (Throwable error) {
                        BridgeLog.error("Listener failed: " + target.getClass().getName() + "." + method.getName(), error);
                    }
                    return null;
                }
                if ("toString".equals(name)) {
                    return "ForgeBridgeListener[" + target.getClass().getSimpleName() + "." + method.getName() + "]";
                }
                if ("hashCode".equals(name)) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(name)) {
                    return Boolean.valueOf(proxy == (args == null ? null : args[0]));
                }
                return null;
            }
        };
        try {
            return Proxy.newProxyInstance(loader, new Class<?>[]{java.util.function.Consumer.class}, handler);
        } catch (Throwable error) {
            BridgeLog.error("Could not create consumer proxy for " + method, error);
            return null;
        }
    }

    /**
     * Posts an event. NeoForge events go to the real bus; bridge events to the legacy
     * dispatcher; everything else to both, which is what makes cross-mod traffic between a
     * translated mod and a native NeoForge mod work.
     *
     * @return number of legacy listeners invoked
     */
    public static int post(Object event) {
        if (event == null) {
            return 0;
        }
        int legacy = 0;
        if (isNeoForgeEvent(event.getClass())) {
            Object bus = gameBus();
            if (bus != null) {
                SafeReflect.invoke(bus, "post", event);
            }
        }
        legacy = LegacyDispatcher.post(event);
        return legacy;
    }

    /** Posts on a specific bus (the mod bus during setup). */
    public static boolean post(Object bus, Object event) {
        if (bus == null || event == null) {
            return false;
        }
        return SafeReflect.invoke(bus, "post", event) != null || true;
    }

    public static List<String> describeLegacyListeners() {
        List<String> list = new ArrayList<String>(LegacyDispatcher.describeListeners());
        return list;
    }

    public static int getRegisteredCount() {
        return registered;
    }
}

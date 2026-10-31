package dev.forgebridge.events;

import dev.forgebridge.util.BridgeLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Creates {@code java.util.function.Consumer} instances that forward to a callback.
 *
 * <p>NeoForge's {@code addListener(EventPriority, Class, Consumer)} is the only way to
 * register a listener for an individual method. Building a {@code Consumer} by hand would
 * require a compile-time NeoForge dependency for every event class the bridge cares about,
 * so a dynamic proxy is used instead: one interface, any event type.</p>
 */
public final class ConsumerProxy {

    /** Callback shape; implementations receive the event object. */
    public interface Callback {
        void accept(Object event);
    }

    private ConsumerProxy() {
    }

    public static Object create(final ClassLoader loader, final String description, final Callback callback) {
        ClassLoader effective = loader == null ? ConsumerProxy.class.getClassLoader() : loader;
        InvocationHandler handler = new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("accept".equals(name) && args != null && args.length == 1) {
                    try {
                        callback.accept(args[0]);
                    } catch (Throwable error) {
                        BridgeLog.error("Bridge listener failed: " + description, error);
                    }
                    return null;
                }
                if ("toString".equals(name)) {
                    return "ForgeBridgeConsumer[" + description + "]";
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
            return Proxy.newProxyInstance(effective, new Class<?>[]{java.util.function.Consumer.class}, handler);
        } catch (Throwable error) {
            BridgeLog.error("Could not create consumer proxy for " + description, error);
            return null;
        }
    }
}

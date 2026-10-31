package dev.forgebridge.tests;

import dev.forgebridge.compat.registry.LegacyRegistry;
import dev.forgebridge.compat.registry.RegistryAccess;
import dev.forgebridge.compat.registry.RegistryEvent;
import dev.forgebridge.events.EventBusBridge;
import dev.forgebridge.events.EventPriorityCompat;
import dev.forgebridge.events.LegacyDispatcher;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Event bridging: the split between the NeoForge bus and the legacy dispatcher. */
public final class EventTests {

    /** A NeoForge-shaped event. */
    public static final class TestNeoEvent extends Event {
        int handled;

        public void handled() {
            handled++;
        }
    }

    /** A mod listener, written the way a translated 1.21.1 mod writes it. */
    public static final class Listener {
        final List<String> order = new ArrayList<String>();
        int neoCalls;

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void onNeoEvent(TestNeoEvent event) {
            neoCalls++;
            order.add("neo");
            event.handled();
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void onRegister(RegistryEvent.Register event) {
            order.add("register-low");
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onRegisterFirst(RegistryEvent.Register event) {
            order.add("register-highest");
        }

        public void notAListener(Object ignored) {
            order.add("should-not-run");
        }
    }

    /** Minimal stand-in for NeoForge's bus: records consumers so the test can fire them. */
    public static final class FakeBus {
        final List<Object> consumers = new ArrayList<Object>();

        public Object addListener(Object priority, Object type, Object consumer) {
            consumers.add(consumer);
            return null;
        }

        public Object addListener(Object type, Object consumer) {
            consumers.add(consumer);
            return null;
        }
    }

    private EventTests() {
    }

    public static void testNeoEventsAndLegacyEventsAreRecognised() {
        Assert.isTrue(EventBusBridge.isNeoForgeEvent(TestNeoEvent.class), "neo event recognised");
        Assert.isTrue(EventBusBridge.isNeoForgeEvent(net.neoforged.bus.api.Event.class), "base event recognised");
        Assert.isFalse(EventBusBridge.isNeoForgeEvent(RegistryEvent.Register.class),
                "legacy registry event is not a NeoForge event");
        Assert.isFalse(EventBusBridge.isNeoForgeEvent(String.class), "unrelated class is not an event");
    }

    public static void testSubscriptionRoutesEachMethodToTheRightBus() throws Exception {
        LegacyDispatcher.clear();
        Listener listener = new Listener();
        FakeBus bus = new FakeBus();
        int registered = EventBusBridge.subscribe(listener, bus);
        Assert.equalInt(3, registered, "three annotated methods registered");
        Assert.equalInt(1, bus.consumers.size(), "the NeoForge event went to the real bus");
        Assert.equalInt(2, LegacyDispatcher.listenerCount(), "the two legacy listeners went to the dispatcher");
        Assert.equalInt(0, listener.neoCalls, "nothing fired yet");
    }

    public static void testNeoEventReachesTheListenerThroughTheBusConsumer() throws Exception {
        LegacyDispatcher.clear();
        Listener listener = new Listener();
        FakeBus bus = new FakeBus();
        EventBusBridge.subscribe(listener, bus);

        Object consumer = bus.consumers.get(0);
        Assert.isTrue(consumer instanceof java.util.function.Consumer, "listener is wrapped in a Consumer");
        TestNeoEvent event = new TestNeoEvent();
        ((java.util.function.Consumer) consumer).accept(event);
        Assert.equalInt(1, listener.neoCalls, "listener invoked once");
        Assert.equalInt(1, event.handled, "listener ran against the event instance");
    }

    public static void testLegacyListenersRunInPriorityOrder() throws Exception {
        LegacyDispatcher.clear();
        Listener listener = new Listener();
        EventBusBridge.subscribe(listener, new FakeBus());
        LegacyRegistry<Object> registry = RegistryAccess.items();
        RegistryEvent.Register register = new RegistryEvent.Register("minecraft:item", registry);
        int invoked = LegacyDispatcher.post(register);
        Assert.equalInt(2, invoked, "both legacy listeners invoked");
        Assert.equalInt(2, listener.order.size(), "two callbacks recorded");
        Assert.equal("register-highest", listener.order.get(0), "HIGHEST runs before LOW");
        Assert.equal("register-low", listener.order.get(1), "LOW runs last");
        Assert.equal("minecraft:item", register.getRegistryNameString(), "event carries the registry id");
        Assert.isTrue(register.getRegistry() == registry, "event carries the registry handle");
    }

    public static void testUnrelatedEventsDoNotReachLegacyListeners() {
        LegacyDispatcher.clear();
        Listener listener = new Listener();
        EventBusBridge.subscribe(listener, new FakeBus());
        int invoked = LegacyDispatcher.post(new TestNeoEvent());
        Assert.equalInt(0, invoked, "a NeoForge event does not hit the legacy dispatcher");
        Assert.equalInt(0, listener.order.size(), "no callbacks");
    }

    public static void testListenerCountAndDescribeAreUsableForDiagnostics() throws Exception {
        LegacyDispatcher.clear();
        Listener listener = new Listener();
        EventBusBridge.subscribe(listener, new FakeBus());
        List<String> described = EventBusBridge.describeLegacyListeners();
        Assert.equalInt(2, described.size(), "one line per legacy listener");
        Assert.contains(String.valueOf(described.get(0)) + String.valueOf(described.get(1)),
                "onRegister", "listener names are visible");
        Assert.isTrue(LegacyDispatcher.post(new RegistryEvent.Register("minecraft:block", RegistryAccess.blocks())) > 0,
                "dispatcher still posts after describe");
    }

    public static void testPriorityNamesMapToTheEnumAndBack() {
        Assert.equalInt(EventPriorityCompat.HIGHEST, EventPriorityCompat.fromName("HIGHEST"), "HIGHEST");
        Assert.equalInt(EventPriorityCompat.HIGH, EventPriorityCompat.fromName("HIGH"), "HIGH");
        Assert.equalInt(EventPriorityCompat.LOW, EventPriorityCompat.fromName("LOW"), "LOW");
        Assert.equalInt(EventPriorityCompat.NORMAL, EventPriorityCompat.fromName("unknown"), "unknown falls back to NORMAL");
        Assert.equalInt(EventPriorityCompat.NORMAL, EventPriorityCompat.fromName((String) null), "null falls back to NORMAL");

        Object constant = EventPriorityCompat.toNeoForgeConstant(EventPriorityCompat.HIGH);
        Assert.equal("HIGH", String.valueOf(constant), "HIGH maps to the real enum constant");
        Object clamped = EventPriorityCompat.toNeoForgeConstant(99);
        Assert.equal("LOWEST", String.valueOf(clamped), "out of range priorities clamp");
    }

    public static void testPriorityIsReadFromTheAnnotation() throws Exception {
        Method method = Listener.class.getMethod("onRegisterFirst", new Class<?>[]{RegistryEvent.Register.class});
        java.lang.annotation.Annotation annotation = method.getAnnotations()[0];
        Assert.equalInt(EventPriorityCompat.HIGHEST, EventPriorityCompat.fromAnnotation(annotation),
                "annotation priority parsed");
        Assert.equalInt(EventPriorityCompat.NORMAL, EventPriorityCompat.fromAnnotation((java.lang.annotation.Annotation) null),
                "missing annotation falls back to NORMAL");
    }

    public static void testPostingWithoutAGameBusIsSafe() {
        Assert.isNull(EventBusBridge.gameBus(), "no game bus outside the game");
        Assert.equalInt(0, EventBusBridge.post((Object) null), "posting null is a no-op");
        LegacyDispatcher.clear();
        Assert.equalInt(0, EventBusBridge.post(new TestNeoEvent()),
                "posting a NeoForge event without a bus does nothing and does not throw");
        Assert.isFalse(EventBusBridge.post(null, new TestNeoEvent()), "posting on a null bus is refused");
    }
}

package dev.forgebridge.events;

import dev.forgebridge.util.SafeReflect;

/** Translates Forge/NeoForge {@code EventPriority} enum names into sortable integers. */
public final class EventPriorityCompat {

    /** Lower value runs first, matching the bus convention. */
    public static final int HIGHEST = 0;
    public static final int HIGH = 1;
    public static final int NORMAL = 2;
    public static final int LOW = 3;
    public static final int LOWEST = 4;

    private EventPriorityCompat() {
    }

    public static int fromName(String name) {
        if (name == null) {
            return NORMAL;
        }
        if ("HIGHEST".equals(name)) {
            return HIGHEST;
        }
        if ("HIGH".equals(name)) {
            return HIGH;
        }
        if ("LOW".equals(name)) {
            return LOW;
        }
        if ("LOWEST".equals(name)) {
            return LOWEST;
        }
        return NORMAL;
    }

    /** Reads the {@code priority} attribute of a {@code @SubscribeEvent} annotation. */
    public static int fromAnnotation(java.lang.annotation.Annotation annotation) {
        if (annotation == null) {
            return NORMAL;
        }
        Object value = SafeReflect.invoke(annotation, "priority");
        if (value == null) {
            return NORMAL;
        }
        return fromName(String.valueOf(value));
    }

    /** Resolves the real {@code EventPriority} constant for the NeoForge bus. */
    public static Object toNeoForgeConstant(int priority) {
        Class<?> type = SafeReflect.findClass("net.neoforged.bus.api.EventPriority");
        if (type == null) {
            return null;
        }
        String[] names = new String[]{"HIGHEST", "HIGH", "NORMAL", "LOW", "LOWEST"};
        int index = Math.min(Math.max(priority, 0), names.length - 1);
        return SafeReflect.getStatic(type, names[index]);
    }
}

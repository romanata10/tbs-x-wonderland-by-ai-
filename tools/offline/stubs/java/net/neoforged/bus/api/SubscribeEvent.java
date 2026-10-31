package net.neoforged.bus.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stub of NeoForge's @SubscribeEvent.
 *
 * <p>Note: the real annotation declares {@code priority() default EventPriority.NORMAL}. The
 * stub omits the default because the offline compiler (Janino 3.1.9) cannot parse annotation
 * element defaults whose type is an enum. Bridge code never reads it at compile time - the
 * runtime value always comes from the real annotation - so this only affects test sources,
 * which spell the priority out.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SubscribeEvent {

    EventPriority priority();

    boolean receiveCanceled() default false;
}

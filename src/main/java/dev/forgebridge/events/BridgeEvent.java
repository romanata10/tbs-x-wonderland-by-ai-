package dev.forgebridge.events;

/**
 * Marker for events the bridge synthesises.
 *
 * <p>These are deliberately <em>not</em> {@code net.neoforged.bus.api.Event} subclasses: they
 * stand in for Forge events that NeoForge deleted, they are dispatched by
 * {@link LegacyDispatcher}, and making them NeoForge events would tempt the real bus into
 * accepting listeners it cannot satisfy.</p>
 */
public interface BridgeEvent {

    /** Stable name used in logs and reports. */
    String getBridgeEventName();
}

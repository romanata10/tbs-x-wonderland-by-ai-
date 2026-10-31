package dev.forgebridge.compat.registry;

/**
 * Stand-in for {@code net.minecraftforge.registries.RegistryEvent}.
 *
 * <p>Forge fired one {@code Register} event per registry on the mod event bus so that mods
 * could add entries. NeoForge replaced this with a single {@code RegisterEvent} that can
 * register into any registry. {@link RegistryBridge} listens to the NeoForge event, replays
 * it in Forge shape through {@link #Register}, and flushes anything the legacy listener
 * queued.</p>
 *
 * <p>The class name is kept identical (only the package moves) so that remapped bytecode,
 * including generic signatures such as {@code RegistryEvent.Register<Item>}, links up
 * without further translation.</p>
 */
public class RegistryEvent {

    private final String registryName;

    public RegistryEvent(String registryName) {
        this.registryName = registryName;
    }

    /** e.g. {@code minecraft:item} */
    public String getRegistryNameString() {
        return registryName;
    }

    /** Forge shape: {@code RegistryEvent.Register<T>} with {@code getRegistry()}. */
    public static class Register<T> extends RegistryEvent {

        private final LegacyRegistry<T> registry;

        public Register(String registryName, LegacyRegistry<T> registry) {
            super(registryName);
            this.registry = registry;
        }

        /** The registry the mod should register into. */
        public LegacyRegistry<T> getRegistry() {
            return registry;
        }
    }

    /** Forge shape: fired when a mod creates its own registry. */
    public static class NewRegistry extends RegistryEvent {

        public NewRegistry(String registryName) {
            super(registryName);
        }
    }
}

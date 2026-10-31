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

    /**
     * Forge 1.20.1 shape: {@code getRegistryName()} returned a {@code ResourceLocation}.
     * Inside a game that is exactly what is returned; outside one (CLI, tests) the id string is
     * returned instead, which keeps {@code toString()} working in both cases.
     */
    public Object getRegistryName() {
        Object parsed = dev.forgebridge.compat.LegacyResourceLocations.parse(registryName);
        return parsed == null ? registryName : parsed;
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

        /**
         * Forge 1.20.1 let a mod create a registry here ({@code createRegistry(...)} returned a
         * {@code RegistryBuilder}). NeoForge 1.21.1 has no per-mod registry creation: registries
         * are declared by the loader or by a data pack.
         *
         * <p>The call is therefore recorded and reported instead of pretending to succeed, and
         * {@code null} is returned. A mod that null-checks will skip its registrations; one that
         * does not will fail with a clear {@code NullPointerException} at the call site rather
         * than a mystery inside the registry system.</p>
         */
        public Object createRegistry(Object registryKey) {
            String id = getRegistryNameString();
            if (registryKey != null) {
                String resolved = RegistryBridge.registryIdOf(registryKey);
                if (resolved != null) {
                    id = resolved;
                }
            }
            RegistryLifecycleBridge.acknowledgeCustomRegistry(id);
            return null;
        }
    }
}

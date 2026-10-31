package dev.forgebridge.api.placeholder;

import dev.forgebridge.remap.MappingTable;
import dev.forgebridge.util.BridgeLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The placeholder set for Forge API that NeoForge deleted outright.
 *
 * <p>Each entry does three things: it adds an exact class mapping from the Forge name to a
 * generated stub, generates the stub's bytecode, and remembers why the type is gone so that the
 * log explains the situation. The stubs are injected into the translated jar by
 * {@link dev.forgebridge.jar.JarPatcher}, which is the only point where new classes can still
 * be added to a mod.</p>
 */
public final class PlaceholderLibrary {

    /** Description of one placeholder. */
    public static final class Placeholder {
        private final String legacyName;
        private final String kind;
        private final String message;
        private final byte[] bytes;
        private final String stubName;

        Placeholder(String legacyName, String kind, String message) {
            this.legacyName = legacyName;
            this.kind = kind;
            this.message = message;
            this.stubName = PlaceholderClassGenerator.placeholderName(legacyName);
            if ("interface".equals(kind)) {
                this.bytes = PlaceholderClassGenerator.generateInterface(stubName, message);
            } else if ("annotation".equals(kind)) {
                this.bytes = PlaceholderClassGenerator.generateAnnotation(stubName, message);
            } else if ("empty".equals(kind)) {
                this.bytes = PlaceholderClassGenerator.generateEmptyClass(stubName, message);
            } else {
                this.bytes = PlaceholderClassGenerator.generateStubClass(stubName, message);
            }
        }

        public String getLegacyName() {
            return legacyName;
        }

        public String getStubName() {
            return stubName;
        }

        public String getMessage() {
            return message;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public String getEntryName() {
            return stubName + ".class";
        }
    }

    private final List<Placeholder> placeholders = new ArrayList<Placeholder>();

    public PlaceholderLibrary add(String legacyInternalName, String kind, String message) {
        placeholders.add(new Placeholder(legacyInternalName, kind, message));
        return this;
    }

    public List<Placeholder> all() {
        return new ArrayList<Placeholder>(placeholders);
    }

    public Placeholder get(String legacyInternalName) {
        for (int i = 0; i < placeholders.size(); i++) {
            Placeholder candidate = (Placeholder) placeholders.get(i);
            if (candidate.getLegacyName().equals(legacyInternalName)) {
                return candidate;
            }
        }
        return null;
    }

    /** Applies the exact class mappings; call before any bytecode is translated. */
    public MappingTable applyTo(MappingTable table) {
        for (int i = 0; i < placeholders.size(); i++) {
            Placeholder placeholder = (Placeholder) placeholders.get(i);
            table.addClass(placeholder.getLegacyName(), placeholder.getStubName());
            table.note("placeholder:" + placeholder.getLegacyName(), placeholder.getMessage());
        }
        return table;
    }

    /** Entry name to bytes, ready to be written into a patched jar. */
    public Map<String, byte[]> asClassEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("dev/forgebridge/api/placeholder/PlaceholderSupport.class",
                PlaceholderClassGenerator.generateSupportClass());
        for (int i = 0; i < placeholders.size(); i++) {
            Placeholder placeholder = (Placeholder) placeholders.get(i);
            entries.put(placeholder.getEntryName(), placeholder.getBytes());
        }
        return entries;
    }

    public static PlaceholderLibrary defaultLibrary() {
        PlaceholderLibrary library = new PlaceholderLibrary();
        library.add("net/minecraftforge/fml/ModLoadingContext", "stub",
                "ModLoadingContext was removed in NeoForge 21; use constructor injection of ModContainer");
        library.add("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext", "stub",
                "FMLJavaModLoadingContext was removed in NeoForge 21; use constructor injection of IEventBus");
        library.add("net/minecraftforge/common/capabilities/Capability", "empty",
                "Capability was replaced by CapabilityToken/BlockCapability in NeoForge 21");
        library.add("net/minecraftforge/common/capabilities/CapabilityManager", "stub",
                "CapabilityManager was removed; register capabilities through RegisterCapabilitiesEvent");
        library.add("net/minecraftforge/common/capabilities/ICapabilityProvider", "interface",
                "ICapabilityProvider was replaced by the CapabilityToken lookup API");
        library.add("net/minecraftforge/network/simple/SimpleChannel", "stub",
                "SimpleChannel was replaced by NeoForge's payload networking (PayloadRegistrar)");
        library.add("net/minecraftforge/network/NetworkRegistry", "stub",
                "NetworkRegistry was removed in NeoForge 21");
        library.add("net/minecraftforge/network/NetworkDirection", "empty",
                "NetworkDirection was removed in NeoForge 21");
        // Forge 1.20.1 lifecycle types that NeoForge 21 does not carry at all. Each of these is
        // referenced from a 1.20.1 mod's constructor or registry code, so without a stub the class
        // fails to load before any of the bridge's diagnostics can run.
        library.add("net/minecraftforge/common/capabilities/CapabilityProvider", "stub",
                "CapabilityProvider was removed in NeoForge 21; expose capabilities through "
                        + "RegisterCapabilitiesEvent and a CapabilityToken lookup");
        library.add("net/minecraftforge/common/capabilities/ICapabilitySerializable", "interface",
                "ICapabilitySerializable was removed in NeoForge 21 with the capability rewrite");
        library.add("net/minecraftforge/registries/RegistryBuilder", "stub",
                "RegistryBuilder was removed; NeoForge creates registries through NewRegistryEvent "
                        + "and data driven registry keys, see RegistryLifecycleBridge");
        library.add("net/minecraftforge/registries/ObjectHolder", "annotation",
                "@ObjectHolder is an empty annotation here: the loader code that read the value is "
                        + "gone, so the field it annotated is not injected and stays null");
        return library;
    }

    public int size() {
        return placeholders.size();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < placeholders.size(); i++) {
            Placeholder placeholder = (Placeholder) placeholders.get(i);
            sb.append(placeholder.getLegacyName()).append(" -> ")
                    .append(placeholder.getStubName()).append('\n');
        }
        return sb.toString();
    }
}

package dev.forgebridge.api;

import dev.forgebridge.Bootstrap;
import dev.forgebridge.BridgeConstants;
import dev.forgebridge.compat.CompatConfig;
import dev.forgebridge.compat.CompatManager;
import dev.forgebridge.compat.CrossModRegistry;
import dev.forgebridge.events.EventBusBridge;
import dev.forgebridge.remap.ClassRemapEngine;
import dev.forgebridge.remap.MappingTable;

/**
 * Public surface of the bridge, for other compatibility mods and for debugging.
 *
 * <p>Everything here is safe to call at any point after the class is loaded: methods that need
 * the game fall back to a documented no-op instead of throwing.</p>
 */
public final class ForgeBridgeApi {

    private ForgeBridgeApi() {
    }

    public static String getVersion() {
        return BridgeConstants.VERSION;
    }

    public static String getMappingSet() {
        return BridgeConstants.MAPPING_SET;
    }

    /** The mapping table currently in use (built-in rules plus user map files). */
    public static MappingTable getMappingTable() {
        return Bootstrap.mappingTable();
    }

    public static ClassRemapEngine getRemapEngine() {
        return Bootstrap.remapEngine();
    }

    /** Maps a binary Forge class name to its NeoForge counterpart. */
    public static String mapClassName(String binaryName) {
        MappingTable table = getMappingTable();
        return table == null ? binaryName : table.mapClass(binaryName.replace('.', '/')).replace('/', '.');
    }

    /** Remaps a raw class file. Returns the input unchanged when remapping is impossible. */
    public static byte[] remapClassBytes(byte[] classBytes) {
        ClassRemapEngine engine = getRemapEngine();
        if (engine == null) {
            return classBytes;
        }
        return engine.remap(classBytes).getBytes();
    }

    /**
     * Posts an event to both event systems. A translated mod's listeners receive it through the
     * legacy dispatcher, native NeoForge listeners through the real bus.
     */
    public static int postEvent(Object event) {
        return EventBusBridge.post(event);
    }

    public static boolean isWonderlandLoaded() {
        return CompatManager.isWonderlandPresent();
    }

    public static boolean isBrokenScriptLoaded() {
        return CompatManager.isBrokenScriptPresent();
    }

    /** Resolves a cross-mod id alias registered by a compatibility profile. */
    public static String resolveAlias(String id) {
        return CrossModRegistry.resolve(id);
    }

    public static CompatConfig getConfig() {
        return CompatManager.getConfig();
    }

    /** One-line status for crash reports and command output. */
    public static String describe() {
        return "ForgeBridge " + BridgeConstants.VERSION + " [mappings " + BridgeConstants.MAPPING_SET
                + ", pipeline " + Bootstrap.pipelineName() + "]";
    }
}

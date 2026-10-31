package dev.forgebridge;

/** Shared identifiers and configuration keys. */
public final class BridgeConstants {

    public static final String MOD_ID = "forgebridge";
    public static final String LOG_NAME = "ForgeBridge";
    public static final String VERSION = "1.0.0";
    public static final String MAPPING_SET = "forge-1.21.1->neoforge-21.1.176";

    /** The two mods the bridge carries explicit compatibility profiles for. */
    public static final String MOD_WONDERLAND = "wonderland";
    public static final String MOD_BROKEN_SCRIPT = "the_broken_script";

    public static final String CONFIG_DIR = "forgebridge";
    public static final String CONFIG_FILE = "forgebridge-compat.toml";
    public static final String MAPPINGS_DIR = "mappings";
    public static final String LEGACY_DIR = "legacy";
    public static final String PATCHED_MARKER = ".forgebridge-patched";
    public static final String REPORT_FILE = "patch-report.txt";

    /** System properties understood by the bridge. */
    public static final String PROP_AUTOPATCH = "forgebridge.autopatch";
    public static final String PROP_DEBUG = "forgebridge.debug";
    public static final String PROP_DISABLE_EVENT_BRIDGE = "forgebridge.disableEventBridge";
    public static final String PROP_DISABLE_REGISTRY_BRIDGE = "forgebridge.disableRegistryBridge";
    public static final String PROP_ASM_LEVEL = "forgebridge.asmLevel";

    private BridgeConstants() {
    }
}

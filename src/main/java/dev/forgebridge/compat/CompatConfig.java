package dev.forgebridge.compat;

import dev.forgebridge.BridgeConstants;
import dev.forgebridge.jar.TomlDocument;
import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for the compatibility layer ({@code config/forgebridge/forgebridge-compat.toml}).
 *
 * <p>The schema is intentionally tiny and forgiving: unknown keys are ignored and the file is
 * rewritten from defaults when missing, so a server operator can inspect what the bridge is
 * doing without reading documentation.</p>
 */
public final class CompatConfig {

    private boolean enabled = true;
    private boolean eventBridge = true;
    private boolean registryBridge = true;
    private boolean tagBridge = true;
    private boolean placeholders = true;
    private boolean patchOnStartup = true;
    private boolean verbose = false;
    private boolean crossModHooks = true;

    private boolean wonderland = true;
    private boolean brokenScript = true;

    private final List<String> extraModIds = new ArrayList<String>();
    private final List<String> extraMappingFiles = new ArrayList<String>();
    private final Map<String, Boolean> rawModFlags = new LinkedHashMap<String, Boolean>();
    private File configFile;

    public static CompatConfig defaults() {
        CompatConfig config = new CompatConfig();
        config.extraModIds.add(BridgeConstants.MOD_WONDERLAND);
        config.extraModIds.add(BridgeConstants.MOD_BROKEN_SCRIPT);
        return config;
    }

    public static CompatConfig load(File configDir) {
        CompatConfig config = defaults();
        File file = new File(configDir, BridgeConstants.CONFIG_FILE);
        config.configFile = file;
        if (!file.isFile()) {
            try {
                IoUtil.writeFileText(file, config.renderDefaultDocument());
                BridgeLog.info("Wrote default compatibility config to " + file);
            } catch (IOException error) {
                BridgeLog.warn("Could not write default config to " + file, error);
            }
            return config;
        }
        try {
            config.apply(IoUtil.readFileText(file));
        } catch (IOException error) {
            BridgeLog.warn("Could not read " + file + "; using defaults", error);
        }
        return config;
    }

    private void apply(String text) {
        List<TomlDocument.Block> blocks = TomlDocument.of(text).getBlocks();
        for (int i = 0; i < blocks.size(); i++) {
            TomlDocument.Block block = (TomlDocument.Block) blocks.get(i);
            Map<String, String> values = block.getValues();
            String section = block.getHeader() == null ? "" : block.getHeader();
            for (Object element : values.entrySet()) {
                Map.Entry entry = (Map.Entry) element;
                String key = String.valueOf(entry.getKey());
                String value = String.valueOf(entry.getValue());
                if (section.isEmpty() || "features".equals(section) || "general".equals(section)) {
                    applyFeature(configKey(key), value);
                } else if ("mods".equals(section)) {
                    // "extra" is a list of additional mod ids, not a mod flag: it has to be
                    // handled before the per-mod booleans, otherwise it is recorded as a flag
                    // named "extra" and the list is lost.
                    if ("extra".equals(key) || "extraMods".equals(key)) {
                        addIds(extraModIds, value);
                    } else {
                        rawModFlags.put(key, Boolean.valueOf(value));
                        if (BridgeConstants.MOD_WONDERLAND.equals(key)) {
                            wonderland = Boolean.parseBoolean(value);
                        } else if (BridgeConstants.MOD_BROKEN_SCRIPT.equals(key)) {
                            brokenScript = Boolean.parseBoolean(value);
                        }
                    }
                } else if ("mappings".equals(section) && "extraFiles".equals(key)) {
                    addIds(extraMappingFiles, value);
                }
            }
        }
    }

    /** Parses a TOML-ish inline list such as {@code ["a", "b"]} into plain strings. */
    private static void addIds(List<String> target, String value) {
        if (value == null) {
            return;
        }
        String cleaned = value.replace("[", "").replace("]", "").replace("\"", "").replace("'", "");
        String[] parts = cleaned.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (!part.isEmpty() && !target.contains(part)) {
                target.add(part);
            }
        }
    }

    private String configKey(String key) {
        return key;
    }

    private void applyFeature(String key, String value) {
        boolean flag = Boolean.parseBoolean(value);
        if ("enabled".equals(key)) {
            enabled = flag;
        } else if ("eventBridge".equals(key)) {
            eventBridge = flag;
        } else if ("registryBridge".equals(key)) {
            registryBridge = flag;
        } else if ("tagBridge".equals(key)) {
            tagBridge = flag;
        } else if ("placeholders".equals(key)) {
            placeholders = flag;
        } else if ("patchOnStartup".equals(key)) {
            patchOnStartup = flag;
        } else if ("verbose".equals(key)) {
            verbose = flag;
        } else if ("crossModHooks".equals(key)) {
            crossModHooks = flag;
        }
    }

    public String renderDefaultDocument() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ForgeBridge compatibility configuration\n");
        sb.append("# Changes take effect on the next launch.\n\n");
        sb.append("[general]\n");
        sb.append("enabled = ").append(enabled).append('\n');
        sb.append("patchOnStartup = ").append(patchOnStartup).append('\n');
        sb.append("verbose = ").append(verbose).append("\n\n");
        sb.append("[features]\n");
        sb.append("eventBridge = ").append(eventBridge).append('\n');
        sb.append("registryBridge = ").append(registryBridge).append('\n');
        sb.append("tagBridge = ").append(tagBridge).append('\n');
        sb.append("placeholders = ").append(placeholders).append('\n');
        sb.append("crossModHooks = ").append(crossModHooks).append("\n\n");
        sb.append("# Per-mod opt-out. Both mods are optional; the bridge only acts when\n");
        sb.append("# they are actually installed.\n");
        sb.append("[mods]\n");
        sb.append("wonderland = ").append(wonderland).append('\n');
        sb.append("the_broken_script = ").append(brokenScript).append("\n\n");
        sb.append("[mappings]\n");
        sb.append("# Extra mapping files, relative to config/forgebridge/mappings/\n");
        sb.append("# extraFiles = [\"wonderland.map\"]\n");
        return sb.toString();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEventBridge() {
        return enabled && eventBridge;
    }

    public boolean isRegistryBridge() {
        return enabled && registryBridge;
    }

    public boolean isTagBridge() {
        return enabled && tagBridge;
    }

    public boolean isPlaceholders() {
        return enabled && placeholders;
    }

    public boolean isPatchOnStartup() {
        return enabled && patchOnStartup;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public boolean isCrossModHooks() {
        return enabled && crossModHooks;
    }

    public boolean isWonderlandEnabled() {
        return enabled && wonderland;
    }

    public boolean isBrokenScriptEnabled() {
        return enabled && brokenScript;
    }

    public List<String> getExtraModIds() {
        return extraModIds;
    }

    public List<String> getExtraMappingFiles() {
        return extraMappingFiles;
    }

    public File getConfigFile() {
        return configFile;
    }
}

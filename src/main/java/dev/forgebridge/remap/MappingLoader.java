package dev.forgebridge.remap;

import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.IoUtil;
import dev.forgebridge.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads extra mapping rules.
 *
 * <p>Two sources are supported and applied on top of {@link BuiltinMappings}:</p>
 * <ol>
 *   <li>map files shipped inside the bridge jar ({@code /forgebridge/mappings/*.map})</li>
 *   <li>map files in {@code config/forgebridge/mappings/*.map}</li>
 * </ol>
 *
 * <p>Format is line based; see {@code forgebridge/mappings/neo-21.1.map} for the grammar.
 * Keeping this data-driven means a user can teach the bridge about a mod-specific
 * class move without rebuilding it, and that the two target mods can be supported by
 * dropping a file into the config directory.</p>
 */
public final class MappingLoader {

    private MappingLoader() {
    }

    public static MappingTable load(File configDir, ClassLoader loader) {
        MappingTable table = (MappingTable) BuiltinMappings.get().copy();
        List<String> loadedFiles = new ArrayList<String>();

        loadFromClasspath(table, loader, "forgebridge/mappings", loadedFiles);
        if (configDir != null) {
            List<File> files = IoUtil.listFiles(configDir, new String[]{".map"});
            for (int i = 0; i < files.size(); i++) {
                apply(table, (File) files.get(i), loadedFiles);
            }
        }
        BridgeLog.info("Mapping table ready: " + table.describe()
                + (loadedFiles.isEmpty() ? "" : " (files: " + StringUtil.join(", ", loadedFiles) + ")"));
        return table;
    }

    private static void loadFromClasspath(MappingTable table, ClassLoader loader, String directory, List<String> loadedFiles) {
        // The bridge ships exactly one data file; the name is stable so no index is needed.
        String resource = directory + "/neo-21.1.map";
        InputStream in = loader == null ? null : loader.getResourceAsStream(resource);
        if (in == null) {
            in = MappingLoader.class.getResourceAsStream("/" + resource);
        }
        if (in == null) {
            BridgeLog.debug("No bundled mapping file at " + resource);
            return;
        }
        try {
            int applied = parse(table, IoUtil.readText(in), resource);
            loadedFiles.add(resource + "(" + applied + ")");
        } catch (IOException error) {
            BridgeLog.warn("Could not read bundled mapping file " + resource, error);
        } finally {
            IoUtil.closeQuietly(in);
        }
    }

    private static void apply(MappingTable table, File file, List<String> loadedFiles) {
        try {
            int applied = parse(table, IoUtil.readFileText(file), file.getName());
            loadedFiles.add(file.getName() + "(" + applied + ")");
        } catch (IOException error) {
            BridgeLog.warn("Could not read " + file, error);
        }
    }

    /** @return number of rules applied */
    public static int parse(MappingTable table, String content, String sourceName) {
        if (content == null) {
            return 0;
        }
        int applied = 0;
        String[] lines = content.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            try {
                if ("class".equals(parts[0]) && parts.length >= 3) {
                    table.addClass(parts[1], parts[2]);
                    applied++;
                } else if ("prefix".equals(parts[0]) && parts.length >= 3) {
                    table.addPrefix(parts[1], parts[2], "user mapping");
                    applied++;
                } else if ("field".equals(parts[0]) && parts.length >= 5) {
                    table.addField(parts[1], parts[2], parts[3], parts[4]);
                    applied++;
                } else if ("method".equals(parts[0]) && parts.length >= 5) {
                    table.addMethod(parts[1], parts[2], parts[3], parts[4]);
                    applied++;
                } else if ("block".equals(parts[0]) && parts.length >= 3) {
                    StringBuilder reason = new StringBuilder();
                    for (int r = 2; r < parts.length; r++) {
                        if (reason.length() > 0) {
                            reason.append(' ');
                        }
                        reason.append(parts[r]);
                    }
                    table.block(parts[1], reason.toString());
                    applied++;
                } else {
                    BridgeLog.warn("Ignoring unparsable mapping line in " + sourceName + ":" + (i + 1) + " -> " + line);
                }
            } catch (RuntimeException error) {
                BridgeLog.warn("Bad mapping line in " + sourceName + ":" + (i + 1), error);
            }
        }
        return applied;
    }

    /** Convenience for tests: builds a table from an inline map document. */
    public static MappingTable parseInline(String document) {
        MappingTable table = new MappingTable();
        table.setName("inline");
        parse(table, document, "inline");
        return table;
    }

    /**
     * Reports prefixes that are shadowed by an earlier, broader rule. This is the kind of
     * ordering mistake that silently breaks remapping, so the bridge checks it at load.
     */
    public static Map<String, String> validate(MappingTable table) {
        Map<String, String> problems = new HashMap<String, String>();
        List<MappingTable.PrefixRule> rules = table.getPrefixes();
        for (int i = 0; i < rules.size(); i++) {
            MappingTable.PrefixRule earlierRule = (MappingTable.PrefixRule) rules.get(i);
            String earlier = earlierRule.getFrom();
            for (int j = i + 1; j < rules.size(); j++) {
                MappingTable.PrefixRule laterRule = (MappingTable.PrefixRule) rules.get(j);
                String later = laterRule.getFrom();
                if (later.startsWith(earlier) && !earlierRule.getTo().equals(laterRule.getTo())) {
                    problems.put(later, "shadowed by " + earlier);
                }
            }
        }
        return problems;
    }
}

package dev.forgebridge.jar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates an FML descriptor from Forge 1.21.1 to NeoForge 21.1.
 *
 * <p>What actually stops a Forge mod from loading on NeoForge is not the bytecode, it is
 * the descriptor: FML validates {@code loaderVersion} against its own version and resolves
 * the {@code forge} dependency before a single class is touched. The rewriter therefore
 * has to fix, in order:</p>
 *
 * <ol>
 *   <li>{@code loaderVersion="[47,)"} to {@code "[4,)"} (FML 4.x on the NeoForge side),</li>
 *   <li>every {@code [[dependencies.<id>]]} block whose {@code modId} is {@code forge},</li>
 *   <li>the matching {@code versionRange} (47.x/1.21.1 to 21.1.x),</li>
 *   <li>a missing {@code license}, which FML 4 refuses to load without.</li>
 * </ol>
 */
public final class ModsTomlRewriter {

    /** Result of a descriptor rewrite. */
    public static final class Result {
        private final String text;
        private final List<String> changes;
        private final boolean changed;
        private final boolean legacyForge;

        Result(String text, List<String> changes, boolean changed, boolean legacyForge) {
            this.text = text;
            this.changes = changes;
            this.changed = changed;
            this.legacyForge = legacyForge;
        }

        public String getText() {
            return text;
        }

        public List<String> getChanges() {
            return changes;
        }

        public boolean isChanged() {
            return changed;
        }

        public boolean isLegacyForge() {
            return legacyForge;
        }
    }

    private ModsTomlRewriter() {
    }

    public static Result rewrite(String toml, PatchOptions options) {
        List<String> changes = new ArrayList<String>();
        boolean changed = false;
        TomlDocument document = TomlDocument.of(toml);
        ModDescriptor descriptor = ModDescriptor.parse(toml);
        boolean legacy = descriptor.looksLikeForge();

        // 1) loaderVersion at the root.
        TomlDocument.Block root = document.root();
        if (root != null && root.lineOf("loaderVersion") != null) {
            int lineIndex = root.lineOf("loaderVersion").intValue();
            String range = (String) root.getValues().get("loaderVersion");
            int major = ModDescriptor.firstVersionNumber(range);
            if (major >= 40) {
                String newLine = replaceValue(document.rawLine(lineIndex), options.getLoaderVersionRange());
                document.setRawLine(lineIndex, newLine);
                changes.add("loaderVersion " + range + " -> " + options.getLoaderVersionRange());
                changed = true;
            }
        }

        // 2) dependencies on forge.
        List<TomlDocument.Block> dependencies = document.dependencyBlocks();
        for (int i = 0; i < dependencies.size(); i++) {
            TomlDocument.Block block = (TomlDocument.Block) dependencies.get(i);
            String modId = (String) block.getValues().get("modId");
            if (modId == null || !"forge".equalsIgnoreCase(modId)) {
                continue;
            }
            Integer idLine = block.lineOf("modId");
            if (idLine != null) {
                document.setRawLine(idLine.intValue(), replaceValue(document.rawLine(idLine.intValue()), "neoforge"));
                changes.add("dependency " + block.getHeader() + ": modId forge -> neoforge");
                changed = true;
            }
            Integer rangeLine = block.lineOf("versionRange");
            String range = (String) block.getValues().get("versionRange");
            if (rangeLine != null && ModDescriptor.firstVersionNumber(range) >= 40) {
                document.setRawLine(rangeLine.intValue(),
                        replaceValue(document.rawLine(rangeLine.intValue()), options.getNeoforgeDependencyRange()));
                changes.add("dependency " + block.getHeader() + ": versionRange " + range
                        + " -> " + options.getNeoforgeDependencyRange());
                changed = true;
            }
        }

        // 3) license is mandatory for FML 4.
        if (root != null && root.getValues().get("license") == null) {
            int insertAt = root.getStartLine() == 0 ? findInsertLine(document) : root.getStartLine();
            document.getLines().add(insertAt, "license=\"All-Rights-Reserved\" # inserted by ForgeBridge: FML 4 requires a license");
            changes.add("added missing license field");
            changed = true;
        }

        // 4) an optional pointer back to the bridge, so a patched jar can be inspected.
        // Only translated (legacy) descriptors get it: a native NeoForge descriptor must not be
        // modified just because it passed through the rewriter.
        if (legacy && options.isAddOptionalBridgeDependency() && descriptor.getModId() != null
                && !descriptor.dependsOn(options.getBridgeModId())) {
            StringBuilder block = new StringBuilder();
            block.append('\n');
            block.append("[[dependencies.").append(descriptor.getModId()).append("]]\n");
            block.append("    modId=\"").append(options.getBridgeModId()).append("\" # added by ForgeBridge\n");
            block.append("    type=\"optional\"\n");
            block.append("    versionRange=\"[0,)\"\n");
            block.append("    ordering=\"AFTER\"\n");
            block.append("    side=\"BOTH\"\n");
            List<String> lines = document.getLines();
            String[] added = block.toString().replace("\r", "").split("\n", -1);
            for (int i = 0; i < added.length; i++) {
                if (added[i].isEmpty() && i == added.length - 1) {
                    continue;
                }
                lines.add(added[i]);
            }
            changes.add("added optional dependency on " + options.getBridgeModId());
            changed = true;
        }

        return new Result(document.render(), changes, changed, legacy);
    }

    /** Finds the end of the root key block, where a new root key may be inserted. */
    private static int findInsertLine(TomlDocument document) {
        List<TomlDocument.Block> blocks = document.getBlocks();
        if (blocks.size() > 1) {
            TomlDocument.Block second = (TomlDocument.Block) blocks.get(1);
            return second.getStartLine();
        }
        return document.lineCount();
    }

    /**
     * Rewrites the value of a {@code key=value} line while preserving indentation, the
     * original quote style and any trailing comment.
     */
    public static String replaceValue(String line, String newValue) {
        int equals = line.indexOf('=');
        if (equals < 0) {
            return line;
        }
        String prefix = line.substring(0, equals + 1);
        String remainder = line.substring(equals + 1);
        int comment = commentIndex(remainder);
        String valuePart = comment < 0 ? remainder : remainder.substring(0, comment);
        String suffix = comment < 0 ? "" : remainder.substring(comment);

        int start = 0;
        while (start < valuePart.length() && Character.isWhitespace(valuePart.charAt(start))) {
            start++;
        }
        String leading = valuePart.substring(0, start);
        char quote = '"';
        if (valuePart.length() > start) {
            char first = valuePart.charAt(start);
            if (first == '"' || first == '\'') {
                quote = first;
            }
        }
        return prefix + leading + quote + newValue + quote + suffix;
    }

    private static int commentIndex(String value) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                return i;
            }
        }
        return -1;
    }

    /** True when the descriptor already targets NeoForge, i.e. needs no translation. */
    public static boolean isNeoForgeDescriptor(String toml) {
        ModDescriptor descriptor = ModDescriptor.parse(toml);
        if (descriptor.dependsOn("neoforge")) {
            return true;
        }
        String loaderVersion = descriptor.getLoaderVersion();
        int major = ModDescriptor.firstVersionNumber(loaderVersion);
        return major > 0 && major < 40;
    }

    /** Exposes the parsed view for the scanner and the CLI. */
    public static ModDescriptor describe(String toml) {
        return ModDescriptor.parse(toml);
    }

    /** Test/debug helper: renders the values of every dependency block. */
    public static List<String> dependencySummary(String toml) {
        List<String> result = new ArrayList<String>();
        List<TomlDocument.Block> dependencies = TomlDocument.of(toml).dependencyBlocks();
        for (int i = 0; i < dependencies.size(); i++) {
            TomlDocument.Block block = (TomlDocument.Block) dependencies.get(i);
            Map<String, String> values = block.getValues();
            result.add(values.get("modId") + " " + values.get("type") + " " + values.get("versionRange"));
        }
        return result;
    }
}

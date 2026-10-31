package dev.forgebridge.jar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small, loss-free TOML view.
 *
 * <p>The bridge must edit {@code mods.toml} files written by humans (comments, odd
 * spacing, single or double quotes, inline comments) without reformatting the parts it
 * does not touch. Instead of a full parser the document is split into blocks keyed by
 * their section header and every edit is expressed as a line rewrite, so unrelated
 * lines round-trip byte for byte.</p>
 */
public final class TomlDocument {

    /** A section of the document introduced by a {@code [header]} or {@code [[header]]}. */
    public static final class Block {
        private final String header;
        private final boolean array;
        private final int startLine;
        private int endLine;
        private final Map<String, String> values = new LinkedHashMap<String, String>();
        private final Map<String, Integer> lineIndex = new LinkedHashMap<String, Integer>();

        Block(String header, boolean array, int startLine) {
            this.header = header;
            this.array = array;
            this.startLine = startLine;
            this.endLine = startLine;
        }

        public String getHeader() {
            return header;
        }

        public boolean isArray() {
            return array;
        }

        public int getStartLine() {
            return startLine;
        }

        public int getEndLine() {
            return endLine;
        }

        public Map<String, String> getValues() {
            return values;
        }

        public Integer lineOf(String key) {
            return (Integer) lineIndex.get(key);
        }

        public boolean isRoot() {
            return header == null;
        }

        public boolean isDependency() {
            return header != null && header.startsWith("dependencies.");
        }

        public String dependencyOwner() {
            return isDependency() ? header.substring("dependencies.".length()) : null;
        }

        public boolean isModsEntry() {
            return header != null && "mods".equals(header);
        }
    }

    private final List<String> lines;
    private final List<Block> blocks;

    private TomlDocument(List<String> lines, List<Block> blocks) {
        this.lines = lines;
        this.blocks = blocks;
    }

    public static TomlDocument of(String text) {
        List<String> lines = new ArrayList<String>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        while (start <= normalized.length()) {
            int newline = normalized.indexOf('\n', start);
            if (newline < 0) {
                lines.add(normalized.substring(start));
                break;
            }
            lines.add(normalized.substring(start, newline));
            start = newline + 1;
        }
        return new TomlDocument(lines, split(lines));
    }

    public static List<Block> split(String text) {
        List<String> lines = new ArrayList<String>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        while (start <= normalized.length()) {
            int newline = normalized.indexOf('\n', start);
            if (newline < 0) {
                lines.add(normalized.substring(start));
                break;
            }
            lines.add(normalized.substring(start, newline));
            start = newline + 1;
        }
        return split(lines);
    }

    private static List<Block> split(List<String> lines) {
        List<Block> result = new ArrayList<Block>();
        Block current = new Block(null, false, 0);
        result.add(current);
        boolean inMultiline = false;
        for (int i = 0; i < lines.size(); i++) {
            String raw = (String) lines.get(i);
            String line = stripComment(raw).trim();
            if (inMultiline) {
                current.endLine = i;
                if (MultilineStrings.terminates(raw)) {
                    inMultiline = false;
                }
                continue;
            }
            if (line.startsWith("[")) {
                boolean array = line.startsWith("[[");
                String header = line;
                if (array) {
                    int close = header.indexOf("]]");
                    header = close > 0 ? header.substring(2, close) : header.substring(2);
                } else {
                    int close = header.indexOf(']');
                    header = close > 0 ? header.substring(1, close) : header.substring(1);
                }
                current = new Block(header.trim(), array, i);
                result.add(current);
                continue;
            }
            current.endLine = i;
            if (line.isEmpty()) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String value = unquote(line.substring(equals + 1).trim());
            if (!current.getValues().containsKey(key)) {
                current.getValues().put(key, value);
                current.lineIndex.put(key, Integer.valueOf(i));
            }
            // A triple-quoted string may open and close on the same line, so the
            // delimiter parity on this line decides whether the value continues.
            if (MultilineStrings.opens(raw)) {
                inMultiline = true;
            }
        }
        return result;
    }

    /**
     * Triple-quoted string bookkeeping. TOML allows the closing delimiter on the same line
     * as the opener, and a value may put one delimiter on a line of its own, so delimiter
     * parity on the line decides whether the string is still open.
     */
    static final class MultilineStrings {

        private static final String SINGLE = "'''";
        private static final String DOUBLE = "\"\"\"";

        private MultilineStrings() {
        }

        static int delimiterCount(String raw) {
            return count(raw, SINGLE) + count(raw, DOUBLE);
        }

        private static int count(String raw, String needle) {
            int total = 0;
            int index = 0;
            while ((index = raw.indexOf(needle, index)) >= 0) {
                total++;
                index += needle.length();
            }
            return total;
        }

        /** True when this line opens a multiline string that is not closed on the same line. */
        static boolean opens(String raw) {
            return delimiterCount(raw) % 2 == 1;
        }

        /** True when this line closes an already open multiline string. */
        static boolean terminates(String raw) {
            return delimiterCount(raw) >= 1;
        }
    }
    public List<Block> getBlocks() {
        return blocks;
    }

    public Block root() {
        return blocks.isEmpty() ? null : (Block) blocks.get(0);
    }

    public List<Block> dependencyBlocks() {
        List<Block> result = new ArrayList<Block>();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = (Block) blocks.get(i);
            if (block.isDependency()) {
                result.add(block);
            }
        }
        return result;
    }

    public Block firstModBlock() {
        for (int i = 0; i < blocks.size(); i++) {
            Block block = (Block) blocks.get(i);
            if (block.isModsEntry()) {
                return block;
            }
        }
        return null;
    }

    public String rawLine(int index) {
        return (String) lines.get(index);
    }

    public void setRawLine(int index, String value) {
        lines.set(index, value);
    }

    public List<String> getLines() {
        return lines;
    }

    public int lineCount() {
        return lines.size();
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(String.valueOf(lines.get(i)));
            if (i < lines.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    public static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }
}

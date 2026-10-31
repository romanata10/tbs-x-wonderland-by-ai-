package dev.forgebridge.jar;

import dev.forgebridge.remap.MappingTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites fully qualified Forge class names inside text resources (JSON, JavaScript,
 * mixin configurations) using the same table as the bytecode remapper.
 *
 * <p>Resource files cannot be parsed as bytecode, so names are matched lexically. Both
 * the internal ({@code a/b/C}) and the source ({@code a.b.C}) spellings are handled, and
 * the longest legal name run is consumed so {@code Outer$Inner} stays intact.</p>
 */
public final class DottedNameMapper {

    private static final String[] LEGACY_ROOTS = new String[]{
            "net.minecraftforge.",
            "net/minecraftforge/"
    };

    private final MappingTable table;

    public DottedNameMapper(MappingTable table) {
        this.table = table;
    }

    public String map(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (int i = 0; i < LEGACY_ROOTS.length; i++) {
            result = mapRoot(result, LEGACY_ROOTS[i]);
        }
        return result;
    }

    public List<String> collectMappings(String text) {
        List<String> mapped = new ArrayList<String>();
        if (text == null) {
            return mapped;
        }
        for (int i = 0; i < LEGACY_ROOTS.length; i++) {
            String root = LEGACY_ROOTS[i];
            int index = 0;
            while ((index = text.indexOf(root, index)) >= 0) {
                int end = index;
                while (end < text.length() && isNameChar(text.charAt(end))) {
                    end++;
                }
                mapped.add(text.substring(index, end));
                index = end;
            }
        }
        return mapped;
    }

    private String mapRoot(String text, String root) {
        StringBuilder sb = null;
        int index = 0;
        int consumed = 0;
        while ((index = text.indexOf(root, consumed)) >= 0) {
            int end = index;
            while (end < text.length() && isNameChar(text.charAt(end))) {
                end++;
            }
            String token = text.substring(index, end);
            boolean internal = root.endsWith("/");
            String mapped = internal
                    ? table.mapClass(token)
                    : table.mapClass(token.replace('.', '/')).replace('/', '.');
            if (mapped.equals(token)) {
                consumed = end;
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(text.length() + 32);
            }
            sb.append(text, consumed, index).append(mapped);
            consumed = end;
        }
        if (sb == null) {
            return text;
        }
        sb.append(text.substring(consumed));
        return sb.toString();
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '/' || c == '.' || c == '$' || c == '_';
    }
}

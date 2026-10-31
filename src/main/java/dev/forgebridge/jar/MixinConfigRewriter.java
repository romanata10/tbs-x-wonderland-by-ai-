package dev.forgebridge.jar;

import dev.forgebridge.remap.MappingTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites Mixin configuration JSON.
 *
 * <p>Mixin refuses to run a configuration whose {@code compatibilityLevel} is older than
 * the class files it processes, and NeoForge ships Java 21 classes. The bridge bumps the
 * level and maps every Forge class name that appears in the file (targets, mixin package
 * names, plugins) through the normal mapping table.</p>
 */
public final class MixinConfigRewriter {

    private MixinConfigRewriter() {
    }

    public static final class Result {
        private final String text;
        private final List<String> changes;

        Result(String text, List<String> changes) {
            this.text = text;
            this.changes = changes;
        }

        public String getText() {
            return text;
        }

        public List<String> getChanges() {
            return changes;
        }

        public boolean isChanged() {
            return !changes.isEmpty();
        }
    }

    public static Result rewrite(String json, MappingTable table) {
        List<String> changes = new ArrayList<String>();
        String result = json;

        // compatibilityLevel: anything below JAVA_21 is raised, because the target
        // runtime is Java 21 and NeoForge class files are version 65.
        int levelIndex = result.indexOf("\"compatibilityLevel\"");
        if (levelIndex >= 0) {
            int valueStart = result.indexOf('"', result.indexOf(':', levelIndex));
            if (valueStart >= 0) {
                int valueEnd = result.indexOf('"', valueStart + 1);
                if (valueEnd > valueStart) {
                    String current = result.substring(valueStart + 1, valueEnd);
                    if (!"JAVA_21".equals(current)) {
                        result = result.substring(0, valueStart + 1) + "JAVA_21" + result.substring(valueEnd);
                        changes.add("compatibilityLevel " + current + " -> JAVA_21");
                    }
                }
            }
        }

        if (table != null) {
            DottedNameMapper mapper = new DottedNameMapper(table);
            List<String> found = mapper.collectMappings(result);
            String mapped = mapper.map(result);
            if (!mapped.equals(result)) {
                result = mapped;
                changes.add("remapped " + found.size() + " Forge class references");
            }
        }
        return new Result(result, changes);
    }
}

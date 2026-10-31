package dev.forgebridge.jar;

import dev.forgebridge.remap.MappingTable;
import dev.forgebridge.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates data and resource files: tags, recipes, loot modifiers, coremod scripts.
 *
 * <p>Two kinds of change are needed. First, namespaces: Forge's convention file namespace
 * was {@code forge}, NeoForge standardized on the {@code c} namespace for common tags and
 * on {@code neoforge} for its own recipe/condition types. Second, class names: coremod
 * scripts ({@code .js}) and {@code coremods.json} reference Forge classes as strings, and
 * JSON files can carry class names in {@code type} fields.</p>
 */
public final class DataRewriter {

    /** Forge recipe-condition type ids and their NeoForge replacements. */
    private static final String[][] CONDITION_IDS = new String[][]{
            {"forge:mod_loaded", "neoforge:mod_loaded"},
            {"forge:mod_not_loaded", "neoforge:mod_not_loaded"},
            {"forge:item_exists", "neoforge:item_exists"},
            {"forge:tag_empty", "neoforge:tag_empty"},
            {"forge:conditional", "neoforge:conditional"},
            {"forge:not", "neoforge:not"},
            {"forge:and", "neoforge:and"},
            {"forge:or", "neoforge:or"},
            {"forge:true", "neoforge:true"},
            {"forge:false", "neoforge:false"},
            {"forge:loot_table_exists", "neoforge:loot_table_exists"}
    };

    private DataRewriter() {
    }

    public static final class Result {
        private final String path;
        private final byte[] bytes;
        private final List<String> changes;

        Result(String path, byte[] bytes, List<String> changes) {
            this.path = path;
            this.bytes = bytes;
            this.changes = changes;
        }

        public String getPath() {
            return path;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public List<String> getChanges() {
            return changes;
        }

        public boolean isChanged() {
            return !changes.isEmpty();
        }
    }

    /** Returns {@code null} when the entry needs no change. */
    public static Result rewrite(String entryName, byte[] data, PatchOptions options, MappingTable table) {
        boolean json = entryName.endsWith(".json");
        boolean script = entryName.endsWith(".js");
        boolean tagLike = entryName.startsWith("data/") && entryName.indexOf("/tags/") > 0;
        if (!json && !script) {
            return null;
        }
        List<String> changes = new ArrayList<String>();

        String text = new String(data, java.nio.charset.Charset.forName("UTF-8"));
        String original = text;

        if (tagLike && options.isRewriteTagsToCommonNamespace()) {
            text = translateTagNamespace(text);
            if (!text.equals(original)) {
                changes.add("forge: tag references -> c:");
            }
        }
        if (json && options.isRewriteRecipesConditions()) {
            String before = text;
            text = translateConditionIds(text);
            if (!text.equals(before)) {
                changes.add("recipe condition ids forge: -> neoforge:");
            }
        }
        if (options.isUpgradeMixinCompatibilityLevel() && entryName.endsWith(".mixins.json")) {
            MixinConfigRewriter.Result mixin = MixinConfigRewriter.rewrite(text, table);
            if (mixin.isChanged()) {
                text = mixin.getText();
                changes.addAll(mixin.getChanges());
            }
        }
        if (table != null && (script || json)) {
            DottedNameMapper mapper = new DottedNameMapper(table);
            String mapped = mapper.map(text);
            if (!mapped.equals(text)) {
                text = mapped;
                changes.add("remapped Forge class names");
            }
        }
        if (text.equals(original)) {
            return null;
        }
        return new Result(relocateTagPath(entryName, options), text.getBytes(java.nio.charset.Charset.forName("UTF-8")), changes);
    }

    /** {@code data/forge/tags/...} becomes {@code data/c/tags/...}. */
    public static String relocateTagPath(String entryName, PatchOptions options) {
        if (!options.isRewriteTagsToCommonNamespace()) {
            return entryName;
        }
        if (!entryName.startsWith("data/forge/")) {
            return entryName;
        }
        String relocated = "data/c/" + entryName.substring("data/forge/".length());
        // Only tag files are moved; forge:* loot modifiers and structures keep their id.
        if (entryName.indexOf("/tags/") > 0) {
            return relocated;
        }
        return entryName;
    }

    public static String translateTagNamespace(String text) {
        return StringUtil.replace(text, "#forge:", "#c:");
    }

    public static String translateConditionIds(String text) {
        String result = text;
        for (int i = 0; i < CONDITION_IDS.length; i++) {
            result = StringUtil.replace(result, "\"" + CONDITION_IDS[i][0] + "\"", "\"" + CONDITION_IDS[i][1] + "\"");
            result = StringUtil.replace(result, "/" + CONDITION_IDS[i][0], "/" + CONDITION_IDS[i][1]);
        }
        return result;
    }
}

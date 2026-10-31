package dev.forgebridge.compat;

import dev.forgebridge.util.BridgeLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared namespace between the two target mods.
 *
 * <p>When a legacy mod and a native NeoForge mod have to exchange content, ids and tags are
 * the only stable contract. This registry records the aliases each compatibility profile
 * declares (for example an item the legacy mod knows as {@code wonderland:rune} but which is
 * reachable on the NeoForge side under a different id) and answers lookups for both sides.</p>
 *
 * <p>Aliases are also consulted when the bridge has to resolve an id at runtime, which is how
 * a recipe written against the Forge-era id keeps working after translation.</p>
 */
public final class CrossModRegistry {

    private static final Map<String, String> ALIASES = new LinkedHashMap<String, String>();
    private static final Map<String, Set<String>> SHARED_TAGS = new LinkedHashMap<String, Set<String>>();
    private static final List<String> HOOKS = new ArrayList<String>();

    private CrossModRegistry() {
    }

    /** Registers {@code from -> to}; both directions are resolvable. */
    public static synchronized void alias(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return;
        }
        ALIASES.put(from, to);
        if (!ALIASES.containsKey(to)) {
            ALIASES.put(to, from);
        }
        BridgeLog.debug("Alias registered: " + from + " <-> " + to);
    }

    public static synchronized String resolve(String id) {
        String target = (String) ALIASES.get(id);
        return target == null ? id : target;
    }

    public static synchronized boolean hasAlias(String id) {
        return ALIASES.containsKey(id);
    }

    public static synchronized Map<String, String> aliases() {
        return new LinkedHashMap<String, String>(ALIASES);
    }

    /** Declares that {@code id} should also be visible through a common ({@code c:}) tag. */
    public static synchronized void shareTag(String tagId, String id) {
        Set<String> members = (Set<String>) SHARED_TAGS.get(tagId);
        if (members == null) {
            members = new LinkedHashSet<String>();
            SHARED_TAGS.put(tagId, members);
        }
        if (members.add(id)) {
            BridgeLog.debug("Shared tag " + tagId + " += " + id);
        }
    }

    public static synchronized Map<String, Set<String>> sharedTags() {
        Map<String, Set<String>> copy = new LinkedHashMap<String, Set<String>>();
        for (Object element : SHARED_TAGS.entrySet()) {
            Map.Entry entry = (Map.Entry) element;
            copy.put(String.valueOf(entry.getKey()), new LinkedHashSet<String>((Set<String>) entry.getValue()));
        }
        return copy;
    }

    public static synchronized void recordHook(String description) {
        HOOKS.add(description);
    }

    public static synchronized List<String> hooks() {
        return new ArrayList<String>(HOOKS);
    }

    public static synchronized void clear() {
        ALIASES.clear();
        SHARED_TAGS.clear();
        HOOKS.clear();
    }
}

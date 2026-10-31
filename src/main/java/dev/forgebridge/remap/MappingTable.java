package dev.forgebridge.remap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lookup structure used by every remapping pass.
 *
 * <p>Names are stored in JVM internal form ({@code net/minecraftforge/...}) because that
 * is what ASM, descriptors and class file entries all use. Conversions to the
 * dot-separated source form happen only at the edges (logging, config, CLI).</p>
 */
public final class MappingTable {

    /** A prefix rewrite, e.g. {@code net/minecraftforge/fml/} to {@code net/neoforged/fml/}. */
    public static final class PrefixRule {
        private final String from;
        private final String to;
        private final String description;

        public PrefixRule(String from, String to, String description) {
            this.from = from;
            this.to = to;
            this.description = description;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }

        public String getDescription() {
            return description;
        }

        public boolean matches(String internalName) {
            return internalName != null && internalName.startsWith(from);
        }

        public String apply(String internalName) {
            return to + internalName.substring(from.length());
        }
    }

    /** A member rename rule: owner + name (+ optional descriptor) to a new name. */
    public static final class MemberRule {
        private final String owner;
        private final String name;
        private final String descriptor;
        private final String newName;

        public MemberRule(String owner, String name, String descriptor, String newName) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor == null ? "*" : descriptor;
            this.newName = newName;
        }

        public boolean matches(String ownerName, String memberName, String desc) {
            if (!owner.equals(ownerName) || !name.equals(memberName)) {
                return false;
            }
            return "*".equals(descriptor) || desc == null || descriptor.equals(desc);
        }

        public String getNewName() {
            return newName;
        }
    }

    private final Map<String, String> exactClasses = new LinkedHashMap<String, String>();
    private final List<PrefixRule> prefixes = new ArrayList<PrefixRule>();
    private final List<MemberRule> fieldRules = new ArrayList<MemberRule>();
    private final List<MemberRule> methodRules = new ArrayList<MemberRule>();
    private final Set<String> blockedClasses = new LinkedHashSet<String>();
    private final Map<String, String> blockedReasons = new LinkedHashMap<String, String>();
    private final Map<String, String> notes = new LinkedHashMap<String, String>();
    private String name = "unbound";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public MappingTable addPrefix(String from, String to, String description) {
        prefixes.add(new PrefixRule(from, to, description));
        return this;
    }

    public MappingTable addClass(String from, String to) {
        exactClasses.put(from, to);
        return this;
    }

    public MappingTable addField(String owner, String name, String desc, String newName) {
        fieldRules.add(new MemberRule(owner, name, desc, newName));
        return this;
    }

    public MappingTable addMethod(String owner, String name, String desc, String newName) {
        methodRules.add(new MemberRule(owner, name, desc, newName));
        return this;
    }

    public MappingTable block(String internalName, String reason) {
        blockedClasses.add(internalName);
        blockedReasons.put(internalName, reason);
        return this;
    }

    public MappingTable note(String key, String value) {
        notes.put(key, value);
        return this;
    }

    public Map<String, String> getNotes() {
        return Collections.unmodifiableMap(notes);
    }

    public Set<String> getBlockedClasses() {
        return Collections.unmodifiableSet(blockedClasses);
    }

    public String getBlockedReason(String internalName) {
        return (String) blockedReasons.get(internalName);
    }

    public boolean isBlocked(String internalName) {
        return blockedClasses.contains(internalName);
    }

    /** All exact rules, for reporting and for the residual-reference audit. */
    public Map<String, String> getExactClasses() {
        return Collections.unmodifiableMap(exactClasses);
    }

    public List<PrefixRule> getPrefixes() {
        return Collections.unmodifiableList(prefixes);
    }

    /**
     * Maps a class name in internal form. Returns the input unchanged when no rule
     * applies, which is the common case for game classes and a mod's own classes.
     */
    public String mapClass(String internalName) {
        if (internalName == null || internalName.isEmpty()) {
            return internalName;
        }
        String direct = (String) exactClasses.get(internalName);
        if (direct != null) {
            return direct;
        }
        for (int i = 0; i < prefixes.size(); i++) {
            PrefixRule rule = (PrefixRule) prefixes.get(i);
            if (rule.matches(internalName)) {
                String mapped = rule.apply(internalName);
                String override = (String) exactClasses.get(mapped);
                return override != null ? override : mapped;
            }
        }
        // Inner classes of an exactly-mapped outer class.
        int dollar = internalName.indexOf('$');
        if (dollar > 0) {
            String outer = (String) exactClasses.get(internalName.substring(0, dollar));
            if (outer != null) {
                return outer + internalName.substring(dollar);
            }
        }
        return internalName;
    }

    /** Maps only the descriptor / signature characters that need it. */
    public String mapDescriptor(String descriptor) {
        if (descriptor == null || descriptor.indexOf('L') < 0) {
            return descriptor;
        }
        StringBuilder sb = new StringBuilder(descriptor.length() + 16);
        int i = 0;
        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) {
                    sb.append(descriptor.substring(i));
                    break;
                }
                String internal = descriptor.substring(i + 1, end);
                sb.append('L').append(mapClass(internal)).append(';');
                i = end + 1;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    public String mapFieldName(String owner, String name, String descriptor) {
        for (int i = 0; i < fieldRules.size(); i++) {
            MemberRule rule = (MemberRule) fieldRules.get(i);
            if (rule.matches(owner, name, descriptor)) {
                return rule.getNewName();
            }
        }
        return name;
    }

    public String mapMethodName(String owner, String name, String descriptor) {
        for (int i = 0; i < methodRules.size(); i++) {
            MemberRule rule = (MemberRule) methodRules.get(i);
            if (rule.matches(owner, name, descriptor)) {
                return rule.getNewName();
            }
        }
        return name;
    }


    /** Deep copy; used when layering user mappings on top of the built-in table. */
    public MappingTable copy() {
        MappingTable copy = new MappingTable();
        copy.name = this.name + "+copy";
        copy.exactClasses.putAll(this.exactClasses);
        copy.prefixes.addAll(this.prefixes);
        copy.fieldRules.addAll(this.fieldRules);
        copy.methodRules.addAll(this.methodRules);
        copy.blockedClasses.addAll(this.blockedClasses);
        copy.blockedReasons.putAll(this.blockedReasons);
        copy.notes.putAll(this.notes);
        return copy;
    }

    public int ruleCount() {
        return exactClasses.size() + prefixes.size() + fieldRules.size() + methodRules.size();
    }

    public String describe() {
        return "MappingTable[" + name + ": " + prefixes.size() + " prefixes, "
                + exactClasses.size() + " classes, " + fieldRules.size() + " field rules, "
                + methodRules.size() + " method rules, " + blockedClasses.size() + " blocked]";
    }
}

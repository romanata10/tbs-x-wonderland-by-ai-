package dev.forgebridge.remap;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds legacy references left inside a rewritten class file.
 *
 * <p>A remapped class should not mention {@code net.minecraftforge} any more. When it
 * still does, either a mapping rule is missing (class name we do not know) or the class
 * touches an API that was removed outright. Both cases are surfaced in the patch report
 * rather than being left to produce a {@code NoClassDefFoundError} during play.</p>
 */
public final class ReferenceAudit {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String LEGACY = "net/minecraftforge/";
    private static final String LEGACY_DOT = "net.minecraftforge.";

    /** Custom registry namespaces from the target mods are not legacy leakage. */
    private static final String[] KNOWN_BENIGN = new String[]{
            "net/minecraftforge/eventbus/api/Event",
    };

    private ReferenceAudit() {
    }

    public static List<String> findLegacyReferences(byte[] classBytes) {
        List<String> found = new ArrayList<String>();
        if (classBytes == null || classBytes.length == 0) {
            return found;
        }
        String latin = new String(classBytes, Charset.forName("ISO-8859-1"));
        Set<String> unique = new LinkedHashSet<String>();
        int index = 0;
        while ((index = latin.indexOf(LEGACY, index)) >= 0) {
            int end = index;
            while (end < latin.length() && isNameChar(latin.charAt(end))) {
                end++;
            }
            unique.add(latin.substring(index, end));
            index = end;
        }
        index = 0;
        while ((index = latin.indexOf(LEGACY_DOT, index)) >= 0) {
            int end = index;
            while (end < latin.length() && isNameChar(latin.charAt(end))) {
                end++;
            }
            unique.add(latin.substring(index, end));
            index = end;
        }
        for (String reference : unique) {
            boolean benign = false;
            for (int i = 0; i < KNOWN_BENIGN.length; i++) {
                if (reference.startsWith(KNOWN_BENIGN[i])) {
                    benign = true;
                    break;
                }
            }
            if (!benign) {
                found.add(reference);
            }
        }
        return found;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '/' || c == '.' || c == '$' || c == '_' || c == '-';
    }
}

package dev.forgebridge.util;

import java.util.Collection;
import java.util.Iterator;

/** Small string helpers. Kept allocation-light and free of outside dependencies. */
public final class StringUtil {

    private StringUtil() {
    }

    public static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static boolean isSet(String value) {
        return !isBlank(value);
    }

    public static String join(String separator, Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Iterator<?> it = values.iterator();
        while (it.hasNext()) {
            sb.append(String.valueOf(it.next()));
            if (it.hasNext()) {
                sb.append(separator);
            }
        }
        return sb.toString();
    }

    public static String join(String separator, String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    public static String replace(String input, String search, String replacement) {
        if (input == null || input.isEmpty() || search == null || search.isEmpty()) {
            return input;
        }
        StringBuilder sb = new StringBuilder(input.length());
        int from = 0;
        int idx;
        while ((idx = input.indexOf(search, from)) >= 0) {
            sb.append(input, from, idx).append(replacement);
            from = idx + search.length();
        }
        sb.append(input, from, input.length());
        return sb.toString();
    }

    /** Replaces {@code search} regardless of case. Used for tolerant descriptor parsing. */
    public static String replaceIgnoreCase(String input, String search, String replacement) {
        if (input == null || search == null || search.isEmpty()) {
            return input;
        }
        String lowerInput = input.toLowerCase(java.util.Locale.ROOT);
        String lowerSearch = search.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder(input.length());
        int from = 0;
        int idx;
        while ((idx = lowerInput.indexOf(lowerSearch, from)) >= 0) {
            sb.append(input, from, idx).append(replacement);
            from = idx + search.length();
        }
        sb.append(input, from, input.length());
        return sb.toString();
    }

    public static boolean startsWithAny(String value, String[] prefixes) {
        for (int i = 0; i < prefixes.length; i++) {
            if (value.startsWith(prefixes[i])) {
                return true;
            }
        }
        return false;
    }

    public static String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * Math.max(count, 1));
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }

    /** Truncates for log output without splitting surrogate pairs. */
    public static String abbreviate(String value, int max) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }
}

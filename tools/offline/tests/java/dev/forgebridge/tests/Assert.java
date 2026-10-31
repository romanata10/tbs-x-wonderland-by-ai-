package dev.forgebridge.tests;

/** Minimal assertion helpers (the offline harness runs tests without JUnit). */
public final class Assert {

    private Assert() {
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void isFalse(boolean condition, String message) {
        isTrue(!condition, message);
    }

    public static void notNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message + " was null");
        }
    }

    public static void isNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + " was expected to be null but was " + value);
        }
    }

    public static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void notEqual(Object unexpected, Object actual, String message) {
        if (unexpected == null ? actual == null : unexpected.equals(actual)) {
            throw new AssertionError(message + ": value must not equal <" + unexpected + ">");
        }
    }

    public static void equalInt(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
        }
    }

    public static void contains(String haystack, String needle, String message) {
        notNull(haystack, message + " (haystack)");
        if (haystack.indexOf(needle) < 0) {
            throw new AssertionError(message + ": <" + needle + "> not found in <" + abbreviate(haystack) + ">");
        }
    }

    public static void notContains(String haystack, String needle, String message) {
        notNull(haystack, message + " (haystack)");
        if (haystack.indexOf(needle) >= 0) {
            throw new AssertionError(message + ": <" + needle + "> must not appear in <" + abbreviate(haystack) + ">");
        }
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    private static String abbreviate(String value) {
        return value.length() <= 400 ? value : value.substring(0, 400) + "...";
    }
}

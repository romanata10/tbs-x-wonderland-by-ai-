package dev.forgebridge.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Logging facade.
 *
 * <p>NeoForge supplies SLF4J at runtime, but the bridge must also be usable from a
 * plain {@code java -cp bridge.jar dev.forgebridge.cli.BridgeCli} invocation where no
 * logging backend exists. SLF4J is therefore reached reflectively and something
 * stdout-based is used when it is absent.</p>
 */
public final class BridgeLog {

    private static final String PREFIX = "[ForgeBridge] ";
    private static final Set<String> ONCE_KEYS = Collections.synchronizedSet(new HashSet<String>());

    private static volatile Object logger;
    private static volatile boolean loggerResolved;
    private static volatile boolean verbose;

    private BridgeLog() {
    }

    public static void setVerbose(boolean value) {
        verbose = value;
    }

    public static boolean isVerbose() {
        return verbose;
    }

    public static void info(String message) {
        write("INFO", message, null);
    }

    public static void warn(String message) {
        write("WARN", message, null);
    }

    public static void warn(String message, Throwable error) {
        write("WARN", message, error);
    }

    public static void error(String message) {
        write("ERROR", message, null);
    }

    public static void error(String message, Throwable error) {
        write("ERROR", message, error);
    }

    public static void debug(String message) {
        if (verbose) {
            write("DEBUG", message, null);
        }
    }

    /** Logs a message at most once per JVM for the given key. */
    public static void once(String key, String message) {
        if (ONCE_KEYS.add(key)) {
            warn(message);
        }
    }

    public static void errorOnce(String key, String message) {
        if (ONCE_KEYS.add(key)) {
            error(message);
        }
    }

    private static void write(String level, String message, Throwable error) {
        Object log = logger();
        if (log != null) {
            String method = "INFO".equals(level) ? "info" : ("WARN".equals(level) ? "warn" : ("ERROR".equals(level) ? "error" : "debug"));
            Object[] args = error == null ? new Object[]{message} : new Object[]{message, error};
            if (SafeReflect.invokeBoolean(log, method, args)) {
                return;
            }
        }
        String line = PREFIX + level + " " + message;
        if (error != null) {
            if ("ERROR".equals(level)) {
                System.err.println(line);
                error.printStackTrace(System.err);
            } else {
                System.out.println(line);
                error.printStackTrace(System.out);
            }
        } else if ("ERROR".equals(level)) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
    }

    private static Object logger() {
        if (loggerResolved) {
            return logger;
        }
        synchronized (BridgeLog.class) {
            if (loggerResolved) {
                return logger;
            }
            try {
                Class<?> factory = Class.forName("org.slf4j.LoggerFactory");
                Object created = factory.getMethod("getLogger", String.class).invoke(null, "ForgeBridge");
                logger = created;
            } catch (Throwable ignored) {
                logger = null;
            }
            loggerResolved = true;
            return logger;
        }
    }
}

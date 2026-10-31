package dev.forgebridge.remap;

import dev.forgebridge.BridgeConstants;
import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

/**
 * Detects which ASM layers are usable at runtime.
 *
 * <p>ModLauncher always ships {@code org.objectweb.asm} core (it transforms classes
 * itself), but {@code org.objectweb.asm.commons} lives in a separate JPMS module that is
 * not guaranteed to be readable from a transformation service. The bridge therefore
 * probes once and picks a pipeline:</p>
 *
 * <ul>
 *   <li>{@code FULL}    - core + asm-commons: name, descriptor, member and call-site rewriting</li>
 *   <li>{@code CORE}    - core only: call-site rewriting still works, names are handled by the raw path</li>
 *   <li>{@code NONE}    - raw constant-pool rewriting of class/package names only</li>
 * </ul>
 */
public final class AsmSupport {

    public enum Level {
        FULL("full ASM (ClassRemapper)"),
        CORE("core ASM only"),
        NONE("raw constant pool (no ASM)");

        private final String description;

        Level(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private static volatile Level level;

    private AsmSupport() {
    }

    public static Level level() {
        Level local = level;
        if (local == null) {
            synchronized (AsmSupport.class) {
                local = level;
                if (local == null) {
                    local = detect();
                    level = local;
                    BridgeLog.info("Remapping pipeline: " + local.getDescription());
                }
            }
        }
        return local;
    }

    /** Test hook so the raw fallback can be exercised on a machine that has ASM. */
    public static void override(Level forced) {
        level = forced;
    }

    private static Level detect() {
        // Escape hatch for troubleshooting: -Dforgebridge.asmLevel=FULL|CORE|NONE pins the
        // pipeline even when the jars on the class path would allow a richer one.
        String forced = System.getProperty(BridgeConstants.PROP_ASM_LEVEL);
        if (forced != null) {
            String name = forced.trim().toUpperCase(java.util.Locale.ROOT);
            Level[] levels = Level.values();
            for (int i = 0; i < levels.length; i++) {
                if (levels[i].name().equals(name)) {
                    BridgeLog.info("Remapping pipeline pinned to " + levels[i].getDescription()
                            + " by -D" + BridgeConstants.PROP_ASM_LEVEL);
                    return levels[i];
                }
            }
            BridgeLog.warn("Ignoring unknown value for -D" + BridgeConstants.PROP_ASM_LEVEL + ": " + forced);
        }
        boolean core = SafeReflect.classExists("org.objectweb.asm.ClassReader")
                && SafeReflect.classExists("org.objectweb.asm.ClassWriter")
                && SafeReflect.classExists("org.objectweb.asm.ClassVisitor");
        if (!core) {
            return Level.NONE;
        }
        boolean commons = SafeReflect.classExists("org.objectweb.asm.commons.ClassRemapper")
                && SafeReflect.classExists("org.objectweb.asm.commons.Remapper");
        return commons ? Level.FULL : Level.CORE;
    }
}

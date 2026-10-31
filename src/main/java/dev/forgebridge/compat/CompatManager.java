package dev.forgebridge.compat;

import dev.forgebridge.BridgeConstants;
import dev.forgebridge.util.BridgeLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the compatibility profiles.
 *
 * <p>Presence is evaluated once per launch and cached in the log line, because a profile that
 * quietly does nothing is much harder to diagnose than one that says so.</p>
 */
public final class CompatManager {

    private static final List<ModCompat> PROFILES = new ArrayList<ModCompat>();

    private static boolean applied;
    private static File gameDir;
    private static CompatConfig config;
    private static ModPresence presence;

    private CompatManager() {
    }

    public static void register(ModCompat compat) {
        PROFILES.add(compat);
    }

    public static synchronized void applyAll(File gameDirectory, CompatConfig compatConfig, ModPresence modPresence) {
        gameDir = gameDirectory;
        config = compatConfig;
        presence = modPresence;
        PROFILES.clear();
        PROFILES.add(new WonderlandCompat());
        PROFILES.add(new BrokenScriptCompat());

        if (applied) {
            return;
        }
        applied = true;
        if (config == null || !config.isEnabled()) {
            BridgeLog.info("Compatibility layer disabled by configuration");
            return;
        }
        List<String> found = presence.interestingMods();
        BridgeLog.info("Compatibility profiles: " + PROFILES.size()
                + ", detected mods: " + (found.isEmpty() ? "none of the known ids" : found.toString()));

        CompatContext context = new CompatContext(gameDir, config, presence);
        for (int i = 0; i < PROFILES.size(); i++) {
            ModCompat compat = (ModCompat) PROFILES.get(i);
            try {
                compat.apply(context);
            } catch (Throwable error) {
                BridgeLog.error("Compatibility profile for " + compat.getModId() + " failed", error);
            }
        }
        if (config.isTagBridge()) {
            SharedTagBridge.generate(gameDir);
        }
        BridgeLog.info("Cross-mod hooks: " + CrossModRegistry.hooks().size()
                + ", aliases: " + CrossModRegistry.aliases().size()
                + ", shared tags: " + CrossModRegistry.sharedTags().size());
    }

    public static CompatConfig getConfig() {
        return config;
    }

    public static File getGameDir() {
        return gameDir;
    }

    public static boolean isWonderlandPresent() {
        return presence != null && presence.isLoaded(BridgeConstants.MOD_WONDERLAND);
    }

    public static boolean isBrokenScriptPresent() {
        return presence != null && presence.isLoaded(BridgeConstants.MOD_BROKEN_SCRIPT);
    }
}

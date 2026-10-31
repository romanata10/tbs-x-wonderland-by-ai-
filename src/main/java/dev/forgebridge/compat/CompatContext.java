package dev.forgebridge.compat;

import java.io.File;

/** Everything a {@link ModCompat} profile is allowed to touch. */
public final class CompatContext {

    private final File gameDir;
    private final CompatConfig config;
    private final ModPresence presence;

    public CompatContext(File gameDir, CompatConfig config, ModPresence presence) {
        this.gameDir = gameDir;
        this.config = config;
        this.presence = presence;
    }

    public File getGameDir() {
        return gameDir;
    }

    public CompatConfig getConfig() {
        return config;
    }

    public boolean isLoaded(String modId) {
        return presence.isLoaded(modId);
    }

    public void alias(String from, String to) {
        CrossModRegistry.alias(from, to);
    }

    public void shareTag(String tagId, String id) {
        CrossModRegistry.shareTag(tagId, id);
    }

    public void hook(String description) {
        CrossModRegistry.recordHook(description);
    }

    public void log(String message) {
        dev.forgebridge.util.BridgeLog.info("[compat] " + message);
    }
}

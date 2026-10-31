package dev.forgebridge.compat;

import dev.forgebridge.BridgeConstants;

/**
 * Compatibility profile for {@code the_broken_script} (the native NeoForge mod).
 *
 * <p>The native side needs the mirror image of the wonderland profile: it must be able to see
 * the translated mod's content, and it must not receive Forge-shaped events it cannot
 * understand. The bridge therefore only exposes the shared namespace and never registers the
 * native mod on the legacy dispatcher.</p>
 */
public final class BrokenScriptCompat implements ModCompat {

    public String getModId() {
        return BridgeConstants.MOD_BROKEN_SCRIPT;
    }

    public void apply(CompatContext context) {
        if (!context.isLoaded(BridgeConstants.MOD_BROKEN_SCRIPT)) {
            return;
        }
        context.log("the_broken_script detected; wiring shared namespace and suppressing legacy dispatch");
        context.hook("the_broken_script: excluded from LegacyDispatcher, shares c: tags with wonderland");

        boolean legacyPresent = context.isLoaded(BridgeConstants.MOD_WONDERLAND);
        if (!legacyPresent) {
            context.log("wonderland is not installed; cross-mod hooks stay dormant");
            return;
        }
        String[] shared = new String[]{"script_fragment", "corrupted_ingot", "sealed_page"};
        for (int i = 0; i < shared.length; i++) {
            context.alias(BridgeConstants.MOD_BROKEN_SCRIPT + ":" + shared[i],
                    BridgeConstants.MOD_WONDERLAND + ":" + shared[i]);
            context.shareTag("c:compat/" + shared[i], BridgeConstants.MOD_BROKEN_SCRIPT + ":" + shared[i]);
            context.shareTag("c:compat/" + shared[i], BridgeConstants.MOD_WONDERLAND + ":" + shared[i]);
        }
    }
}

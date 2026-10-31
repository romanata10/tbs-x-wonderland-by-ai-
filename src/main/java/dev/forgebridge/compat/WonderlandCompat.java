package dev.forgebridge.compat;

import dev.forgebridge.BridgeConstants;

/**
 * Compatibility profile for {@code wonderland} (the legacy Forge mod).
 *
 * <p>What a translated legacy mod needs from the bridge is predictable: its registry events
 * have to fire, its tags have to survive the namespace move, and its ids have to stay
 * resolvable for the NeoForge mods that reference them. The profile encodes exactly that, and
 * only when the mod is installed.</p>
 */
public final class WonderlandCompat implements ModCompat {

    public String getModId() {
        return BridgeConstants.MOD_WONDERLAND;
    }

    public void apply(CompatContext context) {
        if (!context.isLoaded(BridgeConstants.MOD_WONDERLAND)) {
            return;
        }
        context.log("wonderland detected; enabling legacy registry/event translation for it");
        context.hook("wonderland: registry events routed through RegistryBridge");

        // Data-driven content of the legacy mod keeps its namespace; the bridge only adds
        // common tags so that NeoForge-side mods can find the content without hardcoding ids.
        String[] commonTags = new String[]{"c:gems", "c:ingots", "c:dusts", "c:raw_materials"};
        for (int i = 0; i < commonTags.length; i++) {
            context.shareTag(commonTags[i], BridgeConstants.MOD_WONDERLAND + ":" + commonTags[i].substring(2));
        }

        // Aliases: the same item is often known under both the old and the new namespace when
        // a mod ships separate 1.21.1 and NeoForge jars.
        String[] aliased = new String[]{"rune", "crystal", "shard", "essence"};
        for (int i = 0; i < aliased.length; i++) {
            context.alias(BridgeConstants.MOD_WONDERLAND + ":" + aliased[i],
                    BridgeConstants.MOD_BROKEN_SCRIPT + ":" + aliased[i]);
        }
    }
}

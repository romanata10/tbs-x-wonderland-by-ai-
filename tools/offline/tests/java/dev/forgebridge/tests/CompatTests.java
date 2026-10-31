package dev.forgebridge.tests;

import dev.forgebridge.compat.CompatConfig;
import dev.forgebridge.compat.CompatManager;
import dev.forgebridge.compat.CrossModRegistry;
import dev.forgebridge.compat.ModPresence;
import dev.forgebridge.compat.SharedTagBridge;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Configuration, mod detection and the cross-mod hooks for wonderland / the_broken_script. */
public final class CompatTests {

    private CompatTests() {
    }

    public static void testDefaultConfigIsWrittenOnFirstRun() throws Exception {
        File dir = Fixtures.tempDir("forgebridge-config");
        try {
            CompatConfig config = CompatConfig.load(dir);
            File file = new File(dir, "forgebridge-compat.toml");
            Assert.isTrue(file.isFile(), "default config written");
            Assert.isTrue(config.isEnabled(), "enabled by default");
            Assert.isTrue(config.isEventBridge(), "event bridge on by default");
            Assert.isTrue(config.isRegistryBridge(), "registry bridge on by default");
            Assert.isTrue(config.isTagBridge(), "tag bridge on by default");
            Assert.isTrue(config.isPlaceholders(), "placeholders on by default");
            Assert.isTrue(config.isPatchOnStartup(), "autopatch on by default");
            Assert.isFalse(config.isVerbose(), "verbose off by default");
            String text = dev.forgebridge.util.IoUtil.readFileText(file);
            Assert.contains(text, "[features]", "default document has a features section");
            Assert.contains(text, "[mods]", "default document has a mods section");
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    public static void testConfigFileValuesAreApplied() throws Exception {
        File dir = Fixtures.tempDir("forgebridge-config2");
        try {
            dev.forgebridge.util.IoUtil.writeFileText(new File(dir, "forgebridge-compat.toml"),
                    "[general]\n"
                    + "enabled = true\n"
                    + "patchOnStartup = false\n"
                    + "verbose = true\n"
                    + "\n[features]\n"
                    + "eventBridge = false\n"
                    + "registryBridge = true\n"
                    + "tagBridge = false\n"
                    + "nbtBridge = false\n"
                    + "placeholders = false\n"
                    + "crossModHooks = false\n"
                    + "\n[mods]\n"
                    + "wonderland = false\n"
                    + "the_broken_script = true\n"
                    + "extra = [\"examplemod\", \"othermod\"]\n"
                    + "\n[mappings]\n"
                    + "extraFiles = [\"a.map\", \"b.map\"]\n");
            CompatConfig config = CompatConfig.load(dir);
            Assert.isFalse(config.isEventBridge(), "event bridge disabled");
            Assert.isTrue(config.isRegistryBridge(), "registry bridge kept");
            Assert.isFalse(config.isTagBridge(), "tag bridge disabled");
            Assert.isFalse(config.isNbtBridge(), "NBT flush disabled");
            Assert.isFalse(config.isPlaceholders(), "placeholders disabled");
            Assert.isFalse(config.isPatchOnStartup(), "autopatch disabled");
            Assert.isTrue(config.isVerbose(), "verbose enabled");
            Assert.isFalse(config.isCrossModHooks(), "cross mod hooks disabled");
            Assert.isFalse(config.isWonderlandEnabled(), "wonderland profile disabled");
            Assert.isTrue(config.isBrokenScriptEnabled(), "broken script profile kept");
            List<String> extraMods = config.getExtraModIds();
            Assert.isTrue(extraMods.contains("examplemod"), "extra mod id parsed: " + extraMods);
            Assert.isTrue(extraMods.contains("othermod"), "second extra mod id parsed");
            List<String> extraFiles = config.getExtraMappingFiles();
            Assert.equalInt(2, extraFiles.size(), "extra mapping files parsed: " + extraFiles);
            Assert.notNull(config.getConfigFile(), "config file recorded");
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    public static void testModPresenceUsesJarNamesWhenFmlIsAbsent() throws Exception {
        File dir = Fixtures.tempDir("forgebridge-presence");
        try {
            Fixtures.legacyJar(dir, "wonderland-1.4.0.jar");
            Fixtures.legacyJar(dir, "the_broken_script-2.0.jar");
            ModPresence presence = new ModPresence(dir, Arrays.asList("wonderland", "the_broken_script", "absent_mod"));
            Assert.isTrue(presence.isLoaded("wonderland"), "wonderland detected by file name");
            Assert.isTrue(presence.isLoaded("the_broken_script"), "broken script detected by file name");
            Assert.isFalse(presence.isLoaded("absent_mod"), "absent mod not detected");
            Assert.isFalse(presence.isLoaded((String) null), "null mod id");
            List<String> interesting = presence.interestingMods();
            Assert.equalInt(2, interesting.size(), "two known mods found: " + interesting);
            Assert.equalInt(2, presence.detected().size(), "two jars listed");
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    public static void testAliasesResolveAcrossNamespaces() {
        CrossModRegistry.clear();
        CrossModRegistry.alias("wonderland:rune", "the_broken_script:rune");
        Assert.equal("the_broken_script:rune", CrossModRegistry.resolve("wonderland:rune"), "alias resolves");
        Assert.equal("minecraft:diamond", CrossModRegistry.resolve("minecraft:diamond"), "unknown id passes through");
        Assert.isTrue(CrossModRegistry.hasAlias("wonderland:rune"), "alias recorded");
        Assert.isFalse(CrossModRegistry.hasAlias("minecraft:diamond"), "no alias for an unrelated id");
        // Aliases are bidirectional so that both mods can resolve the other's ids.
        Assert.equalInt(2, CrossModRegistry.aliases().size(), "alias registered in both directions");
        Assert.equal("wonderland:rune", CrossModRegistry.resolve("the_broken_script:rune"), "reverse alias");
        CrossModRegistry.shareTag("c:gems", "wonderland:gems");
        CrossModRegistry.shareTag("c:gems", "the_broken_script:gems");
        Set<String> shared = (Set<String>) CrossModRegistry.sharedTags().get("c:gems");
        Assert.notNull(shared, "shared tag set");
        Assert.equalInt(2, shared.size(), "both ids in the shared tag");
        CrossModRegistry.recordHook("wonderland: registry events routed through RegistryBridge");
        Assert.equalInt(1, CrossModRegistry.hooks().size(), "hook recorded");
        CrossModRegistry.clear();
        Assert.equalInt(0, CrossModRegistry.aliases().size(), "clear() resets aliases");
        Assert.equalInt(0, CrossModRegistry.sharedTags().size(), "clear() resets shared tags");
    }

    public static void testApplyAllWiresTheCompatibilityProfiles() throws Exception {
        File gameDir = Fixtures.tempDir("forgebridge-compat");
        try {
            File mods = new File(gameDir, "mods");
            Assert.isTrue(mods.mkdirs(), "mods directory");
            Fixtures.legacyJar(mods, "wonderland-1.4.0.jar");
            Fixtures.legacyJar(mods, "the_broken_script-2.0.jar");

            CrossModRegistry.clear();
            CompatConfig config = CompatConfig.defaults();
            ModPresence presence = new ModPresence(mods, config.getExtraModIds());
            CompatManager.applyAll(gameDir, config, presence);

            Assert.isTrue(CompatManager.isWonderlandPresent(), "wonderland profile active");
            Assert.isTrue(CompatManager.isBrokenScriptPresent(), "broken script profile active");
            Assert.isTrue(CrossModRegistry.hooks().size() >= 2, "hooks recorded: " + CrossModRegistry.hooks());
            Assert.equal("the_broken_script:rune", CrossModRegistry.resolve("wonderland:rune"),
                    "aliases from both profiles registered");

            Map<String, Set<String>> tags = CrossModRegistry.sharedTags();
            Assert.isTrue(tags.size() >= 3, "shared tags registered: " + tags.keySet());
            Assert.notNull(tags.get("c:compat/script_fragment"), "compat tag from the broken script profile");

            // The tag bridge writes a generated data pack that vanilla-style tags can consume.
            File generated = new File(new File(gameDir, "forgebridge"), "generated-data");
            Assert.isTrue(generated.isDirectory(), "generated data directory written");
            File tagFile = new File(new File(new File(generated, "data"), "c"), "tags/gems.json");
            Assert.isTrue(tagFile.isFile(), "c:gems tag file written: " + tagFile);
            String json = dev.forgebridge.util.IoUtil.readFileText(tagFile);
            Assert.contains(json, "wonderland:gems", "tag file lists the legacy mod's item");
            Assert.contains(json, "\"replace\": false", "tag file is additive");
        } finally {
            Fixtures.deleteTree(gameDir);
        }
    }

    public static void testSharedTagBridgeIsANoOpWithoutTags() throws Exception {
        File gameDir = Fixtures.tempDir("forgebridge-tags");
        try {
            CrossModRegistry.clear();
            File result = SharedTagBridge.generate(gameDir);
            Assert.isNull(result, "nothing generated when no tags are shared");
        } finally {
            Fixtures.deleteTree(gameDir);
        }
    }
}

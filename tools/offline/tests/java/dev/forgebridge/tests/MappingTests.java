package dev.forgebridge.tests;

import dev.forgebridge.Bootstrap;
import dev.forgebridge.remap.AsmSupport;
import dev.forgebridge.remap.BuiltinMappings;
import dev.forgebridge.remap.ClassRemapEngine;
import dev.forgebridge.remap.MappingLoader;
import dev.forgebridge.remap.MappingTable;
import dev.forgebridge.remap.RedirectTable;

import java.io.File;
import java.util.List;

/** The mapping table is the single source of truth for every rewrite; verify it precisely. */
public final class MappingTests {

    private MappingTests() {
    }

    public static void testPackagePrefixesFollowTheNeoForgeLayout() {
        MappingTable table = BuiltinMappings.get();
        Assert.equal("net/neoforged/fml/common/Mod",
                table.mapClass("net/minecraftforge/fml/common/Mod"), "FML package move");
        Assert.equal("net/neoforged/bus/api/Event",
                table.mapClass("net/minecraftforge/eventbus/api/Event"), "event bus package move");
        Assert.equal("net/neoforged/neoforgespi/language/IModLanguageProvider",
                table.mapClass("net/minecraftforge/forgespi/language/IModLanguageProvider"), "SPI package move");
        Assert.equal("net/neoforged/api/distmarker/Dist",
                table.mapClass("net/minecraftforge/api/distmarker/Dist"), "dist marker move");
        // The catch-all has to come last: anything else under net.minecraftforge is nested one deeper.
        Assert.equal("net/neoforged/neoforge/common/CommonHooks",
                table.mapClass("net/minecraftforge/common/ForgeHooks"), "renamed class wins over prefix");
    }

    public static void testExactRenamesWinOverPrefixes() {
        MappingTable table = BuiltinMappings.get();
        Assert.equal("net/neoforged/neoforge/common/NeoForge",
                table.mapClass("net/minecraftforge/common/MinecraftForge"), "MinecraftForge");
        Assert.equal("net/neoforged/neoforge/common/ModConfigSpec$Builder",
                table.mapClass("net/minecraftforge/common/ForgeConfigSpec$Builder"), "nested config builder");
        Assert.equal("net/neoforged/fml/common/EventBusSubscriber",
                table.mapClass("net/minecraftforge/fml/common/Mod$EventBusSubscriber"), "event bus subscriber");
        Assert.equal("dev/forgebridge/compat/registry/LegacyRegistry",
                table.mapClass("net/minecraftforge/registries/IForgeRegistry"), "registry interface shim");
        Assert.equal("net/minecraft/core/registries/BuiltInRegistries",
                table.mapClass("net/minecraftforge/registries/ForgeRegistries"), "registry holder");
    }

    public static void testInnerClassOfRenamedOuterIsRenamed() {
        MappingTable table = BuiltinMappings.get();
        // FMLHandshakeHandler$Foo is not an exact rule, but its outer class is.
        String mapped = table.mapClass("net/minecraftforge/fml/common/Mod$Whatever");
        Assert.equal("net/neoforged/fml/common/Mod$Whatever", mapped, "inner class follows outer rename");
        String unknownInner = table.mapClass("com/example/Outer$Inner");
        Assert.equal("com/example/Outer$Inner", unknownInner, "unrelated inner class untouched");
    }

    public static void testDescriptorsAndSignaturesAreMapped() {
        MappingTable table = BuiltinMappings.get();
        String descriptor = "(Lnet/minecraftforge/eventbus/api/IEventBus;Ljava/lang/String;)"
                + "Lnet/minecraftforge/fml/common/Mod;";
        String mapped = table.mapDescriptor(descriptor);
        Assert.equal("(Lnet/neoforged/bus/api/IEventBus;Ljava/lang/String;)Lnet/neoforged/fml/common/Mod;",
                mapped, "method descriptor");
        Assert.equal("[[Lnet/minecraft/world/item/Item;", table.mapDescriptor("[[Lnet/minecraft/world/item/Item;"),
                "array descriptor untouched");
        Assert.equal("I", table.mapDescriptor("I"), "primitive descriptor untouched");
    }

    public static void testFieldRenamesMatchForgeRegistriesConstants() {
        MappingTable table = BuiltinMappings.get();
        String owner = "net/minecraft/core/registries/BuiltInRegistries";
        Assert.equal("ITEM", table.mapFieldName(owner, "ITEMS", "*"), "ITEMS -> ITEM");
        Assert.equal("BLOCK", table.mapFieldName(owner, "BLOCKS", "*"), "BLOCKS -> BLOCK");
        Assert.equal("ENTITY_TYPE", table.mapFieldName(owner, "ENTITIES", "*"), "ENTITIES -> ENTITY_TYPE");
        Assert.equal("BLOCK_ENTITY_TYPE",
                table.mapFieldName(owner, "TILE_ENTITIES", "*"), "TILE_ENTITIES -> BLOCK_ENTITY_TYPE");
        Assert.equal("UNKNOWN", table.mapFieldName(owner, "UNKNOWN", "*"), "unknown field untouched");
    }

    public static void testUnknownClassesAreLeftAlone() {
        MappingTable table = BuiltinMappings.get();
        Assert.equal("net/minecraft/world/item/Item", table.mapClass("net/minecraft/world/item/Item"),
                "vanilla class");
        Assert.equal("com/example/mod/MyItem", table.mapClass("com/example/mod/MyItem"), "mod class");
        Assert.equal("", table.mapClass(""), "empty");
        Assert.isNull(table.mapClass((String) null), "null");
    }

    public static void testBlockedTypesCarryReasons() {
        MappingTable table = BuiltinMappings.get();
        Assert.isTrue(table.getBlockedClasses().size() >= 8, "expected the known removed APIs to be listed");
        Assert.isTrue(table.isBlocked("net/minecraftforge/network/simple/SimpleChannel"), "SimpleChannel blocked");
        String reason = table.getBlockedReason("net/minecraftforge/network/simple/SimpleChannel");
        Assert.notNull(reason, "SimpleChannel reason");
        Assert.contains(reason, "payload", "blocked reason explains the replacement");
        String missing = table.getBlockedReason("com/example/NotThere");
        Assert.equal(null, missing, "no reason for unknown class");
    }

    public static void testEngineMapsBinaryNamesAndEntryNames() {
        ClassRemapEngine engine = new ClassRemapEngine(BuiltinMappings.get(), RedirectTable.builtin());
        Assert.equal("net.neoforged.neoforge.common.NeoForge",
                engine.mapBinaryName("net.minecraftforge.common.MinecraftForge"), "binary name");
        Assert.equal("net/neoforged/fml/common/Mod.class",
                engine.mapEntryName("net/minecraftforge/fml/common/Mod.class"), "entry name");
        Assert.equal("assets/examplemod/lang/en_us.json",
                engine.mapEntryName("assets/examplemod/lang/en_us.json"), "non class entry untouched");
    }

    public static void testRedirectTableCoversEveryLegacyRegistryField() {
        RedirectTable redirects = RedirectTable.builtin();
        String owner = "net/minecraftforge/registries/ForgeRegistries";
        String[][] expected = new String[][]{
                {"ITEMS", "items"}, {"BLOCKS", "blocks"}, {"ENTITIES", "entityTypes"},
                {"SOUND_EVENTS", "soundEvents"}, {"FLUIDS", "fluids"}, {"MOB_EFFECTS", "mobEffects"},
                {"POTIONS", "potions"}, {"ENCHANTMENTS", "enchantments"}, {"PARTICLES", "particleTypes"},
                {"CREATIVE_MODE_TABS", "creativeModeTabs"}, {"RECIPE_SERIALIZERS", "recipeSerializers"},
                {"BLOCK_ENTITY_TYPES", "blockEntityTypes"}, {"MENU_TYPES", "menuTypes"},
                {"ATTRIBUTES", "attributes"}
        };
        for (int i = 0; i < expected.length; i++) {
            RedirectTable.Redirect rule = redirects.find(RedirectTable.FIELD_GET, owner, expected[i][0], "*");
            Assert.notNull(rule, "redirect for " + expected[i][0]);
            Assert.equal("dev/forgebridge/compat/registry/RegistryAccess", rule.getTargetOwner(),
                    "redirect owner for " + expected[i][0]);
            Assert.equal(expected[i][1], rule.getTargetName(), "redirect method for " + expected[i][0]);
        }
        // The 15th registry (data components) is exposed by RegistryAccess but has no Forge field.
        String[] keys = dev.forgebridge.compat.registry.RegistryAccess.knownKeys();
        Assert.equalInt(15, keys.length, "registry keys exposed by RegistryAccess");
        Assert.equal("dataComponents", keys[keys.length - 1], "last registry key");
    }

    public static void testCustomMappingFileOverridesBuiltInRules() throws Exception {
        File dir = Fixtures.tempDir("forgebridge-map");
        try {
            MappingTable builtIn = BuiltinMappings.get();
            Assert.equal("net/neoforged/neoforge/common/NeoForge",
                    builtIn.mapClass("net/minecraftforge/common/MinecraftForge"), "baseline mapping");

            dev.forgebridge.util.IoUtil.writeFileText(new File(dir, "custom.map"),
                    "# user overrides\n"
                    + "class net/minecraftforge/common/MinecraftForge com/example/PatchedForge\n"
                    + "class com/example/legacy/Thing com/example/modern/Thing\n"
                    + "field com/example/Holder OLD_NAME * NEW_NAME\n"
                    + "block com/example/legacy/Removed not supported any more\n");
            MappingTable table = MappingLoader.load(dir, MappingTests.class.getClassLoader());
            Assert.equal("com/example/PatchedForge",
                    table.mapClass("net/minecraftforge/common/MinecraftForge"), "user rule overrides built-in");
            Assert.equal("com/example/modern/Thing",
                    table.mapClass("com/example/legacy/Thing"), "user rule for a mod class");
            Assert.equal("NEW_NAME", table.mapFieldName("com/example/Holder", "OLD_NAME", "*"), "user field rule");
            Assert.isTrue(table.isBlocked("com/example/legacy/Removed"), "user block rule");
            // Built-in rules still work after the overlay.
            Assert.equal("net/neoforged/fml/common/Mod",
                    table.mapClass("net/minecraftforge/fml/common/Mod"), "built-in rules survive the overlay");
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    public static void testBundledMappingResourceIsLoaded() {
        MappingTable table = BuiltinMappings.get();
        Assert.notNull(table.getNotes().get("neoforge"), "table notes");
        Assert.contains(table.getName(), "neoforge-21.1", "table version name");
        Assert.isTrue(table.getPrefixes().size() >= 10, "prefix rules present");
    }

    public static void testBootstrapExposesTheSameTableAsTheEngine() throws Exception {
        Bootstrap.initializeRuntime(null);
        Assert.notNull(Bootstrap.mappingTable(), "bootstrap table");
        Assert.notNull(Bootstrap.remapEngine(), "bootstrap engine");
        Assert.equal(Bootstrap.mappingTable().mapClass("net/minecraftforge/fml/common/Mod"),
                Bootstrap.remapEngine().mapBinaryName("net.minecraftforge.fml.common.Mod").replace('.', '/'),
                "binary name mapping agrees with the table");
        Assert.isTrue(Bootstrap.getPlaceholderCount() > 0, "placeholder library populated");
        Assert.notEqual(null, AsmSupport.level(), "ASM level detected");
    }

    public static void testDumpMappingsThroughTheApi() {
        dev.forgebridge.api.ForgeBridgeApi.getVersion();
        Assert.equal("net.neoforged.neoforge.common.NeoForge",
                dev.forgebridge.api.ForgeBridgeApi.mapClassName("net.minecraftforge.common.MinecraftForge"),
                "public API maps class names");
        List<String> hooks = dev.forgebridge.compat.CrossModRegistry.hooks();
        Assert.notNull(hooks, "hook list never null");
    }
}

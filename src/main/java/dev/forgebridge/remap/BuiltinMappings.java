package dev.forgebridge.remap;

/**
 * The built-in MinecraftForge 1.21.1 to NeoForge 21.1 mapping set.
 *
 * <p>NeoForge is a hard fork of Forge 1.20.1 that renamed its own packages. The bulk of
 * the translation is therefore a package prefix rewrite, with a comparatively small set
 * of exceptions:</p>
 *
 * <ul>
 *   <li>{@code net.minecraftforge.fml}       -&gt; {@code net.neoforged.fml}</li>
 *   <li>{@code net.minecraftforge.eventbus}  -&gt; {@code net.neoforged.bus}</li>
 *   <li>{@code net.minecraftforge.api}       -&gt; {@code net.neoforged.api}</li>
 *   <li>{@code net.minecraftforge.forgespi}  -&gt; {@code net.neoforged.neoforgespi}</li>
 *   <li>{@code net.minecraftforge.coremod}   -&gt; {@code net.neoforged.coremod}</li>
 *   <li>everything else                      -&gt; {@code net.neoforged.neoforge.*}</li>
 * </ul>
 *
 * <p>On top of the prefix rewrite the table carries class renames (for example
 * {@code MinecraftForge -> NeoForge}), field renames for the {@code BuiltInRegistries}
 * flattening, and a list of API surfaces that cannot be translated by renaming alone.
 * The latter are not silently rewritten: they are reported by the patcher and handled by
 * the runtime shims in {@code dev.forgebridge.compat}.</p>
 */
public final class BuiltinMappings {

    /** Version tag exposed in logs and in the jar manifest. */
    public static final String TABLE_VERSION = "forge-1.21.1->neoforge-21.1.176";

    private static volatile MappingTable cached;

    private BuiltinMappings() {
    }

    public static MappingTable get() {
        MappingTable local = cached;
        if (local == null) {
            synchronized (BuiltinMappings.class) {
                local = cached;
                if (local == null) {
                    local = build();
                    cached = local;
                }
            }
        }
        return local;
    }

    private static MappingTable build() {
        MappingTable table = new MappingTable();
        table.setName(TABLE_VERSION);
        table.note("forge", "1.21.1");
        table.note("neoforge", "21.1.176");
        table.note("minecraft", "1.21.1");

        // ------------------------------------------------------------------
        // Package prefixes. Order matters: the first match wins, so the
        // relocated sub-trees have to come before the catch-all.
        // ------------------------------------------------------------------
        table.addPrefix("net/minecraftforge/fml/", "net/neoforged/fml/", "FML moved out of net.minecraftforge");
        table.addPrefix("net/minecraftforge/eventbus/", "net/neoforged/bus/", "event bus became net.neoforged.bus");
        table.addPrefix("net/minecraftforge/api/", "net/neoforged/api/", "dist markers moved to net.neoforged.api");
        table.addPrefix("net/minecraftforge/forgespi/", "net/neoforged/neoforgespi/", "SPI package renamed");
        table.addPrefix("net/minecraftforge/coremod/", "net/neoforged/coremod/", "coremod API renamed");
        table.addPrefix("net/minecraftforge/access/", "net/neoforged/accesstransformer/", "access transformer API renamed");
        table.addPrefix("net/minecraftforge/jarmod/", "net/neoforged/jarmod/", "jar-in-jar helpers renamed");
        table.addPrefix("net/minecraftforge/jarsplitter/", "net/neoforged/jarsplitter/", "jar splitter renamed");
        table.addPrefix("net/minecraftforge/unsafe/", "net/neoforged/unsafe/", "unsafe helpers renamed");
        table.addPrefix("net/minecraftforge/versions/", "net/neoforged/versions/", "version helpers renamed");
        table.addPrefix("net/minecraftforge/", "net/neoforged/neoforge/", "mod API moved under net.neoforged.neoforge");

        // ------------------------------------------------------------------
        // Class level renames inside the rewritten tree.
        // ------------------------------------------------------------------
        table.addClass("net/minecraftforge/common/MinecraftForge", "net/neoforged/neoforge/common/NeoForge");
        table.addClass("net/minecraftforge/common/ForgeMod", "net/neoforged/neoforge/common/NeoForgeMod");
        table.addClass("net/minecraftforge/common/ForgeHooks", "net/neoforged/neoforge/common/CommonHooks");

        // ForgeConfigSpec -> ModConfigSpec (same shape, new simple name).
        String spec = "net/minecraftforge/common/ForgeConfigSpec";
        String newSpec = "net/neoforged/neoforge/common/ModConfigSpec";
        table.addClass(spec, newSpec);
        table.addClass(spec + "$Builder", newSpec + "$Builder");
        table.addClass(spec + "$ConfigValue", newSpec + "$ConfigValue");
        table.addClass(spec + "$ValueSpec", newSpec + "$ValueSpec");
        table.addClass(spec + "$IntValue", newSpec + "$IntValue");
        table.addClass(spec + "$LongValue", newSpec + "$LongValue");
        table.addClass(spec + "$DoubleValue", newSpec + "$DoubleValue");
        table.addClass(spec + "$BooleanValue", newSpec + "$BooleanValue");
        table.addClass(spec + "$EnumValue", newSpec + "$EnumValue");
        table.addClass(spec + "$Config", newSpec + "$Config");
        table.addClass(spec + "$Pair", newSpec + "$Pair");

        // EventBusSubscriber became a top level annotation in NeoForge.
        table.addClass("net/minecraftforge/fml/common/Mod$EventBusSubscriber", "net/neoforged/fml/common/EventBusSubscriber");
        table.addClass("net/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus", "net/neoforged/fml/common/EventBusSubscriber$Bus");

        // Registration helpers that were replaced by bridge implementations.
        table.addClass("net/minecraftforge/registries/ForgeRegistries", "net/minecraft/core/registries/BuiltInRegistries");
        table.addClass("net/minecraftforge/registries/RegistryEvent", "dev/forgebridge/compat/registry/RegistryEvent");
        table.addClass("net/minecraftforge/registries/RegistryEvent$Register", "dev/forgebridge/compat/registry/RegistryEvent$Register");
        table.addClass("net/minecraftforge/registries/RegistryEvent$NewRegistry", "dev/forgebridge/compat/registry/RegistryEvent$NewRegistry");
        table.addClass("net/minecraftforge/registries/IForgeRegistry", "dev/forgebridge/compat/registry/LegacyRegistry");
        table.addClass("net/minecraftforge/registries/IForgeRegistryEntry", "dev/forgebridge/compat/registry/LegacyRegistryEntry");
        table.addClass("net/minecraftforge/registries/RegistryManager", "dev/forgebridge/compat/registry/LegacyRegistryManager");

        // Events that exist under a different simple name.
        table.addClass("net/minecraftforge/event/entity/player/PlayerInteractEvent", "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent");
        table.addClass("net/minecraftforge/client/event/InputEvent$KeyInput", "net/neoforged/neoforge/client/event/InputEvent$Key");

        // ------------------------------------------------------------------
        // Field renames: ForgeRegistries.ITEMS is BuiltInRegistries.ITEM etc.
        // The descriptor wildcard keeps these robust across generic changes.
        // ------------------------------------------------------------------
        String bir = "net/minecraft/core/registries/BuiltInRegistries";
        table.addField(bir, "ITEMS", "*", "ITEM");
        table.addField(bir, "BLOCKS", "*", "BLOCK");
        table.addField(bir, "ENTITIES", "*", "ENTITY_TYPE");
        table.addField(bir, "TILE_ENTITIES", "*", "BLOCK_ENTITY_TYPE");
        table.addField(bir, "SOUND_EVENTS", "*", "SOUND_EVENT");
        table.addField(bir, "BIOMES", "*", "BIOME");
        table.addField(bir, "EFFECTS", "*", "MOB_EFFECT");
        table.addField(bir, "ENCHANTMENTS", "*", "ENCHANTMENT");
        table.addField(bir, "POTIONS", "*", "POTION");
        table.addField(bir, "PARTICLES", "*", "PARTICLE_TYPE");
        table.addField(bir, "CREATIVE_MODE_TABS", "*", "CREATIVE_MODE_TAB");
        table.addField(bir, "RECIPES", "*", "RECIPE_TYPE");
        table.addField(bir, "FLUIDS", "*", "FLUID");
        table.addField(bir, "MENUS", "*", "MENU");
        table.addField(bir, "ATTRIBUTES", "*", "ATTRIBUTE");
        table.addField(bir, "MOB_EFFECTS", "*", "MOB_EFFECT");

        // ------------------------------------------------------------------
        // API surfaces that renaming cannot fix. A full implementation lives in
        // dev.forgebridge.compat; anything not handled there is reported loudly
        // instead of producing a LinkageError later.
        // ------------------------------------------------------------------
        table.block("net/minecraftforge/common/capabilities/Capability",
                "NeoForge 21.1 replaces Capability with CapabilityToken/BlockCapability; handled by CapabilityBridge where possible");
        table.block("net/minecraftforge/common/capabilities/CapabilityManager",
                "removed in NeoForge 21.1 (capabilities are registered through RegisterCapabilitiesEvent)");
        table.block("net/minecraftforge/common/capabilities/ICapabilityProvider",
                "replaced by the CapabilityToken lookup API in NeoForge 21.1");
        table.block("net/minecraftforge/network/simple/SimpleChannel",
                "NeoForge networking is payload based (PayloadRegistrar + RegisterPayloadHandlersEvent); see docs/INCOMPATIBILITIES.md");
        table.block("net/minecraftforge/network/NetworkRegistry",
                "removed in NeoForge 21.1; channel registration happens through RegisterPayloadHandlersEvent");
        table.block("net/minecraftforge/network/NetworkInitialization",
                "removed in NeoForge 21.1");
        table.block("net/minecraftforge/network/NetworkDirection",
                "removed in NeoForge 21.1");
        table.block("net/minecraftforge/fml/ModLoadingContext",
                "removed in NeoForge 21.x; use constructor injection of ModContainer/IEventBus");
        table.block("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext",
                "removed in NeoForge 21.x; use constructor injection of IEventBus/ModContainer");
        table.block("net/minecraftforge/eventbus/api/IEventBus",
                "type moved to net.neoforged.bus.api.IEventBus - remapped, listed here for reporting only");

        return table;
    }
}

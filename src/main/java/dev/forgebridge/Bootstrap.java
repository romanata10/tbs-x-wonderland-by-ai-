package dev.forgebridge;

import dev.forgebridge.api.placeholder.PlaceholderLibrary;
import dev.forgebridge.compat.CompatConfig;
import dev.forgebridge.compat.CompatManager;
import dev.forgebridge.compat.ModPresence;
import dev.forgebridge.events.ConsumerProxy;
import dev.forgebridge.events.EventBusBridge;
import dev.forgebridge.jar.PatchOptions;
import dev.forgebridge.jar.PatchWorkspace;
import dev.forgebridge.registries.DeferredRegisterBridge;
import dev.forgebridge.compat.registry.RegistryBridge;
import dev.forgebridge.remap.AsmSupport;
import dev.forgebridge.remap.BuiltinMappings;
import dev.forgebridge.remap.ClassRemapEngine;
import dev.forgebridge.remap.MappingLoader;
import dev.forgebridge.remap.MappingTable;
import dev.forgebridge.remap.RedirectTable;
import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

import java.io.File;
import java.util.List;

/**
 * Runtime wiring shared by the launcher service, the mod entry point and the CLI.
 *
 * <p>Initialisation is deliberately lazy and re-entrant: the transformation service may ask for
 * the mapping table before FML knows where the game directory is, and the mod entry point runs
 * later with better information. Whichever happens first wins, and the other one upgrades the
 * state (including the game directory) without dropping anything.</p>
 */
public final class Bootstrap {

    private static volatile MappingTable table;
    private static volatile ClassRemapEngine engine;
    private static volatile RedirectTable redirects;
    private static volatile PlaceholderLibrary placeholders;
    private static volatile CompatConfig config;
    private static volatile File gameDir;
    private static volatile Object modEventBus;
    private static volatile Object modContainer;
    private static volatile boolean setupListenerInstalled;
    private static volatile boolean commonSetupComplete;

    private Bootstrap() {
    }

    /** Prepares mappings, placeholders and configuration for the given game directory. */
    public static synchronized void initializeRuntime(File directory) {
        if (directory != null) {
            gameDir = directory;
        }
        if (table == null) {
            File effective = effectiveGameDir();
            placeholders = PlaceholderLibrary.defaultLibrary();
            table = MappingLoader.load(mappingsConfigDir(effective), Bootstrap.class.getClassLoader());
            placeholders.applyTo(table);
            redirects = RedirectTable.builtin();
            engine = new ClassRemapEngine(table, redirects);
            BridgeLog.info("Loaded " + placeholders.size() + " placeholder definition(s) for removed Forge API");
        }
        if (config == null) {
            config = CompatConfig.load(effectiveGameDir());
            BridgeLog.setVerbose(config.isVerbose() || Boolean.getBoolean(BridgeConstants.PROP_DEBUG));
        }
    }

    private static File effectiveGameDir() {
        File directory = gameDir;
        if (directory != null) {
            return directory;
        }
        Object fromFml = SafeReflect.getStatic(
                SafeReflect.findClass("net.neoforged.fml.loading.FMLPaths"), "GAMEDIR");
        Object path = fromFml == null ? null : SafeReflect.invoke(fromFml, "get");
        if (path != null) {
            directory = new File(String.valueOf(path));
        } else {
            directory = new File(System.getProperty("user.dir", "."));
        }
        gameDir = directory;
        return directory;
    }

    private static File mappingsConfigDir(File directory) {
        File config = new File(new File(directory, BridgeConstants.CONFIG_DIR), BridgeConstants.MAPPINGS_DIR);
        if (!config.exists()) {
            config.mkdirs();
        }
        return config;
    }

    public static MappingTable mappingTable() {
        if (table == null) {
            initializeRuntime(null);
        }
        return table;
    }

    public static ClassRemapEngine remapEngine() {
        if (engine == null) {
            initializeRuntime(null);
        }
        return engine;
    }

    public static PlaceholderLibrary placeholders() {
        if (placeholders == null) {
            initializeRuntime(null);
        }
        return placeholders;
    }

    public static CompatConfig config() {
        if (config == null) {
            initializeRuntime(null);
        }
        return config;
    }

    public static File gameDirectory() {
        return effectiveGameDir();
    }

    public static String pipelineName() {
        return AsmSupport.level().getDescription();
    }

    /** Builds the options used for every patch operation. */
    public static PatchOptions patchOptions() {
        PlaceholderLibrary library = placeholders();
        PatchOptions options = new PatchOptions();
        options.setBridgeVersion(BridgeConstants.VERSION);
        options.setNeoforgeDependencyRange("[21.1.0,)");
        options.setLoaderVersionRange("[4,)");
        if (library != null) {
            options.setInjectedEntries(library.asClassEntries());
        }
        CompatConfig compat = config();
        if (compat != null && !compat.isPlaceholders()) {
            options.setInjectedEntries(new java.util.LinkedHashMap<String, byte[]>());
        }
        return options;
    }

    // ------------------------------------------------------------------
    // Mod life cycle
    // ------------------------------------------------------------------

    /**
     * Called from the mod constructor on NeoForge 21.1
     * ({@code public ForgeBridge(IEventBus, ModContainer)}).
     */
    public static void onModConstructed(Object eventBus, Object container) {
        modEventBus = eventBus;
        modContainer = container;
        initializeRuntime(gameDirectoryFromFml());
        BridgeLog.info(ForgeBridgeApiStatus.BANNER);
        BridgeLog.info("State: mappings=" + mappingTable().describe());
        BridgeLog.info("State: pipeline=" + pipelineName());

        CompatConfig compat = config();
        File directory = effectiveGameDir();

        if (compat.isPatchOnStartup() && modEventBus != null) {
            File modsDir = new File(directory, "mods");
            if (modsDir.isDirectory()) {
                boolean automatic = !"false".equalsIgnoreCase(
                        System.getProperty(BridgeConstants.PROP_AUTOPATCH, "true"));
                PatchWorkspace workspace = new PatchWorkspace(remapEngine(), patchOptions(), !automatic);
                PatchWorkspace.Summary summary = workspace.prepare(directory, modsDir);
                if (!summary.getPatched().isEmpty()) {
                    BridgeLog.warn("Prepared " + summary.getPatched().size()
                            + " legacy jar(s) for the next launch: " + summary.getPatched());
                }
            }
        }

        if (compat.isRegistryBridge()
                && !Boolean.getBoolean(BridgeConstants.PROP_DISABLE_REGISTRY_BRIDGE)) {
            RegistryBridge.install(modEventBus);
        }
        if (Boolean.getBoolean(BridgeConstants.PROP_DISABLE_EVENT_BRIDGE)) {
            BridgeLog.warn("Event bridge disabled by -D" + BridgeConstants.PROP_DISABLE_EVENT_BRIDGE);
        } else {
            installEventBridgeHooks(modEventBus, container);
            installSetupListener(modEventBus);
        }
    }

    private static File gameDirectoryFromFml() {
        Object wrapper = SafeReflect.getStatic(
                SafeReflect.findClass("net.neoforged.fml.loading.FMLPaths"), "GAMEDIR");
        Object path = wrapper == null ? null : SafeReflect.invoke(wrapper, "get");
        if (path != null) {
            return new File(String.valueOf(path));
        }
        return effectiveGameDir();
    }

    /**
     * Subscribes the mod's own listeners. The bridge's listeners live here rather than in the mod
     * class so that the entry point stays a one-liner and can be verified without a game.
     */
    private static void installEventBridgeHooks(Object eventBus, Object container) {
        DeferredRegisterBridge.attach(null, eventBus);
        BridgeLog.debug("Event bridge hooks installed on the mod bus");
    }

    /** Registers the common-setup callback reflectively (no compile-time FML dependency). */
    private static void installSetupListener(Object eventBus) {
        if (setupListenerInstalled || eventBus == null) {
            return;
        }
        Class<?> setupEvent = SafeReflect.findClass("net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent");
        if (setupEvent == null) {
            BridgeLog.warn("FMLCommonSetupEvent not found; compatibility profiles will not run");
            return;
        }
        Object consumer = ConsumerProxy.create(setupEvent.getClassLoader(), "CommonSetup", new ConsumerProxy.Callback() {
            public void accept(Object event) {
                onCommonSetup();
            }
        });
        if (consumer == null) {
            return;
        }
        SafeReflect.invoke(eventBus, "addListener", setupEvent, consumer);
        setupListenerInstalled = true;
        BridgeLog.debug("Common setup listener installed");
    }

    /** Runs the compatibility profiles; idempotent. */
    public static void onCommonSetup() {
        if (commonSetupComplete) {
            return;
        }
        commonSetupComplete = true;
        CompatConfig compat = config();
        File directory = effectiveGameDir();
        ModPresence presence = new ModPresence(new File(directory, "mods"), compat.getExtraModIds());
        CompatManager.applyAll(directory, compat, presence);
        BridgeLog.info("Bridge summary: " + describe());
        List<String> listeners = EventBusBridge.describeLegacyListeners();
        if (!listeners.isEmpty()) {
            BridgeLog.debug("Legacy listeners: " + listeners);
        }
    }

    /** Text used by crash reports and {@code /forgebridge status}. */
    public static String describe() {
        MappingTable mappings = mappingTable();
        return "ForgeBridge " + BridgeConstants.VERSION
                + " | mappings " + (mappings == null ? "unavailable" : mappings.getName())
                + " | pipeline " + pipelineName()
                + " | registry bridge " + (RegistryBridge.isInstalled() ? "on" : "off")
                + " | legacy listeners " + EventBusBridge.describeLegacyListeners().size();
    }

    public static Object getModEventBus() {
        return modEventBus;
    }

    public static Object getModContainer() {
        return modContainer;
    }

    public static int getPlaceholderCount() {
        PlaceholderLibrary library = placeholders();
        return library == null ? 0 : library.size();
    }

    /** Small holder so the banner text does not clutter the wiring code. */
    static final class ForgeBridgeApiStatus {
        static final String BANNER =
                "ForgeBridge " + BridgeConstants.VERSION + " - Forge 1.21.1 mods on NeoForge 21.1 ("
                        + BuiltinMappings.TABLE_VERSION + ")";

        private ForgeBridgeApiStatus() {
        }
    }
}

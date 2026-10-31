package dev.forgebridge.cli;

import dev.forgebridge.Bootstrap;
import dev.forgebridge.BridgeConstants;
import dev.forgebridge.api.placeholder.PlaceholderLibrary;
import dev.forgebridge.jar.JarPatcher;
import dev.forgebridge.jar.JarScanner;
import dev.forgebridge.jar.PatchOptions;
import dev.forgebridge.jar.PatchReport;
import dev.forgebridge.jar.PatchWorkspace;
import dev.forgebridge.remap.AsmSupport;
import dev.forgebridge.remap.ClassRemapEngine;
import dev.forgebridge.remap.MappingTable;
import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Command line front end.
 *
 * <p>Two audiences: users who prefer preparing a {@code mods/} directory offline, and developers
 * checking what the bridge would do to a given jar. Everything the launcher service does is
 * reachable from here, which also makes the translation testable without launching a game.</p>
 *
 * <pre>
 *   forgebridge info
 *   forgebridge scan      &lt;jar&gt;...
 *   forgebridge prepare   &lt;modsDir&gt; [--game-dir DIR] [--dry-run]
 *   forgebridge remap-jar &lt;in.jar&gt; &lt;out.jar&gt;
 *   forgebridge remap-class &lt;in.class&gt; &lt;out.class&gt;
 *   forgebridge placeholders &lt;outDir&gt;
 *   forgebridge dump-mappings [name...]
 * </pre>
 */
public final class BridgeCli {

    private BridgeCli() {
    }

    public static void main(String[] args) {
        int status;
        try {
            status = run(args);
        } catch (Throwable error) {
            System.err.println("[ForgeBridge] fatal: " + error);
            error.printStackTrace(System.err);
            status = 2;
        }
        if (status != 0) {
            System.exit(status);
        }
    }

    public static int run(String[] args) {
        if (args.length == 0) {
            usage();
            return 1;
        }
        String command = args[0];
        List<String> positional = new ArrayList<String>();
        boolean dryRun = false;
        File gameDir = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--dry-run".equals(arg)) {
                dryRun = true;
            } else if ("--verbose".equals(arg)) {
                BridgeLog.setVerbose(true);
            } else if ("--game-dir".equals(arg) && i + 1 < args.length) {
                gameDir = new File(args[++i]);
            } else {
                positional.add(arg);
            }
        }
        BridgeLog.setVerbose(BridgeLog.isVerbose() || Boolean.getBoolean(BridgeConstants.PROP_DEBUG));

        if ("info".equals(command)) {
            return info();
        }
        if ("scan".equals(command)) {
            return scan(positional);
        }
        if ("prepare".equals(command)) {
            return prepare(positional, gameDir, dryRun);
        }
        if ("remap-jar".equals(command)) {
            return remapJar(positional);
        }
        if ("remap-class".equals(command)) {
            return remapClass(positional);
        }
        if ("placeholders".equals(command)) {
            return placeholders(positional);
        }
        if ("dump-mappings".equals(command)) {
            return dumpMappings(positional);
        }
        usage();
        return 1;
    }

    private static void usage() {
        System.out.println("ForgeBridge " + BridgeConstants.VERSION + " - Forge 1.21.1 -> NeoForge 21.1 translator");
        System.out.println("usage:");
        System.out.println("  info                                   show the active mapping set and pipeline");
        System.out.println("  scan <jar>...                           report whether jars are legacy Forge mods");
        System.out.println("  prepare <modsDir> [--game-dir DIR] [--dry-run]");
        System.out.println("                                          translate legacy jars in place (originals are kept)");
        System.out.println("  remap-jar <in.jar> <out.jar>             translate one jar to a new file");
        System.out.println("  remap-class <in.class> <out.class>       translate one class file");
        System.out.println("  placeholders <outDir>                    write the generated placeholder classes");
        System.out.println("  dump-mappings [class...]                 show mapping rules");
    }

    private static int info() {
        Bootstrap.initializeRuntime(null);
        System.out.println("ForgeBridge " + BridgeConstants.VERSION);
        System.out.println("mapping set   : " + BridgeConstants.MAPPING_SET);
        System.out.println("pipeline      : " + AsmSupport.level().getDescription());
        MappingTable table = Bootstrap.mappingTable();
        System.out.println("rules         : " + table.describe());
        System.out.println("placeholders  : " + Bootstrap.getPlaceholderCount());
        System.out.println("game dir      : " + Bootstrap.gameDirectory());
        System.out.println("registry keys : " + dev.forgebridge.util.StringUtil.join(", ",
                dev.forgebridge.compat.registry.RegistryAccess.knownKeys()));
        return 0;
    }

    private static int scan(List<String> jars) {
        if (jars.isEmpty()) {
            System.out.println("scan needs at least one jar");
            return 1;
        }
        int legacy = 0;
        for (int i = 0; i < jars.size(); i++) {
            File jar = new File(String.valueOf(jars.get(i)));
            if (!jar.isFile()) {
                System.out.println("missing: " + jar);
                continue;
            }
            try {
                JarScanner.ScanResult result = JarScanner.scan(jar);
                System.out.println("== " + jar.getName());
                System.out.println("   legacy forge : " + result.isLegacyForge() + " (" + result.reason() + ")");
                System.out.println("   mod id       : " + result.getModId() + " " + result.getVersion());
                System.out.println("   entries      : " + result.getEntryCount() + " (" + result.getClassCount() + " classes)");
                System.out.println("   descriptor   : " + result.getDescriptorPath());
                if (!result.getSampleReferences().isEmpty()) {
                    System.out.println("   references   : " + result.getSampleReferences());
                }
                if (result.isLegacyForge()) {
                    legacy++;
                }
            } catch (IOException error) {
                System.out.println("failed to read " + jar + ": " + error);
            }
        }
        System.out.println(legacy + " of " + jars.size() + " jar(s) need translation");
        return legacy > 0 ? 0 : 0;
    }

    private static int prepare(List<String> positional, File gameDir, boolean dryRun) {
        if (positional.isEmpty()) {
            System.out.println("prepare needs a mods directory");
            return 1;
        }
        File modsDir = new File(String.valueOf(positional.get(0)));
        if (!modsDir.isDirectory()) {
            System.out.println("not a directory: " + modsDir);
            return 1;
        }
        File effectiveGame = gameDir == null ? modsDir.getAbsoluteFile().getParentFile() : gameDir;
        Bootstrap.initializeRuntime(effectiveGame);
        PatchWorkspace workspace = new PatchWorkspace(Bootstrap.remapEngine(), Bootstrap.patchOptions(), dryRun);
        PatchWorkspace.Summary summary = workspace.prepare(effectiveGame, modsDir);
        System.out.println("patched : " + summary.getPatched());
        System.out.println("skipped : " + summary.getSkipped());
        System.out.println("failed  : " + summary.getFailed());
        if (summary.getReportFile() != null) {
            System.out.println("report  : " + summary.getReportFile().getAbsolutePath());
        }
        return summary.getFailed().isEmpty() ? 0 : 2;
    }

    private static int remapJar(List<String> positional) {
        if (positional.size() < 2) {
            System.out.println("remap-jar needs <in.jar> <out.jar>");
            return 1;
        }
        File input = new File(String.valueOf(positional.get(0)));
        File output = new File(String.valueOf(positional.get(1)));
        Bootstrap.initializeRuntime(output.getAbsoluteFile().getParentFile());
        try {
            ClassRemapEngine engine = Bootstrap.remapEngine();
            PatchReport report = JarPatcher.patch(input, output, engine, Bootstrap.patchOptions());
            System.out.print(report.toText());
            return 0;
        } catch (IOException error) {
            System.out.println("failed: " + error);
            return 2;
        }
    }

    private static int remapClass(List<String> positional) {
        if (positional.size() < 2) {
            System.out.println("remap-class needs <in.class> <out.class>");
            return 1;
        }
        try {
            File input = new File(String.valueOf(positional.get(0)));
            File output = new File(String.valueOf(positional.get(1)));
            Bootstrap.initializeRuntime(output.getAbsoluteFile().getParentFile());
            ClassRemapEngine.Result result = Bootstrap.remapEngine().remap(IoUtil.readFile(input));
            if (result.hasError()) {
                System.out.println("failed: " + result.getError());
                return 2;
            }
            IoUtil.writeFile(output, result.getBytes());
            System.out.println("remapped " + input.getName() + " -> " + output.getName()
                    + " (" + result.getLevel().getDescription() + ", changed=" + result.isChanged() + ")");
            if (!result.getAppliedRedirects().isEmpty()) {
                System.out.println("redirects: " + result.getAppliedRedirects());
            }
            if (!result.getResidualReferences().isEmpty()) {
                System.out.println("residual Forge references: " + result.getResidualReferences());
            }
            return 0;
        } catch (IOException error) {
            System.out.println("failed: " + error);
            return 2;
        }
    }

    private static int placeholders(List<String> positional) {
        if (positional.isEmpty()) {
            System.out.println("placeholders needs an output directory");
            return 1;
        }
        File outDir = new File(String.valueOf(positional.get(0)));
        PlaceholderLibrary library = PlaceholderLibrary.defaultLibrary();
        Map<String, byte[]> entries = library.asClassEntries();
        int written = 0;
        for (Object element : entries.entrySet()) {
            Map.Entry entry = (Map.Entry) element;
            String entryName = String.valueOf(entry.getKey());
            try {
                IoUtil.writeFile(new File(outDir, entryName), (byte[]) entry.getValue());
                written++;
            } catch (IOException error) {
                System.out.println("failed to write " + entryName + ": " + error);
            }
        }
        System.out.println("wrote " + written + " placeholder class file(s) to " + outDir.getAbsolutePath());
        System.out.print(library.describe());
        return 0;
    }

    private static int dumpMappings(List<String> positional) {
        Bootstrap.initializeRuntime(null);
        MappingTable table = Bootstrap.mappingTable();
        System.out.println(table.describe());
        if (positional.isEmpty()) {
            for (int i = 0; i < table.getPrefixes().size(); i++) {
                MappingTable.PrefixRule rule = (MappingTable.PrefixRule) table.getPrefixes().get(i);
                System.out.println("prefix " + rule.getFrom() + " -> " + rule.getTo() + "   # " + rule.getDescription());
            }
            for (Object element : table.getExactClasses().entrySet()) {
                Map.Entry entry = (Map.Entry) element;
                System.out.println("class  " + entry.getKey() + " -> " + entry.getValue());
            }
            return 0;
        }
        for (int i = 0; i < positional.size(); i++) {
            String name = String.valueOf(positional.get(i)).replace('.', '/');
            System.out.println(name + " -> " + table.mapClass(name));
        }
        return 0;
    }

    /** Test hook: runs the CLI without letting it call {@code System.exit}. */
    public static int runSafely(String... args) {
        try {
            return run(args);
        } catch (Throwable error) {
            System.err.println("[ForgeBridge] " + error);
            return 2;
        }
    }
}

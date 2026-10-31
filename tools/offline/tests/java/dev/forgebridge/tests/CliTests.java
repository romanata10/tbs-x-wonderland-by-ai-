package dev.forgebridge.tests;

import dev.forgebridge.cli.BridgeCli;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;

/** The CLI is the supported way to translate jars without launching the game. */
public final class CliTests {

    private CliTests() {
    }

    private static String run(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true));
            BridgeCli.runSafely(args);
        } finally {
            System.setOut(original);
        }
        return new String(buffer.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
    }

    private static int status(String... args) {
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true));
            return BridgeCli.runSafely(args);
        } finally {
            System.setOut(original);
        }
    }

    public static void testUsageIsPrintedWithoutArguments() {
        String output = run(new String[0]);
        Assert.contains(output, "usage:", "usage text printed");
        Assert.contains(output, "remap-jar", "commands listed");
        Assert.equalInt(1, status(new String[0]), "missing command is an error");
    }

    public static void testUnknownCommandFails() {
        Assert.equalInt(1, status("frobnicate"), "unknown command");
    }

    public static void testInfoDescribesTheMappingSet() {
        String output = run("info");
        Assert.contains(output, "ForgeBridge", "product name");
        Assert.contains(output, "forge-1.20.1->neoforge-21.1", "mapping set");
        Assert.contains(output, "pipeline", "pipeline reported");
        Assert.contains(output, "registry keys", "registry keys listed");
        Assert.contains(output, "items", "registry key names");
    }

    public static void testScanReportsLegacyJars() throws Exception {
        File dir = Fixtures.tempDir("forgebridge-cli-scan");
        try {
            File legacy = Fixtures.legacyJar(dir, "examplemod-1.2.3.jar");
            String output = run("scan", legacy.getAbsolutePath());
            Assert.contains(output, "== examplemod-1.2.3.jar", "jar name");
            Assert.contains(output, "legacy forge : true", "legacy detection");
            Assert.contains(output, "mod id       : examplemod 1.2.3", "descriptor values");
            Assert.contains(output, "1 of 1 jar(s) need translation", "summary line");
            Assert.equalInt(1, status("scan"), "scan without a jar is a usage error");
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    public static void testRemapJarWritesATranslatedJar() throws Exception {
        File dir = Fixtures.tempDir("forgebridge-cli-remap");
        try {
            File input = Fixtures.legacyJar(dir, "examplemod.jar");
            File output = new File(dir, "examplemod-neoforge.jar");
            String text = run("remap-jar", input.getAbsolutePath(), output.getAbsolutePath());
            Assert.contains(text, "ForgeBridge patch report", "report printed");
            Assert.isTrue(output.isFile(), "output jar written");
            dev.forgebridge.jar.JarScanner.ScanResult scan = dev.forgebridge.jar.JarScanner.scan(output);
            Assert.isTrue(scan.isPatched(), "output is marked as patched");
            Assert.equalInt(1, status("remap-jar", input.getAbsolutePath()), "missing output argument");
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    public static void testRemapClassWritesARemappedClass() throws Exception {
        File dir = Fixtures.tempDir("forgebridge-cli-class");
        try {
            File input = new File(dir, "LegacyListener.class");
            dev.forgebridge.util.IoUtil.writeFile(input, Fixtures.legacyClass("com/example/LegacyListener"));
            File output = new File(dir, "LegacyListener.out.class");
            String text = run("remap-class", input.getAbsolutePath(), output.getAbsolutePath());
            Assert.contains(text, "remapped LegacyListener.class", "status line");
            Assert.contains(text, "redirects:", "redirects listed");
            Assert.isTrue(output.isFile(), "output class written");
            String latin = dev.forgebridge.util.IoUtil.readText(new java.io.FileInputStream(output));
            Assert.notContains(latin, "net/minecraftforge/registries/ForgeRegistries",
                    "legacy registry access rewritten on disk");
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    public static void testPlaceholdersCommandWritesLoadableClasses() throws Exception {
        File dir = Fixtures.tempDir("forgebridge-cli-placeholders");
        try {
            String text = run("placeholders", dir.getAbsolutePath());
            Assert.contains(text, "placeholder class file(s)", "summary");
            File stub = new File(dir, "dev/forgebridge/placeholders/fml/ModLoadingContext.class");
            Assert.isTrue(stub.isFile(), "stub written to disk");
            Assert.equalInt(1, status("placeholders"), "missing directory argument");
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    public static void testDumpMappingsListsRulesAndLookups() {
        String all = run("dump-mappings");
        Assert.contains(all, "prefix net/minecraftforge/fml/", "prefix rules listed");
        String one = run("dump-mappings", "net.minecraftforge.common.MinecraftForge");
        Assert.contains(one, "net/minecraftforge/common/MinecraftForge -> net/neoforged/neoforge/common/NeoForge",
                "single class lookup");
    }

    public static void testPrepareDryRunChangesNothing() throws Exception {
        File gameDir = Fixtures.tempDir("forgebridge-cli-prepare");
        try {
            File mods = new File(gameDir, "mods");
            Assert.isTrue(mods.mkdirs(), "mods directory");
            File legacy = Fixtures.legacyJar(mods, "examplemod.jar");
            long size = legacy.length();
            String text = run("prepare", mods.getAbsolutePath(), "--game-dir", gameDir.getAbsolutePath(), "--dry-run");
            Assert.contains(text, "[dry run]", "dry run is reported per jar");
            Assert.contains(text, "patched : [examplemod.jar [dry run]]", "summary lists the jar");
            Assert.equalInt((int) size, (int) legacy.length(), "jar untouched by the dry run");
            Assert.isTrue(dev.forgebridge.jar.JarScanner.scan(legacy).isLegacyForge(), "jar still legacy");
        } finally {
            Fixtures.deleteTree(gameDir);
        }
    }

    public static void testPrepareTranslatesTheModsDirectory() throws Exception {
        File gameDir = Fixtures.tempDir("forgebridge-cli-prepare2");
        try {
            File mods = new File(gameDir, "mods");
            Assert.isTrue(mods.mkdirs(), "mods directory");
            File legacy = Fixtures.legacyJar(mods, "examplemod.jar");
            String text = run("prepare", mods.getAbsolutePath(), "--game-dir", gameDir.getAbsolutePath());
            Assert.contains(text, "patched : [examplemod.jar", "jar patched");
            Assert.contains(text, "failed  : []", "no failures");
            Assert.contains(text, "report  :", "report path printed");
            Assert.isTrue(dev.forgebridge.jar.JarScanner.scan(legacy).isPatched(), "jar in mods is translated");
            File report = new File(new File(gameDir, "forgebridge"), "patch-report.txt");
            Assert.isTrue(report.isFile(), "report written into the game directory");
        } finally {
            Fixtures.deleteTree(gameDir);
        }
    }

    public static void testPrepareRejectsAMissingDirectory() {
        Assert.equalInt(1, status("prepare", "/definitely/not/here"), "missing mods directory is a usage error");
    }
}

package dev.forgebridge.tests;

import dev.forgebridge.Bootstrap;
import dev.forgebridge.jar.JarPatcher;
import dev.forgebridge.jar.JarScanner;
import dev.forgebridge.jar.ManifestRewriter;
import dev.forgebridge.jar.PatchOptions;
import dev.forgebridge.jar.PatchReport;
import dev.forgebridge.jar.PatchWorkspace;
import dev.forgebridge.remap.AsmSupport;
import dev.forgebridge.remap.ClassRemapEngine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** End to end jar translation: scan, patch, and the workspace protocol used at startup. */
public final class JarTests {

    private JarTests() {
    }

    private static String readEntry(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        Assert.notNull(entry, "entry " + name + " exists");
        InputStream in = zip.getInputStream(entry);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
        } finally {
            in.close();
        }
    }

    public static void testScannerRecognisesALegacyJar() throws Exception {
        File dir = Fixtures.tempDir("forgebridge-scan");
        try {
            File jar = Fixtures.legacyJar(dir, "examplemod-1.2.3.jar");
            JarScanner.ScanResult result = JarScanner.scan(jar);
            Assert.isTrue(result.isLegacyForge(), "legacy jar detected: " + result.reason());
            Assert.isTrue(result.hasDescriptor(), "descriptor found");
            Assert.equal("META-INF/mods.toml", result.getDescriptorPath(), "descriptor path");
            Assert.equal("examplemod", result.getModId(), "mod id from descriptor");
            Assert.equal("1.2.3", result.getVersion(), "version from descriptor");
            Assert.isFalse(result.isPatched(), "fresh jar is not patched");
            Assert.isTrue(result.getClassCount() >= 1, "class count");
            Assert.isTrue(result.getLegacyClassCount() >= 1, "legacy class count");
            Assert.equalInt(1, result.getMixinConfigs().size(), "mixin config discovered from the manifest");
            Assert.equal("examplemod.mixins.json", result.getMixinConfigs().get(0), "mixin config name");
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    public static void testScannerAcceptsANativeNeoForgeJar() throws Exception {
        File dir = Fixtures.tempDir("forgebridge-native");
        try {
            File jar = new File(dir, "native-1.0.jar");
            java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(jar));
            try {
                Fixtures.put(out, "META-INF/neoforge.mods.toml",
                        "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n\n[[mods]]\n    modId=\"native_mod\"\n");
            } finally {
                out.close();
            }
            JarScanner.ScanResult result = JarScanner.scan(jar);
            Assert.isFalse(result.isLegacyForge(), "native jar is not legacy: " + result.reason());
            Assert.isTrue(result.hasDescriptor(), "neoforge descriptor found");
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    public static void testPatchProducesANeoForgeJar() throws Exception {
        AsmSupport.override(AsmSupport.Level.FULL);
        File dir = Fixtures.tempDir("forgebridge-patch");
        try {
            File input = Fixtures.legacyJar(dir, "examplemod-1.2.3.jar");
            File output = new File(dir, "examplemod-1.2.3-neoforge.jar");
            PatchReport report = JarPatcher.patch(input, output, Bootstrap.remapEngine(), Bootstrap.patchOptions());

            Assert.isTrue(output.isFile(), "output jar written");
            Assert.isTrue(report.isLegacyMod(), "report marks the input as legacy");
            Assert.equal("examplemod", report.getDetectedModId(), "report mod id");
            Assert.isTrue(report.getClassesRemapped() >= 1, "at least one class remapped");

            ZipFile zip = new ZipFile(output);
            try {
                // Manifest: rewritten and marked, original attributes preserved.
                byte[] manifest = readEntry(zip, "META-INF/MANIFEST.MF").getBytes(java.nio.charset.Charset.forName("UTF-8"));
                Assert.isTrue(ManifestRewriter.isPatched(manifest), "manifest carries the patch marker");
                String manifestText = new String(manifest, java.nio.charset.Charset.forName("UTF-8"));
                Assert.contains(manifestText, "MixinConfigs: examplemod.mixins.json", "manifest attributes preserved");

                // Descriptor: forge -> neoforge.
                String toml = readEntry(zip, "META-INF/mods.toml");
                Assert.contains(toml, "loaderVersion=\"[4,)\"", "loader version");
                Assert.contains(toml, "modId=\"neoforge\"", "neoforge dependency");

                // Tags: forge namespace relocated to the common namespace.
                String tag = readEntry(zip, "data/c/tags/items/gems.json");
                Assert.contains(tag, "#c:gems", "tag reference translated");
                Assert.notContains(tag, "#forge:", "legacy tag reference gone");

                // Recipe conditions.
                String recipe = readEntry(zip, "data/examplemod/recipe/diamond_block.json");
                Assert.contains(recipe, "\"neoforge:mod_loaded\"", "recipe condition translated");

                // Mixin config.
                String mixin = readEntry(zip, "examplemod.mixins.json");
                Assert.contains(mixin, "\"JAVA_21\"", "mixin compatibility level upgraded");
                Assert.notContains(mixin, "JAVA_17", "old mixin level gone");

                // Signatures are dropped because patching invalidates them.
                Assert.isNull(zip.getEntry("META-INF/EXAMPLEMOD.SF"), "signature file dropped");

                // Service file renamed together with its content.
                Assert.isNull(zip.getEntry("META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider"),
                        "legacy service file name gone");
                String service = readEntry(zip, "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageProvider");
                Assert.contains(service, "net.neoforged.fml.javafmlmod.FMLModContainer",
                        "service provider class remapped");
                // A service file for a mod-owned interface must keep its own providers.
                String ownService = readEntry(zip, "META-INF/services/com.example.Extension");
                Assert.contains(ownService, "com.example.impl.LegacyExtension", "unrelated provider kept");

                // Placeholder stubs for removed APIs are injected.
                Assert.notNull(zip.getEntry("dev/forgebridge/placeholders/fml/ModLoadingContext.class"),
                        "placeholder for ModLoadingContext injected");
                Assert.notNull(zip.getEntry("dev/forgebridge/api/placeholder/PlaceholderSupport.class"),
                        "placeholder support injected");

                // Assets are copied untouched.
                Assert.contains(readEntry(zip, "assets/examplemod/lang/en_us.json"), "Rune", "assets preserved");
            } finally {
                zip.close();
            }

            // The patched output must be recognisable as patched and no longer legacy.
            JarScanner.ScanResult rescanned = JarScanner.scan(output);
            Assert.isTrue(rescanned.isPatched(), "patched jar is marked");
            // One class still carries a *string* constant with the old package name; reflection
            // strings cannot be rewritten safely, so they are reported rather than guessed at.
            Assert.equalInt(1, rescanned.getLegacyClassCount(), "only the reflected name remains");
            Assert.contains(String.valueOf(rescanned.getSampleReferences().toArray()[0]),
                    "net.minecraftforge.", "the remaining reference is the dot-form string");
        } finally {
            AsmSupport.override(null);
            Fixtures.deleteTree(dir);
        }
    }

    public static void testDryRunOptionsLeaveTheInputUntouched() throws Exception {
        File dir = Fixtures.tempDir("forgebridge-dry");
        try {
            File input = Fixtures.legacyJar(dir, "examplemod.jar");
            long before = input.length();
            PatchOptions options = Bootstrap.patchOptions().setDryRun(true);
            File output = new File(dir, "out.jar");
            PatchReport report = JarPatcher.patch(input, output, Bootstrap.remapEngine(), options);
            Assert.equalInt((int) before, (int) input.length(), "input jar untouched");
            Assert.isFalse(output.exists(), "dry run writes no output jar");
            Assert.isTrue(report.isLegacyMod(), "dry run still analyses the descriptor");
            Assert.isTrue(report.getClassesRemapped() >= 1, "dry run still counts remapped classes");
            Assert.equal("(dry run)", report.getOutputName(), "report marks the output as a dry run");
            Assert.isTrue(report.getWarnings().size() >= 1, "dry run is called out in the report warnings");
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    public static void testWorkspaceKeepsTheOriginalAndReplacesTheJar() throws Exception {
        File gameDir = Fixtures.tempDir("forgebridge-ws");
        try {
            File mods = new File(gameDir, "mods");
            Assert.isTrue(mods.mkdirs(), "mods directory");
            File legacy = Fixtures.legacyJar(mods, "examplemod-1.2.3.jar");
            File nativeJar = new File(mods, "native-1.0.jar");
            java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(nativeJar));
            try {
                Fixtures.put(out, "META-INF/neoforge.mods.toml",
                        "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n\n[[mods]]\n    modId=\"native_mod\"\n");
            } finally {
                out.close();
            }

            ClassRemapEngine engine = Bootstrap.remapEngine();
            PatchWorkspace workspace = new PatchWorkspace(engine, Bootstrap.patchOptions(), false);
            PatchWorkspace.Summary summary = workspace.prepare(gameDir, mods);

            Assert.equalInt(1, summary.getPatched().size(), "one jar patched: " + summary.getPatched());
            Assert.equalInt(1, summary.getSkipped().size(), "native jar skipped: " + summary.getSkipped());
            Assert.equalInt(0, summary.getFailed().size(), "no failures: " + summary.getFailed());
            Assert.isTrue(summary.isRestartRequired(), "restart required after patching");
            Assert.notNull(summary.getReportFile(), "report file");
            Assert.isTrue(summary.getReportFile().isFile(), "report written to disk");

            File backup = new File(new File(new File(gameDir, "forgebridge"), "legacy"), "examplemod-1.2.3.jar");
            Assert.isTrue(backup.isFile(), "original kept under forgebridge/legacy");
            Assert.isTrue(JarScanner.scan(legacy).isPatched(), "jar in mods is translated");

            String report = dev.forgebridge.util.IoUtil.readFileText(summary.getReportFile());
            Assert.contains(report, "examplemod-1.2.3.jar", "report names the patched jar");
            Assert.contains(report, "redirects", "report lists redirects");
            Assert.contains(report, "examplemod", "report names the detected mod");

            // Running again must be a no-op: the jar is already patched.
            PatchWorkspace.Summary second = new PatchWorkspace(engine, Bootstrap.patchOptions(), false)
                    .prepare(gameDir, mods);
            Assert.equalInt(0, second.getPatched().size(), "second pass patches nothing");
            Assert.equalInt(2, second.getSkipped().size(), "both jars skipped on the second pass");
        } finally {
            Fixtures.deleteTree(gameDir);
        }
    }
}

package dev.forgebridge.jar;

import dev.forgebridge.remap.AsmSupport;
import dev.forgebridge.remap.ClassRemapEngine;
import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.IoUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The translation engine for a whole jar.
 *
 * <p>Runs in two passes over an unchanged input file and writes a fresh archive:</p>
 * <ol>
 *   <li>scan ({@link JarScanner}) to decide whether translation is needed at all;</li>
 *   <li>rewrite every entry through {@link ClassRemapEngine} or the resource rewriters.</li>
 * </ol>
 *
 * <p>The output archive is written to a temporary file and moved into place afterwards, so
 * an interrupted patch can never leave a half-written mod behind.</p>
 */
public final class JarPatcher {

    private JarPatcher() {
    }

    public static PatchReport patch(File input, File output, ClassRemapEngine engine, PatchOptions options) throws IOException {
        JarScanner.ScanResult scan = JarScanner.scan(input);
        PatchReport report = new PatchReport(input.getName());
        report.setLegacyMod(scan.isLegacyForge());
        report.setPipeline(AsmSupport.level().getDescription());
        if (scan.getModId() != null) {
            report.setDetectedMod(scan.getModId(), scan.getVersion());
        }
        if (scan.isPatched()) {
            report.warn("jar already carries the ForgeBridge marker; patching again");
        }

        // Manifest first: rewrite it before the main loop so it is the first entry, as
        // the jar specification recommends.
        byte[] manifestBytes = ManifestRewriter.rewrite(scan.getManifestBytes(), options.getBridgeVersion(),
                IoUtil.sha256Hex(new byte[0]), AsmSupport.level().getDescription()).getBytes();

        File parent = output.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File temp = new File(parent, output.getName() + ".tmp");
        ZipFile zip = new ZipFile(input);
        ZipOutputStream out = null;
        try {
            out = new ZipOutputStream(new java.io.FileOutputStream(temp));
            out.setLevel(9);
            putEntry(out, "META-INF/MANIFEST.MF", manifestBytes, 0L);
            Set<String> written = new HashSet<String>();
            written.add("META-INF/MANIFEST.MF");

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry) entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if ("META-INF/MANIFEST.MF".equals(name)) {
                    continue;
                }
                if (isSignatureFile(name)) {
                    if (options.isDropSignatures()) {
                        report.skipEntry(name, "signature invalidated by patching");
                        continue;
                    }
                }
                byte[] data = read(zip, entry);
                byte[] transformed = data;
                String targetName = name;

                if (name.endsWith(".class")) {
                    ClassRemapEngine.Result result = engine.remap(data);
                    if (result.hasError()) {
                        report.warn(name + ": " + result.getError());
                    }
                    transformed = result.getBytes();
                    targetName = engine.mapEntryName(name);
                    report.countClass(name, result.isChanged());
                    for (int i = 0; i < result.getAppliedRedirects().size(); i++) {
                        report.addRedirect(name + " :: " + result.getAppliedRedirects().get(i));
                    }
                    report.addResidualReferences(result.getResidualReferences());
                    String internal = name.substring(0, name.length() - ".class".length());
                    if (engine.isBlocked(internal)) {
                        report.blockType(internal);
                        report.warn(internal + " uses an API that NeoForge removed: " + engine.blockedReason(internal));
                    }
                } else if ("META-INF/mods.toml".equals(name) || "META-INF/neoforge.mods.toml".equals(name)) {
                    ModsTomlRewriter.Result result = ModsTomlRewriter.rewrite(
                            new String(data, IoUtil.UTF8), options);
                    transformed = result.getText().getBytes(IoUtil.UTF8);
                    if (result.isChanged()) {
                        report.countResource(name, describe(result.getChanges()));
                    }
                } else if (ServiceFileRewriter.isServiceFile(name)) {
                    ServiceFileRewriter.Result result = ServiceFileRewriter.rewrite(name, data, engine.getTable());
                    if (result != null) {
                        transformed = result.getBytes();
                        targetName = result.getPath();
                        report.countResource(name, describe(result.getChanges()));
                    }
                } else if (name.endsWith(".json") || name.endsWith(".js")) {
                    DataRewriter.Result result = DataRewriter.rewrite(name, data, options, engine.getTable());
                    if (result != null) {
                        transformed = result.getBytes();
                        targetName = result.getPath();
                        report.countResource(name + " -> " + targetName, describe(result.getChanges()));
                    }
                }

                if (!written.add(targetName)) {
                    report.warn("entry name collision on " + targetName + "; keeping " + name);
                    targetName = name;
                    written.add(name);
                }
                putEntry(out, targetName, transformed, entry.getTime());
            }

            // Placeholder stubs last: they must not shadow a class the mod already ships.
            java.util.Map<String, byte[]> injected = options.getInjectedEntries();
            for (Object element : injected.entrySet()) {
                java.util.Map.Entry entry = (java.util.Map.Entry) element;
                String entryName = String.valueOf(entry.getKey());
                if (written.contains(entryName)) {
                    continue;
                }
                putEntry(out, entryName, (byte[]) entry.getValue(), 0L);
                written.add(entryName);
                report.increment("placeholder injected");
            }
        } finally {
            IoUtil.closeQuietly(out);
            zip.close();
        }

        if (options.isDryRun()) {
            // A dry run produces the same report but keeps the filesystem untouched.
            temp.delete();
            report.setOutputName("(dry run)");
            report.warn("dry run: no output written");
            BridgeLog.info(report.summary());
            return report;
        }

        if (output.exists() && !output.delete()) {
            throw new IOException("Could not replace " + output);
        }
        if (!temp.renameTo(output)) {
            IoUtil.writeFile(output, IoUtil.readFile(temp));
            temp.delete();
        }
        report.setOutputName(output.getName());
        BridgeLog.info(report.summary());
        return report;
    }

    private static void putEntry(ZipOutputStream out, String name, byte[] data, long time) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (time > 0) {
            entry.setTime(time);
        }
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        InputStream in = zip.getInputStream(entry);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(4096, (int) Math.min(Math.max(entry.getSize(), 0), 1 << 20)));
            IoUtil.copy(in, buffer);
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    private static boolean isSignatureFile(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }

    private static String describe(List<String> changes) {
        if (changes.isEmpty()) {
            return "no change";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(String.valueOf(changes.get(i)));
        }
        return sb.toString();
    }
}

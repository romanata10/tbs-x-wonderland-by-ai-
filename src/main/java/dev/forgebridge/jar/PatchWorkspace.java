package dev.forgebridge.jar;

import dev.forgebridge.remap.ClassRemapEngine;
import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Prepares a {@code mods/} directory for a NeoForge launch.
 *
 * <p>Translation has to happen before FML discovers mods, otherwise the runtime is already
 * committed to the legacy descriptor. The workspace therefore owns the on-disk protocol:</p>
 *
 * <ol>
 *   <li>every legacy jar is translated into a staging directory;</li>
 *   <li>the original is moved to {@code <gamedir>/forgebridge/legacy/} (never deleted);</li>
 *   <li>the translated jar takes its place, keeping the original file name;</li>
 *   <li>a report is written next to the backups.</li>
 * </ol>
 *
 * <p>Running with {@code -Dforgebridge.autopatch=false} turns this into a dry run that only
 * reports what it would do.</p>
 */
public final class PatchWorkspace {

    /** Outcome of a workspace preparation pass. */
    public static final class Summary {
        private final List<String> patched = new ArrayList<String>();
        private final List<String> skipped = new ArrayList<String>();
        private final List<String> failed = new ArrayList<String>();
        private boolean restartRequired;
        private File reportFile;

        public List<String> getPatched() {
            return patched;
        }

        public List<String> getSkipped() {
            return skipped;
        }

        public List<String> getFailed() {
            return failed;
        }

        public boolean isRestartRequired() {
            return restartRequired;
        }

        public void setRestartRequired(boolean restartRequired) {
            this.restartRequired = restartRequired;
        }

        public File getReportFile() {
            return reportFile;
        }

        public String summaryLine() {
            return "patched " + patched.size() + ", skipped " + skipped.size() + ", failed " + failed.size()
                    + (restartRequired ? " (restart required)" : "");
        }
    }

    private final ClassRemapEngine engine;
    private final PatchOptions options;
    private final boolean dryRun;

    public PatchWorkspace(ClassRemapEngine engine, PatchOptions options, boolean dryRun) {
        this.engine = engine;
        this.options = options;
        this.dryRun = dryRun;
    }

    public Summary prepare(File gameDir, File modsDir) {
        Summary summary = new Summary();
        File workspace = new File(gameDir, dev.forgebridge.BridgeConstants.CONFIG_DIR);
        File legacyDir = new File(workspace, dev.forgebridge.BridgeConstants.LEGACY_DIR);
        File staging = new File(modsDir, ".forgebridge-staging");
        StringBuilder reportText = new StringBuilder();
        reportText.append("ForgeBridge patch pass\n");
        reportText.append("mods directory: ").append(modsDir.getAbsolutePath()).append('\n');
        reportText.append("dry run       : ").append(dryRun).append("\n\n");

        List<File> jars = IoUtil.listFiles(modsDir, new String[]{".jar"});
        for (int i = 0; i < jars.size(); i++) {
            File jar = (File) jars.get(i);
            if (jar.getName().startsWith("forgebridge-")) {
                continue;
            }
            try {
                JarScanner.ScanResult scan = JarScanner.scan(jar);
                if (!scan.isLegacyForge()) {
                    summary.getSkipped().add(jar.getName() + " (" + scan.reason() + ")");
                    continue;
                }
                if (scan.isPatched()) {
                    summary.getSkipped().add(jar.getName() + " (already patched)");
                    continue;
                }
                if (dryRun) {
                    summary.getPatched().add(jar.getName() + " [dry run]");
                    reportText.append("WOULD PATCH ").append(jar.getName()).append(": ").append(scan.reason()).append('\n');
                    continue;
                }
                staging.mkdirs();
                File patched = new File(staging, jar.getName());
                PatchReport report = JarPatcher.patch(jar, patched, engine, options);
                reportText.append(report.toText()).append('\n');

                // Keep the original, then let the patched jar take its place.
                legacyDir.mkdirs();
                File backup = new File(legacyDir, jar.getName());
                if (backup.exists()) {
                    backup = new File(legacyDir, jar.getName() + "." + System.currentTimeMillis());
                }
                if (!jar.renameTo(backup)) {
                    copy(jar, backup);
                    if (!jar.delete()) {
                        BridgeLog.warn("Could not move " + jar + " out of the mods directory");
                    }
                }
                copy(patched, jar);
                patched.delete();
                summary.getPatched().add(jar.getName() + " (" + report.getClassesRemapped() + " classes)");
                summary.setRestartRequired(true);
            } catch (Throwable error) {
                BridgeLog.error("Failed to patch " + jar.getName(), error);
                summary.getFailed().add(jar.getName() + ": " + error);
                reportText.append("FAILED ").append(jar.getName()).append(": ").append(error).append('\n');
            }
        }

        File reportFile = new File(workspace, dev.forgebridge.BridgeConstants.REPORT_FILE);
        try {
            IoUtil.writeFileText(reportFile, reportText.toString());
            summary.reportFile = reportFile;
        } catch (IOException error) {
            BridgeLog.warn("Could not write patch report", error);
        }
        return summary;
    }

    private static void copy(File from, File to) throws IOException {
        IoUtil.writeFile(to, IoUtil.readFile(from));
    }
}

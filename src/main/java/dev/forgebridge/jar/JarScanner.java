package dev.forgebridge.jar;

import dev.forgebridge.remap.ReferenceAudit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only inspection of a mod jar: is it a Forge (legacy) mod, and what does it contain?
 *
 * <p>Deliberately conservative. A jar counts as legacy when its descriptor looks like
 * Forge 1.20.1/1.21.1 <em>or</em> when any class references {@code net/minecraftforge}. The second
 * condition catches mods that ship a NeoForge-agnostic descriptor but call into Forge APIs
 * anyway (common in multi-loader source sets that got the descriptor wrong).</p>
 */
public final class JarScanner {

    private static final int SAMPLE_LIMIT = 16;

    /** Everything the patcher needs to know before writing anything. */
    public static final class ScanResult {
        private String descriptorPath;
        private String descriptorText;
        private ModDescriptor descriptor;
        private byte[] manifestBytes;
        private boolean patched;
        private int entryCount;
        private int classCount;
        private int legacyClassCount;
        private final Set<String> sampleReferences = new LinkedHashSet<String>();
        private final List<String> mixinConfigs = new ArrayList<String>();
        private String modId;
        private String version;

        public String getDescriptorPath() {
            return descriptorPath;
        }

        public String getDescriptorText() {
            return descriptorText;
        }

        public ModDescriptor getDescriptor() {
            return descriptor;
        }

        public byte[] getManifestBytes() {
            return manifestBytes;
        }

        public boolean isPatched() {
            return patched;
        }

        public int getEntryCount() {
            return entryCount;
        }

        public int getClassCount() {
            return classCount;
        }

        public int getLegacyClassCount() {
            return legacyClassCount;
        }

        public Set<String> getSampleReferences() {
            return sampleReferences;
        }

        public List<String> getMixinConfigs() {
            return mixinConfigs;
        }

        public String getModId() {
            return modId;
        }

        public String getVersion() {
            return version;
        }

        public boolean hasDescriptor() {
            return descriptorText != null;
        }

        public boolean isLegacyForge() {
            if (descriptor != null && descriptor.looksLikeForge()) {
                return true;
            }
            return legacyClassCount > 0;
        }

        public String reason() {
            if (descriptor != null && descriptor.dependsOnForge()) {
                return "descriptor depends on modId=forge";
            }
            if (descriptor != null && descriptor.looksLikeForge()) {
                return "descriptor loaderVersion " + descriptor.getLoaderVersion() + " is the Forge line";
            }
            if (legacyClassCount > 0) {
                return legacyClassCount + " classes reference net.minecraftforge";
            }
            return "not a legacy Forge mod";
        }
    }

    private JarScanner() {
    }

    public static ScanResult scan(File file) throws IOException {
        ZipFile zip = new ZipFile(file);
        try {
            return scan(zip);
        } finally {
            zip.close();
        }
    }

    public static ScanResult scan(ZipFile zip) throws IOException {
        ScanResult result = new ScanResult();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        ByteArrayOutputStream modsToml = null;
        while (entries.hasMoreElements()) {
            ZipEntry entry = (ZipEntry) entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            result.entryCount++;
            String name = entry.getName();
            if ("META-INF/mods.toml".equals(name) || "META-INF/neoforge.mods.toml".equals(name)) {
                if (modsToml == null) {
                    result.descriptorPath = name;
                    modsToml = new ByteArrayOutputStream();
                    InputStream in = zip.getInputStream(entry);
                    try {
                        copy(in, modsToml);
                    } finally {
                        in.close();
                    }
                }
            } else if ("META-INF/MANIFEST.MF".equals(name)) {
                result.manifestBytes = readEntry(zip, entry);
                result.patched = ManifestRewriter.isPatched(result.manifestBytes);
                String mixinConfigs = ManifestRewriter.attribute(result.manifestBytes, "MixinConfigs");
                if (mixinConfigs != null) {
                    String[] split = mixinConfigs.split(",");
                    for (int i = 0; i < split.length; i++) {
                        if (!split[i].trim().isEmpty()) {
                            result.mixinConfigs.add(split[i].trim());
                        }
                    }
                }
            } else if (name.endsWith(".jar")) {
                // Nested jar (jar-in-jar). Reported, not translated in place.
                result.sampleReferences.add("nested:" + name);
            } else if (name.endsWith(".class")) {
                result.classCount++;
                byte[] bytes = readEntry(zip, entry);
                List<String> refs = ReferenceAudit.findLegacyReferences(bytes);
                if (!refs.isEmpty()) {
                    result.legacyClassCount++;
                    for (int i = 0; i < refs.size() && result.sampleReferences.size() < SAMPLE_LIMIT; i++) {
                        result.sampleReferences.add(String.valueOf(refs.get(i)));
                    }
                }
            }
        }
        if (modsToml != null) {
            result.descriptorText = new String(modsToml.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
            result.descriptor = ModDescriptor.parse(result.descriptorText);
            result.modId = result.descriptor.getModId();
            result.version = result.descriptor.getVersion();
            String mixins = (String) result.descriptor.getTopLevel().get("mixins");
            if (mixins != null && !result.mixinConfigs.contains(mixins)) {
                result.mixinConfigs.add(mixins);
            }
        }
        return result;
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        InputStream in = zip.getInputStream(entry);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, (int) Math.min(entry.getSize(), 1 << 20)));
            copy(in, out);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
    }
}

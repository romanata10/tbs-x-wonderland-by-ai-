package dev.forgebridge.jar;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Rewrites {@code META-INF/MANIFEST.MF}.
 *
 * <p>Manifests matter for two reasons: FML reads {@code FMLModType} to decide how a jar
 * joins the module layer, and Mixin reads the {@code MixinConfigs} attribute before any
 * descriptor is parsed. Both are carried over untouched, while the bridge stamps its own
 * attributes so a patched jar is recognisable and idempotent.</p>
 */
public final class ManifestRewriter {

    public static final String PATCHED_ATTRIBUTE = "ForgeBridge-Patched";
    public static final String SOURCE_HASH_ATTRIBUTE = "ForgeBridge-Source-Sha256";
    public static final String PIPELINE_ATTRIBUTE = "ForgeBridge-Pipeline";

    private ManifestRewriter() {
    }

    public static final class Result {
        private final byte[] bytes;
        private final List<String> changes;

        Result(byte[] bytes, List<String> changes) {
            this.bytes = bytes;
            this.changes = changes;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public List<String> getChanges() {
            return changes;
        }
    }

    public static Result rewrite(byte[] original, String bridgeVersion, String sourceHash, String pipeline) throws IOException {
        List<String> changes = new ArrayList<String>();
        Manifest manifest = original == null || original.length == 0
                ? new Manifest()
                : new Manifest(new ByteArrayInputStream(original));

        Attributes attributes = manifest.getMainAttributes();
        if (attributes.getValue(Attributes.Name.MANIFEST_VERSION) == null) {
            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }
        if (attributes.getValue("ForgeBridge-Version") == null) {
            attributes.put(new Attributes.Name("ForgeBridge-Version"), bridgeVersion);
        }
        attributes.put(new Attributes.Name(PATCHED_ATTRIBUTE), "true");
        attributes.put(new Attributes.Name(SOURCE_HASH_ATTRIBUTE), sourceHash);
        attributes.put(new Attributes.Name(PIPELINE_ATTRIBUTE), pipeline);
        changes.add("stamped ForgeBridge attributes");

        for (Object element : new java.util.LinkedHashMap<Object, Object>(attributes).entrySet()) {
            Map.Entry entry = (Map.Entry) element;
            String key = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());
            if (key.equalsIgnoreCase("Automatic-Module-Name") && value.contains("minecraftforge")) {
                String replacement = value.replace("minecraftforge", "neoforged").replace("..", ".");
                attributes.put(new Attributes.Name(key), replacement);
                changes.add("Automatic-Module-Name " + value + " -> " + replacement);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manifest.write(out);
        return new Result(out.toByteArray(), changes);
    }

    public static boolean isPatched(byte[] manifestBytes) {
        if (manifestBytes == null || manifestBytes.length == 0) {
            return false;
        }
        try {
            Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
            return "true".equalsIgnoreCase(manifest.getMainAttributes().getValue(PATCHED_ATTRIBUTE));
        } catch (IOException error) {
            return false;
        }
    }

    public static String attribute(byte[] manifestBytes, String name) {
        if (manifestBytes == null || manifestBytes.length == 0) {
            return null;
        }
        try {
            Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
            return manifest.getMainAttributes().getValue(name);
        } catch (IOException error) {
            return null;
        }
    }

    /** Produces a minimal manifest for jars that ship none. */
    public static byte[] empty() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manifest.write(out);
        return out.toByteArray();
    }
}

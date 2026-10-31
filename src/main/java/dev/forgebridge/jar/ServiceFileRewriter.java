package dev.forgebridge.jar;

import dev.forgebridge.remap.MappingTable;
import dev.forgebridge.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites {@code META-INF/services/*} provider files.
 *
 * <p>Legacy mods use ServiceLoader for their own extension points, and some use Forge's
 * SPI types ({@code net.minecraftforge.forgespi.*}). Both the file name (the service
 * interface) and every provider line are remapped. Unlike bytecode, a stale name here
 * fails silently — ServiceLoader simply returns no providers — so this rewrite is what
 * keeps such extensions alive.</p>
 */
public final class ServiceFileRewriter {

    private ServiceFileRewriter() {
    }

    public static final class Result {
        private final String path;
        private final byte[] bytes;
        private final List<String> changes;

        Result(String path, byte[] bytes, List<String> changes) {
            this.path = path;
            this.bytes = bytes;
            this.changes = changes;
        }

        public String getPath() {
            return path;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public List<String> getChanges() {
            return changes;
        }
    }

    public static boolean isServiceFile(String entryName) {
        return entryName.startsWith("META-INF/services/") && !entryName.endsWith("/");
    }

    /** Returns {@code null} when nothing needs to change. */
    public static Result rewrite(String entryName, byte[] data, MappingTable table) {
        String serviceName = entryName.substring("META-INF/services/".length());
        // The service file name is a binary name (dots); the table is keyed by internal names.
        String mappedService = table.mapClass(serviceName.replace('.', '/')).replace('/', '.');
        String text = new String(data, java.nio.charset.Charset.forName("UTF-8"));
        List<String> changes = new ArrayList<String>();

        StringBuilder sb = new StringBuilder(text.length() + 32);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                sb.append(line);
            } else {
                String mapped = table.mapClass(trimmed.replace('.', '/')).replace('/', '.');
                if (!mapped.equals(trimmed)) {
                    changes.add(trimmed + " -> " + mapped);
                }
                int indent = line.indexOf(trimmed);
                sb.append(line.substring(0, Math.max(indent, 0))).append(mapped);
                String trailing = line.substring(Math.max(indent, 0) + trimmed.length());
                if (!trailing.isEmpty()) {
                    sb.append(trailing);
                }
            }
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        if (!mappedService.equals(serviceName)) {
            changes.add("service file " + serviceName + " -> " + mappedService);
        }
        if (changes.isEmpty()) {
            return null;
        }
        String newPath = "META-INF/services/" + mappedService;
        if (StringUtil.isBlank(newPath)) {
            newPath = entryName;
        }
        return new Result(newPath, sb.toString().getBytes(java.nio.charset.Charset.forName("UTF-8")), changes);
    }
}

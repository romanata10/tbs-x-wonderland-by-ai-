package dev.forgebridge.compat;

import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Emits common tags for content shared between the bridged mods.
 *
 * <p>Tags are data, so they cannot be created after the datapack has been loaded. The bridge
 * therefore writes them into a small generated data pack directory inside the game folder
 * ({@code forgebridge/generated-data}) and reports the path. On a dedicated server that
 * directory can be copied into {@code world/datapacks/}; in a dev environment it is picked up
 * by the data pack loader directly.</p>
 *
 * <p>Everything is derived from {@link CrossModRegistry}, which the compatibility profiles
 * populate from what they actually find at runtime - the bridge never invents item ids.</p>
 */
public final class SharedTagBridge {

    private SharedTagBridge() {
    }

    /** @return the generated data pack root, or {@code null} when nothing was written */
    public static File generate(File gameDir) {
        Map<String, Set<String>> tags = CrossModRegistry.sharedTags();
        if (tags.isEmpty()) {
            return null;
        }
        File root = new File(new File(gameDir, "forgebridge"), "generated-data");
        int written = 0;
        for (Object element : tags.entrySet()) {
            Map.Entry entry = (Map.Entry) element;
            String tagId = String.valueOf(entry.getKey());
            String namespace = "c";
            String path = tagId;
            int colon = tagId.indexOf(':');
            if (colon >= 0) {
                namespace = tagId.substring(0, colon);
                path = tagId.substring(colon + 1);
            }
            File target = new File(new File(new File(new File(root, "data"), namespace), "tags"), path + ".json");
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"replace\": false,\n  \"values\": [\n");
            int index = 0;
            Set members = (Set) entry.getValue();
            for (Object memberElement : members) {
                String member = String.valueOf(memberElement);
                json.append("    ");
                if (index > 0) {
                    json.setLength(json.length() - 4);
                    json.append(",\n    ");
                }
                json.append('"').append(member).append('"');
                index++;
            }
            json.append("\n  ]\n}\n");
            try {
                IoUtil.writeFileText(target, json.toString());
                written++;
            } catch (IOException error) {
                BridgeLog.warn("Could not write shared tag " + target, error);
            }
        }
        if (written == 0) {
            return null;
        }
        BridgeLog.info("Wrote " + written + " shared tag file(s) to " + root.getAbsolutePath()
                + " (copy into world/datapacks to activate on a server)");
        return root;
    }
}

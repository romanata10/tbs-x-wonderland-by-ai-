package dev.forgebridge.jar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The parts of an FML descriptor the bridge cares about. */
public final class ModDescriptor {

    private String modId;
    private String version;
    private String displayName;
    private String modLoader;
    private String loaderVersion;
    private final List<Dependency> dependencies = new ArrayList<Dependency>();
    private final Map<String, String> topLevel = new LinkedHashMap<String, String>();

    /** One {@code [[dependencies.<modid>]]} block. */
    public static final class Dependency {
        private String modId;
        private String type;
        private String versionRange;
        private String ordering;
        private String side;

        public String getModId() {
            return modId;
        }

        public String getType() {
            return type;
        }

        public String getVersionRange() {
            return versionRange;
        }

        public String getOrdering() {
            return ordering;
        }

        public String getSide() {
            return side;
        }
    }

    public String getModId() {
        return modId;
    }

    public String getVersion() {
        return version;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getModLoader() {
        return modLoader;
    }

    public String getLoaderVersion() {
        return loaderVersion;
    }

    public List<Dependency> getDependencies() {
        return dependencies;
    }

    public Map<String, String> getTopLevel() {
        return topLevel;
    }

    public boolean dependsOnForge() {
        for (int i = 0; i < dependencies.size(); i++) {
            Dependency dependency = (Dependency) dependencies.get(i);
            if ("forge".equalsIgnoreCase(dependency.getModId())) {
                return true;
            }
        }
        return false;
    }

    public boolean dependsOn(String id) {
        for (int i = 0; i < dependencies.size(); i++) {
            Dependency dependency = (Dependency) dependencies.get(i);
            if (id.equalsIgnoreCase(dependency.getModId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A 1.21.1 Forge descriptor is recognisable by its {@code forge} dependency or by a
     * 47.x loader version (FML 47 is the Forge 1.21.1 line; NeoForge uses FML 4.x).
     */
    public boolean looksLikeForge() {
        if (dependsOnForge()) {
            return true;
        }
        int major = firstVersionNumber(loaderVersion);
        return major >= 40;
    }

    /** Extracts the leading version number of a range such as {@code [47,1.22)}, or -1. */
    public static int firstVersionNumber(String range) {
        if (range == null) {
            return -1;
        }
        int start = -1;
        int end = -1;
        for (int i = 0; i < range.length(); i++) {
            char c = range.charAt(i);
            if (Character.isDigit(c)) {
                if (start < 0) {
                    start = i;
                }
                end = i + 1;
            } else if (start >= 0) {
                break;
            }
        }
        if (start < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(range.substring(start, end));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    public static ModDescriptor parse(String toml) {
        ModDescriptor descriptor = new ModDescriptor();
        List<TomlDocument.Block> blocks = TomlDocument.split(toml);
        for (int i = 0; i < blocks.size(); i++) {
            TomlDocument.Block block = (TomlDocument.Block) blocks.get(i);
            Map<String, String> values = block.getValues();
            if (block.isDependency()) {
                ModDescriptor.Dependency dependency = new ModDescriptor.Dependency();
                dependency.modId = (String) values.get("modId");
                dependency.type = (String) values.get("type");
                dependency.versionRange = (String) values.get("versionRange");
                dependency.ordering = (String) values.get("ordering");
                dependency.side = (String) values.get("side");
                descriptor.dependencies.add(dependency);
            } else if (block.isModsEntry()) {
                descriptor.modId = values.containsKey("modId") ? (String) values.get("modId") : descriptor.modId;
                descriptor.version = values.containsKey("version") ? (String) values.get("version") : descriptor.version;
                descriptor.displayName = values.containsKey("displayName") ? (String) values.get("displayName") : descriptor.displayName;
            } else if (block.isRoot()) {
                descriptor.topLevel.putAll(values);
                String loader = (String) values.get("modLoader");
                if (loader != null) {
                    descriptor.modLoader = loader;
                }
                String loaderVersion = (String) values.get("loaderVersion");
                if (loaderVersion != null) {
                    descriptor.loaderVersion = loaderVersion;
                }
            }
        }
        if (descriptor.modId == null) {
            descriptor.modId = (String) descriptor.topLevel.get("modId");
        }
        return descriptor;
    }
}

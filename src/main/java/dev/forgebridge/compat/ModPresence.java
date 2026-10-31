package dev.forgebridge.compat;

import dev.forgebridge.util.SafeReflect;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Answers "is this mod installed?" without linking against FML.
 *
 * <p>{@code ModList.get().isLoaded(id)} is the supported answer during and after mod loading.
 * Before that (CLI runs, or the launcher service), the mods directory is inspected instead, so
 * the same question can be answered at any point of the life cycle.</p>
 */
public final class ModPresence {

    private final File modsDir;
    private final List<String> extraIds;

    public ModPresence(File modsDir, List<String> extraIds) {
        this.modsDir = modsDir;
        this.extraIds = extraIds == null ? Collections.<String>emptyList() : extraIds;
    }

    public boolean isLoaded(String modId) {
        if (modId == null) {
            return false;
        }
        Boolean fromFml = askModList(modId);
        if (fromFml != null) {
            return fromFml.booleanValue();
        }
        return jarLooksLike(modId);
    }

    /** @return {@code null} when FML is not available (i.e. we cannot ask) */
    private Boolean askModList(String modId) {
        Class<?> modList = SafeReflect.findClass("net.neoforged.fml.ModList");
        if (modList == null) {
            return null;
        }
        Object instance = SafeReflect.invokeStatic(modList, "get");
        if (instance == null) {
            return null;
        }
        Object loaded = SafeReflect.invoke(instance, "isLoaded", modId);
        if (loaded instanceof Boolean) {
            return (Boolean) loaded;
        }
        return null;
    }

    private boolean jarLooksLike(String modId) {
        if (modsDir == null) {
            return false;
        }
        File[] files = modsDir.listFiles();
        if (files == null) {
            return false;
        }
        String needle = modId.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < files.length; i++) {
            String name = files[i].getName().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".jar") && name.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public List<String> detected() {
        List<String> result = new ArrayList<String>();
        List<String> candidates = new ArrayList<String>(extraIds);
        if (modsDir != null) {
            File[] files = modsDir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    String name = files[i].getName();
                    if (name.endsWith(".jar") || name.endsWith(".jar.disabled")) {
                        result.add(name);
                    }
                }
            }
        }
        return result;
    }

    public List<String> interestingMods() {
        List<String> found = new ArrayList<String>();
        for (int i = 0; i < extraIds.size(); i++) {
            if (isLoaded(String.valueOf(extraIds.get(i)))) {
                found.add(String.valueOf(extraIds.get(i)));
            }
        }
        return found;
    }
}

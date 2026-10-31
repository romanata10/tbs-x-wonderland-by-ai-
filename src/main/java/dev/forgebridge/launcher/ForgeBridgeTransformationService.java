package dev.forgebridge.launcher;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import dev.forgebridge.BridgeConstants;
import dev.forgebridge.Bootstrap;
import dev.forgebridge.jar.PatchOptions;
import dev.forgebridge.jar.PatchWorkspace;
import dev.forgebridge.remap.AsmSupport;
import dev.forgebridge.remap.ClassRemapEngine;
import dev.forgebridge.util.BridgeLog;
import dev.forgebridge.util.SafeReflect;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * ModLauncher entry point.
 *
 * <p>This service is the only part of the bridge that can act <em>before</em> FML discovers
 * mods, which is exactly when legacy jars have to be translated. It is discovered through
 * {@code META-INF/services/cpw.mods.modlauncher.api.ITransformationService}, and it uses only
 * the subset of the ModLauncher API that has been stable since ML 9 (name, initialize, onLoad,
 * transformers); everything version specific is reached reflectively.</p>
 *
 * <p>Deployment note: a ModLauncher service must be on the <em>launch</em> class path, not just
 * in {@code mods/}. {@code docs/DEPLOYMENT.md} describes both supported layouts and the
 * fallback that keeps working when the bridge is installed like an ordinary mod.</p>
 */
public final class ForgeBridgeTransformationService implements ITransformationService {

    private final ForgeBridgeClassTransformer transformer = new ForgeBridgeClassTransformer();
    private boolean patchedAtLaunch;

    @Override
    public String name() {
        return "forgebridge";
    }

    @Override
    public void initialize(IEnvironment environment) {
        BridgeLog.setVerbose(Boolean.getBoolean(BridgeConstants.PROP_DEBUG));
        BridgeLog.info("ForgeBridge " + BridgeConstants.VERSION + " starting (mapping set " + BridgeConstants.MAPPING_SET + ")");
        BridgeLog.info("ASM pipeline: " + AsmSupport.level().getDescription());

        File gameDir = gameDirectory(environment);
        if (gameDir == null) {
            BridgeLog.warn("Could not determine the game directory; launch-time patching disabled");
            return;
        }
        Bootstrap.initializeRuntime(gameDir);

        boolean autopatch = !"false".equalsIgnoreCase(System.getProperty(BridgeConstants.PROP_AUTOPATCH, "true"));
        File modsDir = new File(gameDir, "mods");
        if (!modsDir.isDirectory()) {
            BridgeLog.info("No mods directory at " + modsDir + "; nothing to translate at launch");
            return;
        }
        PatchOptions options = Bootstrap.patchOptions();
        ClassRemapEngine engine = Bootstrap.remapEngine();
        PatchWorkspace workspace = new PatchWorkspace(engine, options, !autopatch);
        PatchWorkspace.Summary summary = workspace.prepare(gameDir, modsDir);
        patchedAtLaunch = !summary.getPatched().isEmpty();
        BridgeLog.info("Launch-time patch pass: " + summary.summaryLine());
        if (!autopatch && !summary.getPatched().isEmpty()) {
            BridgeLog.warn("Autopatching is disabled; the listed jars were only reported. "
                    + "Enable -D" + BridgeConstants.PROP_AUTOPATCH + "=true or run `forgebridge prepare` manually.");
        }
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) throws IncompatibleEnvironmentException {
        BridgeLog.info("ForgeBridge active"
                + (patchedAtLaunch ? " (translated jars for this launch)" : "")
                + "; services: " + otherServices.size());
        // A repaired jar only takes effect on the next launch, because FML froze the mod
        // list before this callback. Say so explicitly instead of failing mysteriously.
        if (patchedAtLaunch) {
            BridgeLog.warn("Legacy jars were translated during this launch. FML has already scanned the "
                    + "mods directory, so restart the game once for the new descriptors to be used.");
        }
    }

    @Override
    public List<ITransformer> transformers() {
        return Collections.singletonList(transformer);
    }

    @Override
    public String toString() {
        return "ForgeBridgeTransformationService[" + BridgeConstants.VERSION + "]";
    }

    /** Resolves the game directory from FML, falling back to the working directory. */
    static File gameDirectory(IEnvironment environment) {
        Class<?> fmlPaths = SafeReflect.findClass("net.neoforged.fml.loading.FMLPaths");
        Object gameDir = SafeReflect.getStatic(fmlPaths, "GAMEDIR");
        if (gameDir == null) {
            gameDir = SafeReflect.getStatic(fmlPaths, "GAMEDIR");
        }
        File resolved = toFile(gameDir);
        if (resolved != null) {
            return resolved;
        }
        return new File(System.getProperty("user.dir", "."));
    }

    private static File toFile(Object pathWrapper) {
        if (pathWrapper == null) {
            return null;
        }
        Object path = SafeReflect.invoke(pathWrapper, "get");
        if (path == null) {
            path = pathWrapper;
        }
        try {
            return new File(String.valueOf(path));
        } catch (Throwable error) {
            return null;
        }
    }
}

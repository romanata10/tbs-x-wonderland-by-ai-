package cpw.mods.modlauncher.api;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Compile-time stub of ModLauncher's ITransformationService.
 *
 * This file is NOT shipped in the bridge jar: the real interface comes from ModLauncher at
 * runtime. It exists so the project can be compiled and verified in an offline sandbox that
 * has no access to the NeoForge/ModLauncher maven repositories. The signatures mirror
 * ModLauncher 10.2.x (NeoForge 21.1).
 */
public interface ITransformationService {

    String name();

    default void arguments(ArgumentCollection arguments) {
    }

    default void initialize(IEnvironment environment) {
    }

    default void onLoad(IEnvironment environment, Set<String> otherServices) throws IncompatibleEnvironmentException {
    }

    default List<ITransformer> transformers() {
        return Collections.emptyList();
    }
}

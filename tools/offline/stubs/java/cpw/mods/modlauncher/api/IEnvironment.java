package cpw.mods.modlauncher.api;

import java.util.Optional;

/** Compile-time stub of ModLauncher's IEnvironment. */
public interface IEnvironment {

    <T> Optional<T> getProperty(Environment.Keys<T> key);

    String getProperty(String key);
}

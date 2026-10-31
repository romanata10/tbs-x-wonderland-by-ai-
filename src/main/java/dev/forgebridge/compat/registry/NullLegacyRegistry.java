package dev.forgebridge.compat.registry;

import dev.forgebridge.util.BridgeLog;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Registry handle used when the real registry is unavailable (CLI runs, or a field NeoForge
 * no longer has). Every access is a logged no-op, which turns a crash into a diagnostic.
 */
public final class NullLegacyRegistry implements LegacyRegistry<Object> {

    private final String registryName;

    public NullLegacyRegistry(String registryName) {
        this.registryName = registryName;
    }

    private void warn(String what) {
        BridgeLog.errorOnce("null-registry-" + registryName + "-" + what,
                "Access to registry '" + registryName + "." + what + "' failed: no NeoForge registry is available for it");
    }

    public Object getRegistryName() {
        warn("getRegistryName");
        return null;
    }

    public Object getKey(Object value) {
        warn("getKey");
        return null;
    }

    public Object getValue(Object key) {
        warn("getValue");
        return null;
    }

    public boolean containsKey(Object key) {
        warn("containsKey");
        return false;
    }

    public boolean containsValue(Object value) {
        warn("containsValue");
        return false;
    }

    public Set<Object> getKeys() {
        warn("getKeys");
        return Collections.emptySet();
    }

    public Collection<Object> getValues() {
        warn("getValues");
        return Collections.emptyList();
    }

    public Object register(Object key, Object value) {
        warn("register");
        return value;
    }

    public int size() {
        return 0;
    }
}

package dev.forgebridge.compat;

/**
 * A compatibility profile for one mod.
 *
 * <p>Profiles are only invoked when their mod is actually present, and every action they take
 * has to be reversible/no-op when the counterpart is missing. The two shipped implementations
 * ({@link WonderlandCompat}, {@link BrokenScriptCompat}) exist because those mods are the
 * reason the bridge exists; third parties can add their own through the service loader.</p>
 */
public interface ModCompat {

    /** The mod id this profile belongs to. */
    String getModId();

    /** Called once during common setup, after mod presence is known. */
    void apply(CompatContext context);
}

package dev.forgebridge.compat.nbt;

/**
 * Where one stack's compound tag lives.
 *
 * <p>Two implementations exist, and the difference matters:</p>
 *
 * <ul>
 *   <li>a <b>live</b> store hands out the attached tag instance itself, so a mod that mutates
 *       the returned tag has already changed what is stored (this is what Forge 1.20.1 did);</li>
 *   <li>a <b>copying</b> store hands out a detached copy, which is all a 1.21.1 data component
 *       can offer. Mutations only become visible when the bridge writes the tag back, so the
 *       bridge has to remember which tags were handed out and flush them.</li>
 * </ul>
 */
public interface TagStore {

    /** The attached tag, or {@code null} when the stack carries none. */
    Object read();

    /** Attaches the tag; {@code null} clears it. */
    void write(Object tag);

    /** True when {@link #read()} returns the stored instance instead of a copy. */
    boolean isLive();
}

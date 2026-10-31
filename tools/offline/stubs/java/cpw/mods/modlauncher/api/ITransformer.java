package cpw.mods.modlauncher.api;

import org.objectweb.asm.tree.ClassNode;

/**
 * Compile-time stub of ModLauncher's ITransformer.
 *
 * <p>The real interface is generic ({@code ITransformer<T extends ITransformerExtension>} with
 * {@code T transform(T, TransformationContext)}), and the bridge implements it as
 * {@code ITransformer<ClassNode>}. The offline compiler (Janino) does not reproduce generic
 * interface method substitution, so the stub declares the already-substituted signature while
 * keeping the type parameter. Source code is identical either way; only the stub differs.</p>
 */
public interface ITransformer<T> extends ITransformerExtension {

    ClassNode transform(ClassNode input, ITransformerV2.TransformationContext context);
}

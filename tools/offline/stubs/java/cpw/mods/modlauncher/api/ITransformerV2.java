package cpw.mods.modlauncher.api;

/** Compile-time stub of ModLauncher's ITransformerV2 (version 2 transformer contract). */
public interface ITransformerV2<T> extends ITransformer<T> {

    interface TransformationContext {
        String getClassName();
    }
}

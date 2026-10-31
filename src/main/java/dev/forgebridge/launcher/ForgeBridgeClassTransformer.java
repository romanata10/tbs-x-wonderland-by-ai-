package dev.forgebridge.launcher;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerV2;
import dev.forgebridge.Bootstrap;
import dev.forgebridge.remap.AsmSupport;
import dev.forgebridge.remap.ClassRemapEngine;
import dev.forgebridge.remap.ReferenceAudit;
import dev.forgebridge.util.BridgeLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

/**
 * Last line of defence for classes that reach the JVM still carrying Forge names.
 *
 * <p>Jar translation handles the normal case, but a few things slip past it: jars that FML
 * loaded from the launcher class path, classes synthesised at runtime, and jars that were
 * patched but reference a mapping the bridge had not seen. This transformer remaps any such
 * class and is a no-op for everything else, so it costs one serialisation per class.</p>
 */
public final class ForgeBridgeClassTransformer implements ITransformer<ClassNode> {

    private int seen;
    private int changed;

    @Override
    public String name() {
        return "forgebridge:legacy-remap";
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerV2.TransformationContext context) {
        if (input == null) {
            return null;
        }
        seen++;
        ClassRemapEngine engine = Bootstrap.remapEngine();
        if (engine == null) {
            return input;
        }
        try {
            ClassWriter writer = new ClassWriter(0);
            input.accept(writer);
            byte[] original = writer.toByteArray();
            if (ReferenceAudit.findLegacyReferences(original).isEmpty()) {
                return input;
            }
            ClassRemapEngine.Result result = engine.remap(original);
            if (!result.isChanged() || result.hasError()) {
                if (result.hasError()) {
                    BridgeLog.warn("Could not remap " + input.name + ": " + result.getError());
                }
                return input;
            }
            ClassNode output = new ClassNode();
            new ClassReader(result.getBytes()).accept(output, 0);
            changed++;
            BridgeLog.debug("Remapped class at load time: " + input.name + " -> " + output.name);
            List<String> residual = result.getResidualReferences();
            if (!residual.isEmpty()) {
                BridgeLog.once("residual-" + input.name, "Remaining Forge references in " + input.name + ": " + residual);
            }
            return output;
        } catch (Throwable error) {
            // Never break class loading because of the bridge.
            BridgeLog.errorOnce("transform-" + input.name, "Transform failed for " + input.name + ": " + error);
            return input;
        }
    }

    @Override
    public String toString() {
        return "ForgeBridgeClassTransformer[pipeline=" + AsmSupport.level().name()
                + ", seen=" + seen + ", changed=" + changed + "]";
    }
}

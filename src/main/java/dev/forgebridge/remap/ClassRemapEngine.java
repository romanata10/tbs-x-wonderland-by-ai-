package dev.forgebridge.remap;

import dev.forgebridge.util.BridgeLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade over the available remapping pipelines.
 *
 * <p>Callers hand in raw class bytes and receive the rewritten bytes plus a record of
 * what happened, which is what makes the patch report possible.</p>
 */
public final class ClassRemapEngine {

    /** Outcome of a single class rewrite. */
    public static final class Result {
        private final byte[] bytes;
        private final boolean changed;
        private final AsmSupport.Level level;
        private final List<String> appliedRedirects;
        private final List<String> residualReferences;
        private final String error;

        Result(byte[] bytes, boolean changed, AsmSupport.Level level, List<String> appliedRedirects,
               List<String> residualReferences, String error) {
            this.bytes = bytes;
            this.changed = changed;
            this.level = level;
            this.appliedRedirects = appliedRedirects;
            this.residualReferences = residualReferences;
            this.error = error;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public boolean isChanged() {
            return changed;
        }

        public AsmSupport.Level getLevel() {
            return level;
        }

        public List<String> getAppliedRedirects() {
            return appliedRedirects;
        }

        public List<String> getResidualReferences() {
            return residualReferences;
        }

        public String getError() {
            return error;
        }

        public boolean hasError() {
            return error != null;
        }
    }

    private final MappingTable table;
    private final RedirectTable redirects;
    private final List<ConstantPoolRemapper.Replacement> rawReplacements;

    public ClassRemapEngine(MappingTable table, RedirectTable redirects) {
        this.table = table;
        this.redirects = redirects == null ? new RedirectTable() : redirects;
        this.rawReplacements = ConstantPoolRemapper.replacementsFor(table);
    }

    public MappingTable getTable() {
        return table;
    }

    public Result remap(byte[] input) {
        AsmSupport.Level level = AsmSupport.level();
        List<String> applied = new ArrayList<String>();
        byte[] output;
        try {
            if (level == AsmSupport.Level.FULL) {
                output = AsmRemapPipeline.fullRemap(input, table, redirects, applied);
            } else if (level == AsmSupport.Level.CORE) {
                byte[] redirected = AsmRemapPipeline.redirectOnly(input, redirects, applied);
                output = ConstantPoolRemapper.remap(redirected, rawReplacements);
            } else {
                output = ConstantPoolRemapper.remap(input, rawReplacements);
            }
        } catch (Throwable error) {
            // A single unparseable class must never abort a whole jar.
            BridgeLog.debug("Remap failed, keeping original bytes: " + error);
            error.printStackTrace();
            return new Result(input, false, level, applied, ReferenceAudit.findLegacyReferences(input),
                    error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        List<String> residual = ReferenceAudit.findLegacyReferences(output);
        boolean changed = output.length != input.length || !java.util.Arrays.equals(output, input);
        return new Result(output, changed, level, applied, residual, null);
    }

    /** Maps a binary class name such as {@code net.minecraftforge.fml.common.Mod}. */
    public String mapBinaryName(String binaryName) {
        if (binaryName == null) {
            return null;
        }
        return table.mapClass(binaryName.replace('.', '/')).replace('/', '.');
    }

    /** Maps an entry name inside a jar, e.g. {@code net/minecraftforge/Foo.class}. */
    public String mapEntryName(String entryName) {
        if (entryName == null || !entryName.endsWith(".class")) {
            return entryName;
        }
        String internal = entryName.substring(0, entryName.length() - ".class".length());
        return table.mapClass(internal) + ".class";
    }

    public boolean isBlocked(String internalName) {
        return table.isBlocked(internalName);
    }

    public String blockedReason(String internalName) {
        return table.getBlockedReason(internalName);
    }

    /** Convenience for tools: remap without caring about the bookkeeping. */
    public byte[] remapBytes(byte[] input) throws IOException {
        Result result = remap(input);
        if (result.hasError()) {
            throw new IOException("Remap failed: " + result.getError());
        }
        return result.getBytes();
    }
}

package dev.forgebridge.remap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

import java.util.List;

/**
 * The high fidelity pipeline. Loaded only when ASM (and asm-commons) are present; the
 * engine keeps this class out of its own signatures so the JVM never resolves it when
 * the fallback is in use.
 */
final class AsmRemapPipeline {

    private AsmRemapPipeline() {
    }

    static byte[] fullRemap(byte[] input, MappingTable table, RedirectTable redirects, List<String> appliedRedirects) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(0);
        LegacyRemapper remapper = new LegacyRemapper(table);
        ClassVisitor tail = new ClassRemapper(writer, remapper);
        if (redirects != null && redirects.size() > 0) {
            CallSiteRedirector redirector = new CallSiteRedirector(tail, redirects);
            reader.accept(redirector, 0);
            appliedRedirects.addAll(redirector.getAppliedRedirects());
        } else {
            reader.accept(tail, 0);
        }
        return writer.toByteArray();
    }

    /** Core-ASM only: rewrite call sites, leave names to the constant pool pass. */
    static byte[] redirectOnly(byte[] input, RedirectTable redirects, List<String> appliedRedirects) {
        if (redirects == null || redirects.size() == 0) {
            return input;
        }
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(0);
        CallSiteRedirector redirector = new CallSiteRedirector(writer, redirects);
        reader.accept(redirector, 0);
        appliedRedirects.addAll(redirector.getAppliedRedirects());
        return redirector.getAppliedRedirects().isEmpty() ? input : writer.toByteArray();
    }
}

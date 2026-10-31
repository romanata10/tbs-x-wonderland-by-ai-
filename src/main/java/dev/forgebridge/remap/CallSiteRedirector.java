package dev.forgebridge.remap;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Core-ASM {@link ClassVisitor} that rewrites the call sites a rename cannot express.
 * Only {@code org.objectweb.asm} core is used, never asm-commons, so this pass works in every
 * launcher configuration.
 *
 * <p>Four shapes are handled (see {@link RedirectTable}): static field access, static calls,
 * instance calls and constructor calls. The last two are what make the 1.20.1 → 1.21.1
 * migration possible:</p>
 *
 * <pre>
 *   stack.getOrCreateTag()             INVOKEVIRTUAL ItemStack.getOrCreateTag()
 *                                  -&gt; INVOKESTATIC  ItemStackNbtBridge.getOrCreateTag(Object)
 *                                     CHECKCAST     CompoundTag
 *
 *   new ResourceLocation("mod:thing")  NEW ResourceLocation          -&gt; (removed)
 *                                      DUP                           -&gt; (removed)
 *                                      INVOKESPECIAL &lt;init&gt;(String)  -&gt; INVOKESTATIC parse(String)
 *                                                                         CHECKCAST ResourceLocation
 * </pre>
 *
 * <p>The constructor case cannot be a one-to-one instruction swap: the factory allocates the
 * object, so the {@code NEW} and the {@code DUP} that fed the original {@code &lt;init&gt;} must
 * disappear. Instructions are therefore buffered from the first rewritten {@code NEW} until the
 * matching {@code &lt;init&gt;}, which also makes nested allocations in the constructor arguments
 * work ({@code new X(new Y())}). Anything that could make the pattern ambiguous (a jump, a label,
 * a stack map frame, an unsupported instruction) aborts the rewrite and replays the buffer
 * unchanged, so a class is never left structurally invalid.</p>
 */
public final class CallSiteRedirector extends ClassVisitor {

    private final RedirectTable redirects;
    private final List<String> applied = new ArrayList<String>();
    private final List<String> unapplied = new ArrayList<String>();
    private String className;

    public CallSiteRedirector(ClassVisitor next, RedirectTable redirects) {
        super(Opcodes.ASM9, next);
        this.redirects = redirects;
    }

    public List<String> getAppliedRedirects() {
        return applied;
    }

    /**
     * Call sites that matched a rule but could not be rewritten safely (an ambiguous
     * constructor pattern). Reported instead of silently leaving a broken reference behind.
     */
    public List<String> getUnappliedRedirects() {
        return unapplied;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (delegate == null) {
            return null;
        }
        return new RedirectingMethodVisitor(delegate, className + "." + name);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        return super.visitField(access, name, descriptor, signature, value);
    }

    /** One buffered instruction, replayed verbatim when a rewrite is abandoned or resolved. */
    private static final class Pending {
        static final int INSN = 1;
        static final int VAR = 2;
        static final int TYPE = 3;
        static final int FIELD = 4;
        static final int METHOD = 5;
        static final int LDC = 6;
        static final int INT = 7;
        static final int IINC = 8;
        static final int JUMP = 9;
        static final int MULTI_ARRAY = 10;

        final int shape;
        int opcode;
        int operand;
        int increment;
        String text;
        String text2;
        String text3;
        Object constant;
        boolean flag;
        /** A {@code NEW} that is a rewrite candidate. */
        boolean candidate;
        Label label;

        Pending(int shape) {
            this.shape = shape;
        }
    }

    private final class RedirectingMethodVisitor extends MethodVisitor {
        private final String where;

        /** {@code null} unless a constructor rewrite is in progress. */
        private List<Pending> buffer;
        /** Rewrite candidates recorded in the buffer and not yet resolved. */
        private int candidates;

        RedirectingMethodVisitor(MethodVisitor next, String where) {
            super(Opcodes.ASM9, next);
            this.where = where;
        }

        // ---------------------------------------------------------------- buffering

        private boolean buffering() {
            return buffer != null;
        }

        private void startBuffer() {
            if (buffer == null) {
                buffer = new ArrayList<Pending>();
                candidates = 0;
            }
        }

        private void record(Pending instruction) {
            if (buffering()) {
                buffer.add(instruction);
                return;
            }
            replay(null, instruction);
        }

        private void flush() {
            if (buffer == null) {
                return;
            }
            List<Pending> instructions = buffer;
            buffer = null;
            candidates = 0;
            for (int i = 0; i < instructions.size(); i++) {
                replay(null, (Pending) instructions.get(i));
            }
        }

        /**
         * Abandons every pending rewrite: the buffered instructions are replayed unchanged and
         * the ambiguous call sites are reported.
         */
        private void abort() {
            if (buffer == null) {
                return;
            }
            for (int i = 0; i < buffer.size(); i++) {
                Pending instruction = (Pending) buffer.get(i);
                if (instruction.candidate) {
                    unapplied.add(where + " :: new " + instruction.text + " (ambiguous constructor pattern)");
                }
            }
            flush();
        }

        private void replay(Pending replacement, Pending instruction) {
            Pending actual = replacement == null ? instruction : replacement;
            switch (actual.shape) {
                case Pending.INSN:
                    super.visitInsn(actual.opcode);
                    return;
                case Pending.VAR:
                    super.visitVarInsn(actual.opcode, actual.operand);
                    return;
                case Pending.TYPE:
                    super.visitTypeInsn(actual.opcode, actual.text);
                    return;
                case Pending.FIELD:
                    super.visitFieldInsn(actual.opcode, actual.text, actual.text2, actual.text3);
                    return;
                case Pending.METHOD:
                    super.visitMethodInsn(actual.opcode, actual.text, actual.text2, actual.text3, actual.flag);
                    return;
                case Pending.LDC:
                    super.visitLdcInsn(actual.constant);
                    return;
                case Pending.INT:
                    super.visitIntInsn(actual.opcode, actual.operand);
                    return;
                case Pending.IINC:
                    super.visitIincInsn(actual.operand, actual.increment);
                    return;
                case Pending.JUMP:
                    super.visitJumpInsn(actual.opcode, actual.label);
                    return;
                case Pending.MULTI_ARRAY:
                    super.visitMultiANewArrayInsn(actual.text, actual.operand);
                    return;
                default:
                    return;
            }
        }

        // ---------------------------------------------------------------- instructions

        @Override
        public void visitInsn(int opcode) {
            if (buffering() && opcode == Opcodes.DUP) {
                // Part of a NEW/DUP pair that may disappear with the constructor it feeds.
                // Recorded so a resolved rewrite can drop both together.
                Pending pending = new Pending(Pending.INSN);
                pending.opcode = opcode;
                buffer.add(pending);
                return;
            }
            if (buffering() && isControlFlow(opcode)) {
                abort();
            }
            Pending pending = new Pending(Pending.INSN);
            pending.opcode = opcode;
            record(pending);
        }

        private boolean isControlFlow(int opcode) {
            return opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN
                    || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN
                    || opcode == Opcodes.ATHROW || opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH;
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            Pending pending = new Pending(Pending.INT);
            pending.opcode = opcode;
            pending.operand = operand;
            record(pending);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            Pending pending = new Pending(Pending.VAR);
            pending.opcode = opcode;
            pending.operand = varIndex;
            record(pending);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW) {
                RedirectTable.Redirect rule = constructorRule(type);
                if (rule != null) {
                    startBuffer();
                    Pending pending = new Pending(Pending.TYPE);
                    pending.opcode = opcode;
                    pending.text = type;
                    pending.candidate = true;
                    buffer.add(pending);
                    candidates++;
                    return;
                }
            }
            if (buffering() && opcode == Opcodes.CHECKCAST) {
                // Harmless inside a buffer, nothing to abandon.
            }
            Pending pending = new Pending(Pending.TYPE);
            pending.opcode = opcode;
            pending.text = type;
            record(pending);
        }

        /** The first constructor rule registered for an owner, used while buffering a NEW. */
        private RedirectTable.Redirect constructorRule(String owner) {
            List<RedirectTable.Redirect> rules = redirects.getRules();
            for (int i = 0; i < rules.size(); i++) {
                RedirectTable.Redirect rule = (RedirectTable.Redirect) rules.get(i);
                if (rule.getKind() == RedirectTable.CONSTRUCTOR && owner.equals(rule.getOwner())) {
                    return rule;
                }
            }
            return null;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (buffering() && opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
                Pending call = new Pending(Pending.METHOD);
                call.opcode = opcode;
                call.text = owner;
                call.text2 = name;
                call.text3 = descriptor;
                call.flag = isInterface;
                buffer.add(call);
                resolveConstructor(owner, descriptor);
                return;
            }
            if (buffering()) {
                Pending call = new Pending(Pending.METHOD);
                call.opcode = opcode;
                call.text = owner;
                call.text2 = name;
                call.text3 = descriptor;
                call.flag = isInterface;
                buffer.add(call);
                return;
            }
            redirectCall(opcode, owner, name, descriptor, isInterface);
        }

        private void redirectCall(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC) {
                RedirectTable.Redirect rule = redirects.find(RedirectTable.METHOD, owner, name, descriptor);
                if (rule != null) {
                    applied.add(where + " :: " + rule.describe());
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, rule.getTargetOwner(), rule.getTargetName(),
                            rule.getTargetDescriptor(), rule.isInterfaceTarget());
                    emitCastIfNeeded(descriptor, rule.getTargetDescriptor());
                    return;
                }
            }
            if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
                RedirectTable.Redirect rule = redirects.find(RedirectTable.VIRTUAL, owner, name, descriptor);
                if (rule != null) {
                    applied.add(where + " :: " + rule.describe());
                    // The receiver on the stack is already argument zero of the static shim.
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, rule.getTargetOwner(), rule.getTargetName(),
                            rule.getTargetDescriptor(), rule.isInterfaceTarget());
                    emitCastIfNeeded(descriptor, rule.getTargetDescriptor());
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        /**
         * Matches the {@code <init>} just recorded against the innermost buffered
         * {@code NEW owner} + {@code DUP} pair. On success both instructions are dropped and the
         * constructor call becomes a factory call plus a cast.
         */
        private void resolveConstructor(String owner, String descriptor) {
            RedirectTable.Redirect rule = redirects.find(RedirectTable.CONSTRUCTOR, owner, "<init>", descriptor);
            int callIndex = buffer.size() - 1;
            if (rule == null) {
                // A constructor we do not translate: anything still pending becomes ambiguous.
                finishBufferIfDone(false);
                return;
            }
            int newIndex = -1;
            for (int i = callIndex - 1; i >= 0; i--) {
                Pending instruction = (Pending) buffer.get(i);
                if (instruction.candidate && owner.equals(instruction.text)) {
                    newIndex = i;
                    break;
                }
            }
            boolean hasDup = newIndex >= 0 && newIndex + 1 < callIndex
                    && isDup((Pending) buffer.get(newIndex + 1));
            if (newIndex < 0 || !hasDup) {
                unapplied.add(where + " :: new " + owner + descriptor + " (pattern not recognised)");
                finishBufferIfDone(false);
                return;
            }
            // Replace the factory call in place of the reserved slot, then drop NEW/DUP.
            Pending factory = new Pending(Pending.METHOD);
            factory.opcode = Opcodes.INVOKESTATIC;
            factory.text = rule.getTargetOwner();
            factory.text2 = rule.getTargetName();
            factory.text3 = rule.getTargetDescriptor();
            factory.flag = rule.isInterfaceTarget();
            buffer.set(callIndex, factory);

            Pending cast = new Pending(Pending.TYPE);
            cast.opcode = Opcodes.CHECKCAST;
            cast.text = owner;
            buffer.add(cast);

            buffer.remove(newIndex + 1); // DUP
            buffer.remove(newIndex);     // NEW
            candidates--;
            applied.add(where + " :: " + rule.describe());
            finishBufferIfDone(true);
        }

        private boolean isDup(Pending instruction) {
            return instruction.shape == Pending.INSN && instruction.opcode == Opcodes.DUP;
        }

        private void finishBufferIfDone(boolean rewritten) {
            if (buffer == null) {
                return;
            }
            if (candidates > 0) {
                return;
            }
            flush();
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (buffering()) {
                Pending pending = new Pending(Pending.FIELD);
                pending.opcode = opcode;
                pending.text = owner;
                pending.text2 = name;
                pending.text3 = descriptor;
                buffer.add(pending);
                return;
            }
            int kind = -1;
            if (opcode == Opcodes.GETSTATIC) {
                kind = RedirectTable.FIELD_GET;
            } else if (opcode == Opcodes.PUTSTATIC) {
                kind = RedirectTable.FIELD_PUT;
            }
            if (kind >= 0) {
                RedirectTable.Redirect rule = redirects.find(kind, owner, name, descriptor);
                if (rule != null) {
                    applied.add(where + " :: " + rule.describe());
                    // A field rule stores the accessor's *return* type, so the call site needs a
                    // real method descriptor: ()T for a read, (T)V for a write.
                    String callDescriptor = kind == RedirectTable.FIELD_PUT
                            ? "(" + descriptor + ")V"
                            : "()" + rule.getTargetDescriptor();
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, rule.getTargetOwner(), rule.getTargetName(),
                            callDescriptor, rule.isInterfaceTarget());
                    emitCastIfNeeded(descriptor, rule.getTargetDescriptor());
                    return;
                }
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (buffering()) {
                Pending pending = new Pending(Pending.LDC);
                pending.constant = value;
                buffer.add(pending);
                return;
            }
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            if (buffering()) {
                Pending pending = new Pending(Pending.IINC);
                pending.operand = varIndex;
                pending.increment = increment;
                buffer.add(pending);
                return;
            }
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            if (buffering()) {
                abort();
            }
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(Label label) {
            // A label can be a jump target: the suppressed NEW/DUP must exist for that path.
            if (buffering()) {
                abort();
            }
            super.visitLabel(label);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            if (buffering()) {
                abort();
            }
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            if (buffering()) {
                Pending pending = new Pending(Pending.MULTI_ARRAY);
                pending.text = descriptor;
                pending.operand = numDimensions;
                buffer.add(pending);
                return;
            }
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (buffering()) {
                abort();
            }
            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (buffering()) {
                abort();
            }
            super.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitEnd() {
            if (buffering()) {
                abort();
            }
            super.visitEnd();
        }

        /**
         * The bridge targets return {@code java.lang.Object} or an adapter type so that a single
         * shim can serve every registry and every Minecraft type the bridge cannot name. Stack map
         * frames, however, describe the original type, so a cast is emitted whenever the two types
         * disagree. The cast is remapped later and therefore points at the final type.
         */
        private void emitCastIfNeeded(String originalDescriptor, String targetDescriptor) {
            String originalType = typeOf(originalDescriptor);
            String targetType = typeOf(targetDescriptor);
            if (originalType.equals(targetType) || "V".equals(originalType)) {
                return;
            }
            if (originalType.length() > 1 && originalType.charAt(0) == 'L') {
                super.visitTypeInsn(Opcodes.CHECKCAST, originalType.substring(1, originalType.length() - 1));
            }
        }

        /**
         * The type an instruction leaves on the stack: the return type for a method descriptor,
         * the field type for a field descriptor (which has no parentheses at all).
         */
        private String typeOf(String descriptor) {
            int close = descriptor.lastIndexOf(')');
            return close < 0 ? descriptor : descriptor.substring(close + 1);
        }
    }
}

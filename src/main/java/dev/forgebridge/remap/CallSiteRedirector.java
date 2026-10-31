package dev.forgebridge.remap;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Core-ASM {@link ClassVisitor} that rewrites the handful of call sites a rename
 * cannot express. Only {@code org.objectweb.asm} core is used, never asm-commons,
 * so this pass works in every launcher configuration.
 */
public final class CallSiteRedirector extends ClassVisitor {

    private final RedirectTable redirects;
    private final List<String> applied = new ArrayList<String>();
    private String className;

    public CallSiteRedirector(ClassVisitor next, RedirectTable redirects) {
        super(Opcodes.ASM9, next);
        this.redirects = redirects;
    }

    public List<String> getAppliedRedirects() {
        return applied;
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

    private final class RedirectingMethodVisitor extends MethodVisitor {
        private final String where;

        RedirectingMethodVisitor(MethodVisitor next, String where) {
            super(Opcodes.ASM9, next);
            this.where = where;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
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

        /**
         * The bridge targets return {@code java.lang.Object} or an adapter type so that a
         * single shim can serve every registry. Stack map frames, however, describe the
         * original type, so a cast is emitted whenever the two descriptors disagree.
         * The cast is remapped later and therefore points at the NeoForge type.
         */
        private void emitCastIfNeeded(String originalDescriptor, String targetDescriptor) {
            String originalType = typeOf(originalDescriptor);
            String targetType = typeOf(targetDescriptor);
            if (originalType.equals(targetType)) {
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

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
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
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }
}

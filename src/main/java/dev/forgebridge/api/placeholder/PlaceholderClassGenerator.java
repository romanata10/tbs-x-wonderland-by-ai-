package dev.forgebridge.api.placeholder;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Generates stub classes with ASM so that a reference to a removed Forge type fails with a
 * readable message instead of a {@code NoClassDefFoundError} during class loading.
 *
 * <p>Placeholders are never written into the {@code net.minecraftforge} namespace. They are
 * generated in the bridge's own package and reached through an exact class mapping, which
 * keeps them out of the way of the remapper and makes them trivially identifiable in a stack
 * trace ({@code dev.forgebridge.placeholders.*}).</p>
 */
public final class PlaceholderClassGenerator {

    public static final String PACKAGE = "dev/forgebridge/placeholders/";
    public static final String MARKER_FIELD = "FORGEBRIDGE_PLACEHOLDER";
    public static final String MESSAGE_FIELD = "BRIDGE_MESSAGE";

    private PlaceholderClassGenerator() {
    }

    /**
     * A writer that computes stack map frames.
     *
     * <p>Generated placeholders are class file version 21, where frames are mandatory at every
     * branch target. Frame computation asks for the common super class of two types; the
     * generated code only ever merges JDK types, and returning {@code Object} is always a valid
     * (if conservative) answer, so no game class has to be loadable while the stub is written.</p>
     */
    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
    }

    /** Maps a legacy internal name to the placeholder's internal name. */
    public static String placeholderName(String legacyInternalName) {
        String trimmed = legacyInternalName;
        String[] prefixes = new String[]{"net/minecraftforge/", "com/", "org/"};
        for (int i = 0; i < prefixes.length; i++) {
            if (trimmed.startsWith(prefixes[i])) {
                trimmed = trimmed.substring(prefixes[i].length());
                break;
            }
        }
        return PACKAGE + trimmed;
    }

    /** An empty interface: enough for marker types such as {@code ICapabilityProvider}. */
    public static byte[] generateInterface(String internalName, String message) {
        ClassWriter writer = newWriter();
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, MARKER_FIELD,
                "Z", null, Boolean.TRUE).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, MESSAGE_FIELD,
                "Ljava/lang/String;", null, message).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * A concrete stub: a public no-arg constructor, a static {@code get()} returning the stub
     * (so call chains such as {@code ModLoadingContext.get().registerConfig(...)} reach a real
     * object), and a {@code toString} that prints the explanation.
     */
    public static byte[] generateStubClass(String internalName, String message) {
        ClassWriter writer = newWriter();
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, MARKER_FIELD,
                "Z", null, Boolean.TRUE).visitEnd();

        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        // public static <Stub> get()
        MethodVisitor get = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get",
                "()L" + internalName + ";", null, null);
        get.visitCode();
        get.visitLdcInsn(message);
        get.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/forgebridge/api/placeholder/PlaceholderSupport",
                "warnOnce", "(Ljava/lang/String;)V", false);
        get.visitTypeInsn(Opcodes.NEW, internalName);
        get.visitInsn(Opcodes.DUP);
        get.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false);
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(2, 0);
        get.visitEnd();

        // public String toString()
        MethodVisitor toString = writer.visitMethod(Opcodes.ACC_PUBLIC, "toString",
                "()Ljava/lang/String;", null, null);
        toString.visitCode();
        toString.visitLdcInsn("[ForgeBridge placeholder] " + message);
        toString.visitInsn(Opcodes.ARETURN);
        toString.visitMaxs(1, 1);
        toString.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    /** An empty class: used for types whose members are never invoked directly. */
    public static byte[] generateEmptyClass(String internalName, String message) {
        ClassWriter writer = newWriter();
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, MARKER_FIELD,
                "Z", null, Boolean.TRUE).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, MESSAGE_FIELD,
                "Ljava/lang/String;", null, message).visitEnd();
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Logs what the placeholder is standing in for, and why the call cannot work. */
    public static byte[] generateSupportClass() {
        String internalName = "dev/forgebridge/api/placeholder/PlaceholderSupport";
        ClassWriter writer = newWriter();
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "SEEN",
                "Ljava/util/Set;", null, null).visitEnd();

        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/HashSet");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "SEEN", "Ljava/util/Set;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(2, 0);
        clinit.visitEnd();

        MethodVisitor warn = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "warnOnce",
                "(Ljava/lang/String;)V", null, null);
        warn.visitCode();
        warn.visitFieldInsn(Opcodes.GETSTATIC, internalName, "SEEN", "Ljava/util/Set;");
        warn.visitVarInsn(Opcodes.ALOAD, 0);
        warn.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true);
        Label skip = new Label();
        warn.visitJumpInsn(Opcodes.IFEQ, skip);
        warn.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        warn.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        warn.visitInsn(Opcodes.DUP);
        warn.visitLdcInsn("[ForgeBridge] placeholder in use: ");
        warn.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                "(Ljava/lang/String;)V", false);
        warn.visitVarInsn(Opcodes.ALOAD, 0);
        warn.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        warn.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false);
        warn.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        warn.visitLabel(skip);
        warn.visitInsn(Opcodes.RETURN);
        warn.visitMaxs(4, 1);
        warn.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}

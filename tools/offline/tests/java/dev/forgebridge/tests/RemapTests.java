package dev.forgebridge.tests;

import dev.forgebridge.remap.AsmSupport;
import dev.forgebridge.remap.BuiltinMappings;
import dev.forgebridge.remap.ClassRemapEngine;
import dev.forgebridge.remap.RedirectTable;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bytecode level verification of the three remapping pipelines. */
public final class RemapTests {

    /** Records the parts of a class file the tests care about. */
    static final class Capture extends ClassVisitor {
        String className;
        String superName;
        String fieldDescriptor;
        String annotationDescriptor;
        final Map<String, List<String>> instructions = new LinkedHashMap<String, List<String>>();
        private final List<String> current = new ArrayList<String>();
        private String currentMethod;

        Capture() {
            super(Opcodes.ASM9);
        }

        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name;
            this.superName = superName;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (this.annotationDescriptor == null) {
                this.annotationDescriptor = descriptor;
            }
            return super.visitAnnotation(descriptor, visible);
        }

        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (this.fieldDescriptor == null) {
                this.fieldDescriptor = descriptor;
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            this.currentMethod = name;
            final List<String> target = new ArrayList<String>();
            instructions.put(name, target);
            final Capture owner = this;
            return new MethodVisitor(Opcodes.ASM9) {
                public void visitFieldInsn(int opcode, String ownerName, String name2, String descriptor2) {
                    target.add(opcodeName(opcode) + " " + ownerName + "." + name2 + " " + descriptor2);
                }

                public void visitMethodInsn(int opcode, String ownerName, String name2, String descriptor2, boolean isInterface) {
                    target.add(opcodeName(opcode) + " " + ownerName + "." + name2 + " " + descriptor2);
                }

                public void visitTypeInsn(int opcode, String type) {
                    target.add(opcodeName(opcode) + " " + type);
                }
            };
        }

        List<String> method(String name) {
            List<String> list = (List<String>) instructions.get(name);
            Assert.notNull(list, "method " + name + " present in class");
            return list;
        }

        static String opcodeName(int opcode) {
            if (opcode == Opcodes.GETSTATIC) {
                return "GETSTATIC";
            }
            if (opcode == Opcodes.INVOKESTATIC) {
                return "INVOKESTATIC";
            }
            if (opcode == Opcodes.INVOKEVIRTUAL) {
                return "INVOKEVIRTUAL";
            }
            if (opcode == Opcodes.INVOKESPECIAL) {
                return "INVOKESPECIAL";
            }
            if (opcode == Opcodes.INVOKEINTERFACE) {
                return "INVOKEINTERFACE";
            }
            if (opcode == Opcodes.CHECKCAST) {
                return "CHECKCAST";
            }
            if (opcode == Opcodes.NEW) {
                return "NEW";
            }
            return "OP" + opcode;
        }
    }

    private RemapTests() {
    }

    private static ClassRemapEngine engine() {
        return new ClassRemapEngine(BuiltinMappings.get(), RedirectTable.builtin());
    }

    private static Capture capture(byte[] bytes) {
        Capture capture = new Capture();
        new ClassReader(bytes).accept(capture, 0);
        return capture;
    }

    private static boolean anyContains(List<String> instructions, String needle) {
        for (int i = 0; i < instructions.size(); i++) {
            String instruction = (String) instructions.get(i);
            if (instruction.indexOf(needle) >= 0) {
                return true;
            }
        }
        return false;
    }

    public static void testFullPipelineRemapsRegistriesAccess() {
        AsmSupport.override(AsmSupport.Level.FULL);
        try {
            byte[] input = Fixtures.legacyClass("com/example/LegacyListener");
            ClassRemapEngine.Result result = engine().remap(input);
            Assert.isFalse(result.hasError(), "no remap error");
            Assert.isTrue(result.isChanged(), "class changed");
            Assert.equal(AsmSupport.Level.FULL, result.getLevel(), "pipeline level");

            Capture classFile = capture(result.getBytes());
            Assert.equal("com/example/LegacyListener", classFile.className, "mod class keeps its name");
            Assert.equal("java/lang/Object", classFile.superName, "superclass preserved");
            Assert.equal("Ldev/forgebridge/compat/registry/LegacyRegistry;", classFile.fieldDescriptor,
                    "Forge registry field descriptor rewritten to the shim");
            Assert.equal("Lnet/neoforged/fml/common/Mod;", classFile.annotationDescriptor,
                    "annotation class rewritten");

            List<String> items = classFile.method("items");
            Assert.isTrue(anyContains(items, "INVOKESTATIC dev/forgebridge/compat/registry/RegistryAccess.items"),
                    "GETSTATIC ForgeRegistries.ITEMS became a RegistryAccess call: "
                            + items);
            Assert.isTrue(anyContains(items, "CHECKCAST dev/forgebridge/compat/registry/LegacyRegistry"),
                    "a cast restores the declared registry type: " + items);
            Assert.isFalse(anyContains(items, "GETSTATIC net/minecraftforge"), "no legacy GETSTATIC left");

            List<String> bus = classFile.method("eventBus");
            Assert.isTrue(anyContains(bus, "GETSTATIC net/neoforged/neoforge/common/NeoForge.EVENT_BUS"),
                    "plain field access is renamed, not redirected: " + bus);

            List<String> create = classFile.method("createRegister");
            Assert.isTrue(anyContains(create, "INVOKESTATIC dev/forgebridge/registries/DeferredRegisterBridge.create"),
                    "DeferredRegister.create is redirected to the shim: " + create);

            Assert.isTrue(result.getAppliedRedirects().size() >= 2, "redirects reported: "
                    + result.getAppliedRedirects());
        } finally {
            AsmSupport.override(null);
        }
    }

    public static void testStringConstantsAreReportedAsResidualReferences() {
        AsmSupport.override(AsmSupport.Level.FULL);
        try {
            ClassRemapEngine.Result result = engine().remap(Fixtures.legacyClass("com/example/LegacyListener"));
            List<String> residual = result.getResidualReferences();
            Assert.equalInt(1, residual.size(), "only the reflected string constant is left: " + residual);
            Assert.equal("net.minecraftforge.common.MinecraftForge", residual.get(0), "residual reference");
            Assert.notContains(new String(result.getBytes(), java.nio.charset.Charset.forName("ISO-8859-1")),
                    "net/minecraftforge/fml/", "no legacy class file entries remain");
        } finally {
            AsmSupport.override(null);
        }
    }

    public static void testCorePipelineAppliesRedirectsButLeavesConstantPoolToTheFallback() {
        AsmSupport.override(AsmSupport.Level.CORE);
        try {
            ClassRemapEngine.Result result = engine().remap(Fixtures.legacyClass("com/example/LegacyListener"));
            Assert.isFalse(result.hasError(), "no remap error");
            Assert.equal(AsmSupport.Level.CORE, result.getLevel(), "pipeline level");
            Capture classFile = capture(result.getBytes());
            Assert.equal("Ldev/forgebridge/compat/registry/LegacyRegistry;", classFile.fieldDescriptor,
                    "constant pool pass rewrote descriptors");
            List<String> items = classFile.method("items");
            Assert.isTrue(anyContains(items, "RegistryAccess.items"), "redirect applied in core mode: " + items);
            Assert.isTrue(result.getAppliedRedirects().size() >= 2, "redirects reported in core mode");
        } finally {
            AsmSupport.override(null);
        }
    }

    public static void testRawFallbackRemapsNamesWithoutAsm() {
        AsmSupport.override(AsmSupport.Level.NONE);
        try {
            ClassRemapEngine.Result result = engine().remap(Fixtures.legacyClass("com/example/LegacyListener"));
            Assert.isFalse(result.hasError(), "no remap error");
            Assert.equal(AsmSupport.Level.NONE, result.getLevel(), "pipeline level");
            Assert.isTrue(result.isChanged(), "raw rewrite changed the constant pool");
            Assert.equalInt(0, result.getAppliedRedirects().size(), "raw mode cannot redirect call sites");

            String latin = new String(result.getBytes(), java.nio.charset.Charset.forName("ISO-8859-1"));
            Assert.contains(latin, "net/neoforged/fml/common/Mod", "annotation class rewritten");
            Assert.notContains(latin, "net/minecraftforge/fml/common/Mod", "legacy FML class name gone");
            Assert.notContains(latin, "net/minecraftforge/registries/ForgeRegistries",
                    "legacy registry holder rewritten");
            // Documented limitation: the raw pass only knows slash-form names.
            Assert.contains(latin, "net.minecraftforge.common.MinecraftForge",
                    "dot-form string constants survive the raw pass");
        } finally {
            AsmSupport.override(null);
        }
    }

    public static void testCorruptBytesYieldAnErrorInsteadOfAnException() {
        AsmSupport.override(AsmSupport.Level.FULL);
        try {
            byte[] broken = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
            ClassRemapEngine.Result result = engine().remap(broken);
            Assert.isTrue(result.hasError(), "error reported");
            Assert.equalInt(broken.length, result.getBytes().length, "original bytes preserved");
            Assert.isFalse(result.isChanged(), "nothing claimed to be changed");
        } finally {
            AsmSupport.override(null);
        }
    }

    public static void testRemapBytesConvenienceThrowsOnError() {
        byte[] broken = new byte[]{0x00};
        boolean threw = false;
        try {
            engine().remapBytes(broken);
        } catch (java.io.IOException expected) {
            threw = true;
        }
        Assert.isTrue(threw, "remapBytes throws IOException for unparseable input");
    }

    public static void testBlockedTypesAreReportedByTheEngine() {
        ClassRemapEngine engine = engine();
        Assert.isTrue(engine.isBlocked("net/minecraftforge/network/simple/SimpleChannel"), "blocked type");
        Assert.contains(engine.blockedReason("net/minecraftforge/network/simple/SimpleChannel"),
                "payload", "reason mentions the replacement API");
        Assert.isFalse(engine.isBlocked("com/example/Anything"), "regular class not blocked");
    }
}

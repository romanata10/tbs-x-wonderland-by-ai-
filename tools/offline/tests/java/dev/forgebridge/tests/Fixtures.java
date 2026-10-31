package dev.forgebridge.tests;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the synthetic "legacy Forge mod" artifacts the tests translate. */
public final class Fixtures {

    public static final String[] LEGACY_CLASS_NAMES = new String[]{
            "net/minecraftforge/common/MinecraftForge",
            "net/minecraftforge/eventbus/api/IEventBus",
            "net/minecraftforge/registries/ForgeRegistries",
            "net/minecraftforge/registries/IForgeRegistry",
            "net/minecraftforge/registries/DeferredRegister",
            "net/minecraftforge/fml/common/Mod",
            "net/minecraftforge/fml/ModLoadingContext"
    };

    private Fixtures() {
    }

    /**
     * A class that does everything a translated mod must survive: a Forge type in a field
     * descriptor, a Forge field read, a renamed-class call, a string constant, and an
     * annotation with a class value.
     */
    public static byte[] legacyClass(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);

        // Field whose descriptor mentions a Forge type.
        writer.visitField(Opcodes.ACC_PRIVATE, "registry",
                "Lnet/minecraftforge/registries/IForgeRegistry;", null, null).visitEnd();

        // Annotation carrying a Forge class value.
        org.objectweb.asm.AnnotationVisitor annotation = writer.visitAnnotation(
                "Lnet/minecraftforge/fml/common/Mod;", true);
        annotation.visit("value", "examplemod");
        annotation.visitEnd();

        // Constant string that contains a Forge class name (reflection style usage).
        MethodVisitor constant = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "className", "()Ljava/lang/String;", null, null);
        constant.visitCode();
        constant.visitLdcInsn("net.minecraftforge.common.MinecraftForge");
        constant.visitInsn(Opcodes.ARETURN);
        constant.visitMaxs(0, 0);
        constant.visitEnd();

        // GETSTATIC ForgeRegistries.ITEMS -> must become a call into RegistryAccess.
        MethodVisitor items = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "items", "()Lnet/minecraftforge/registries/IForgeRegistry;", null, null);
        items.visitCode();
        items.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraftforge/registries/ForgeRegistries",
                "ITEMS", "Lnet/minecraftforge/registries/IForgeRegistry;");
        items.visitInsn(Opcodes.ARETURN);
        items.visitMaxs(0, 0);
        items.visitEnd();

        // GETSTATIC MinecraftForge.EVENT_BUS -> plain owner remap, no redirect.
        MethodVisitor bus = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "eventBus", "()Lnet/minecraftforge/eventbus/api/IEventBus;", null, null);
        bus.visitCode();
        bus.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraftforge/common/MinecraftForge",
                "EVENT_BUS", "Lnet/minecraftforge/eventbus/api/IEventBus;");
        bus.visitInsn(Opcodes.ARETURN);
        bus.visitMaxs(0, 0);
        bus.visitEnd();

        // DeferredRegister.create(ResourceLocation, String) -> signature-changed call site.
        MethodVisitor create = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "createRegister", "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)"
                        + "Lnet/minecraftforge/registries/DeferredRegister;", null, null);
        create.visitCode();
        create.visitVarInsn(Opcodes.ALOAD, 0);
        create.visitVarInsn(Opcodes.ALOAD, 1);
        create.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraftforge/registries/DeferredRegister",
                "create", "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)"
                        + "Lnet/minecraftforge/registries/DeferredRegister;", false);
        create.visitInsn(Opcodes.ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    public static final String FORGE_MODS_TOML =
            "modLoader=\"javafml\"\n"
            + "loaderVersion=\"[47,)\"\n"
            + "license=\"MIT\"\n"
            + "\n"
            + "[[mods]]\n"
            + "    modId=\"examplemod\"\n"
            + "    version=\"1.2.3\"\n"
            + "    displayName=\"Example Legacy Mod\"\n"
            + "    description='''A mod written for Forge 1.21.1.'''\n"
            + "\n"
            + "[[dependencies.examplemod]]\n"
            + "    modId=\"forge\"\n"
            + "    type=\"required\"\n"
            + "    versionRange=\"[47.1.0,)\"\n"
            + "    ordering=\"NONE\"\n"
            + "    side=\"BOTH\"\n"
            + "\n"
            + "[[dependencies.examplemod]]\n"
            + "    modId=\"minecraft\"\n"
            + "    type=\"required\"\n"
            + "    versionRange=\"[1.21,1.22)\"\n"
            + "    ordering=\"NONE\"\n"
            + "    side=\"BOTH\"\n";

    public static final String MIXIN_CONFIG =
            "{\n"
            + "  \"required\": true,\n"
            + "  \"package\": \"com.example.mixin\",\n"
            + "  \"compatibilityLevel\": \"JAVA_17\",\n"
            + "  \"refmap\": \"examplemod.refmap.json\",\n"
            + "  \"target\": \"net.minecraftforge.client.Foo\",\n"
            + "  \"mixins\": []\n"
            + "}\n";

    public static final String TAG_FILE =
            "{\n  \"replace\": false,\n  \"values\": [\n    \"#forge:gems\",\n"
            + "    { \"id\": \"minecraft:diamond\", \"required\": false }\n  ]\n}\n";

    public static final String RECIPE_FILE =
            "{\n  \"type\": \"minecraft:crafting_shaped\",\n"
            + "  \"conditions\": [ { \"type\": \"forge:mod_loaded\", \"modid\": \"examplemod\" } ],\n"
            + "  \"result\": { \"item\": \"minecraft:diamond\" }\n}\n";

    public static final String SERVICE_PROVIDER =
            "com.example.impl.LegacyExtension\n"
            + "net.minecraftforge.fml.javafmlmod.FMLModContainer\n";

    /** Writes a synthetic legacy ModLauncher zip. */
    public static File legacyJar(File directory, String name) throws IOException {
        File jar = new File(directory, name);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar));
        try {
            put(out, "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\r\n"
                    + "MixinConfigs: examplemod.mixins.json\r\n"
                    + "FMLModType: MOD\r\n"
                    + "\r\n");
            put(out, "META-INF/mods.toml", FORGE_MODS_TOML);
            put(out, "META-INF/services/com.example.Extension", SERVICE_PROVIDER);
            put(out, "META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider",
                    "net.minecraftforge.fml.javafmlmod.FMLModContainer\n");
            put(out, "META-INF/EXAMPLEMOD.SF", "Signature-Version: 1.0\n");
            putBytes(out, "com/example/LegacyListener.class", legacyClass("com/example/LegacyListener"));
            put(out, "examplemod.mixins.json", MIXIN_CONFIG);
            put(out, "data/forge/tags/items/gems.json", TAG_FILE);
            put(out, "data/examplemod/recipe/diamond_block.json", RECIPE_FILE);
            put(out, "assets/examplemod/lang/en_us.json", "{\"item.examplemod.rune\":\"Rune\"}\n");
        } finally {
            out.close();
        }
        return jar;
    }

    /** Jar with a Forge descriptor but no Forge classes: descriptor-only legacy mod. */
    public static File descriptorOnlyJar(File directory, String name) throws IOException {
        File jar = new File(directory, name);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar));
        try {
            put(out, "META-INF/mods.toml", FORGE_MODS_TOML);
            put(out, "com/example/Plain.class", "not a real class");
        } finally {
            out.close();
        }
        return jar;
    }

    public static void put(ZipOutputStream out, String name, String text) throws IOException {
        putBytes(out, name, text.getBytes(java.nio.charset.Charset.forName("UTF-8")));
    }

    public static void putBytes(ZipOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    public static File tempDir(String prefix) throws IOException {
        File dir = File.createTempFile(prefix, "");
        if (!dir.delete() || !dir.mkdirs()) {
            throw new IOException("could not create temp dir " + dir);
        }
        return dir;
    }

    public static void deleteTree(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                deleteTree(children[i]);
            }
        }
        file.delete();
    }
}

package dev.forgebridge.remap;

import java.util.ArrayList;
import java.util.List;

/**
 * Call-site rewrites that a pure rename cannot express.
 *
 * <p>Three families exist, and they differ in which bytecode shape they start from:</p>
 *
 * <ul>
 *   <li><b>field access</b> — {@code GETSTATIC/PUTSTATIC} against a field Forge removed.
 *       {@code ForgeRegistries.ITEMS} becomes {@code RegistryAccess.items()}.</li>
 *   <li><b>static call</b> — {@code INVOKESTATIC} against a method whose signature changed.
 *       {@code DeferredRegister.create(...)} becomes {@code DeferredRegisterBridge.create(...)}.</li>
 *   <li><b>instance call</b> — {@code INVOKEVIRTUAL/INVOKEINTERFACE} against a method that no
 *       longer exists, typically because Minecraft itself changed between 1.20.1 and 1.21.1.
 *       {@code stack.getOrCreateTag()} becomes {@code ItemStackNbtBridge.getOrCreateTag(stack)}:
 *       the receiver simply becomes argument zero of a static shim.</li>
 *   <li><b>constructor</b> — {@code NEW/DUP/INVOKESPECIAL <init>} against a constructor that
 *       became a static factory. {@code new ResourceLocation(id)} becomes
 *       {@code LegacyResourceLocations.parse(id)}. The {@code NEW} and its {@code DUP} are
 *       dropped, because the factory allocates the object itself.</li>
 * </ul>
 *
 * <p>Matching happens against the <em>original</em> names, i.e. before the package remapper
 * runs, and the emitted call is written in final terms.</p>
 */
public final class RedirectTable {

    public static final int FIELD_GET = 1;
    public static final int FIELD_PUT = 2;
    /** {@code INVOKESTATIC} to {@code INVOKESTATIC}. */
    public static final int METHOD = 3;
    /** {@code INVOKEVIRTUAL}/{@code INVOKEINTERFACE} to {@code INVOKESTATIC} (receiver first). */
    public static final int VIRTUAL = 4;
    /** {@code NEW + DUP + INVOKESPECIAL <init>} to {@code INVOKESTATIC} factory. */
    public static final int CONSTRUCTOR = 5;

    /** Types the bridge cannot name at compile time are erased to {@code java.lang.Object}. */
    private static final String ERASED = "Ljava/lang/Object;";
    private static final String OPAQUE_PREFIX = "net/minecraft/";

    /** One rewrite rule. */
    public static final class Redirect {
        private final int kind;
        private final String owner;
        private final String name;
        private final String descriptor;
        private final String targetOwner;
        private final String targetName;
        private final String targetDescriptor;
        private final boolean itf;

        public Redirect(int kind, String owner, String name, String descriptor,
                        String targetOwner, String targetName, String targetDescriptor, boolean itf) {
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.targetOwner = targetOwner;
            this.targetName = targetName;
            this.targetDescriptor = targetDescriptor;
            this.itf = itf;
        }

        public int getKind() {
            return kind;
        }

        public String getOwner() {
            return owner;
        }

        public String getName() {
            return name;
        }

        public String getDescriptor() {
            return descriptor;
        }

        public String getTargetOwner() {
            return targetOwner;
        }

        public String getTargetName() {
            return targetName;
        }

        public String getTargetDescriptor() {
            return targetDescriptor;
        }

        public boolean isInterfaceTarget() {
            return itf;
        }

        public boolean matches(int kind, String ownerName, String memberName, String desc) {
            if (this.kind != kind || !owner.equals(ownerName) || !name.equals(memberName)) {
                return false;
            }
            return "*".equals(descriptor) || descriptor.equals(desc);
        }

        public String describe() {
            String prefix;
            if (kind == METHOD) {
                prefix = "invoke";
            } else if (kind == VIRTUAL) {
                prefix = "call";
            } else if (kind == CONSTRUCTOR) {
                prefix = "new";
            } else {
                prefix = kind == FIELD_GET ? "get" : "put";
            }
            return prefix + " " + owner + "." + name + descriptor + " -> " + targetOwner + "." + targetName;
        }
    }

    private final List<Redirect> rules = new ArrayList<Redirect>();

    public RedirectTable addFieldGet(String owner, String name, String desc,
                                     String targetOwner, String targetName, String targetDesc) {
        rules.add(new Redirect(FIELD_GET, owner, name, desc, targetOwner, targetName, targetDesc, false));
        return this;
    }

    public RedirectTable addMethod(String owner, String name, String desc,
                                   String targetOwner, String targetName, String targetDesc) {
        rules.add(new Redirect(METHOD, owner, name, desc, targetOwner, targetName, targetDesc, false));
        return this;
    }

    /**
     * Rewrites an instance call to a static shim whose first parameter is the receiver.
     *
     * <p>The shim descriptor is derived from the original one: the receiver is prepended to the
     * parameter list and every {@code net/minecraft/...} reference type is erased to
     * {@code java.lang.Object}, because the bridge compiles without the game on its class path.
     * The caller keeps its own types: {@link CallSiteRedirector} inserts the {@code CHECKCAST}
     * that restores the declared return type.</p>
     */
    public RedirectTable addVirtualErased(String owner, String name, String desc,
                                          String targetOwner, String targetName) {
        rules.add(new Redirect(VIRTUAL, owner, name, desc, targetOwner, targetName,
                staticShimDescriptor(owner, desc), false));
        return this;
    }

    /** Rewrites {@code new Target(args)} to the static factory {@code targetName(args)}. */
    public RedirectTable addConstructorErased(String owner, String desc,
                                              String targetOwner, String targetName) {
        rules.add(new Redirect(CONSTRUCTOR, owner, "<init>", desc, targetOwner, targetName,
                factoryDescriptor(desc), false));
        return this;
    }

    public Redirect find(int kind, String owner, String name, String desc) {
        for (int i = 0; i < rules.size(); i++) {
            Redirect rule = (Redirect) rules.get(i);
            if (rule.matches(kind, owner, name, desc)) {
                return rule;
            }
        }
        return null;
    }

    public List<Redirect> getRules() {
        return new ArrayList<Redirect>(rules);
    }

    public int size() {
        return rules.size();
    }

    /** Number of rules of one kind, for tests and for {@code dump-mappings}. */
    public int count(int kind) {
        int total = 0;
        for (int i = 0; i < rules.size(); i++) {
            if (((Redirect) rules.get(i)).getKind() == kind) {
                total++;
            }
        }
        return total;
    }

    /** {@code (args)ret} becomes {@code (L<owner>;args)ret-with-minecraft-types-erased}. */
    public static String staticShimDescriptor(String owner, String methodDescriptor) {
        int close = methodDescriptor.indexOf(')');
        if (close < 0) {
            throw new IllegalArgumentException("not a method descriptor: " + methodDescriptor);
        }
        String arguments = methodDescriptor.substring(1, close);
        String returns = methodDescriptor.substring(close + 1);
        return "(" + erase("L" + owner + ";") + arguments + ")" + erase(returns);
    }

    /** {@code (args)V} becomes {@code (args)Ljava/lang/Object;}: a factory returns the object. */
    public static String factoryDescriptor(String constructorDescriptor) {
        int close = constructorDescriptor.indexOf(')');
        if (close < 0) {
            throw new IllegalArgumentException("not a method descriptor: " + constructorDescriptor);
        }
        String arguments = constructorDescriptor.substring(1, close);
        return "(" + arguments + ")" + ERASED;
    }

    /**
     * Replaces every {@code Lnet/minecraft/...;} reference type with {@code java.lang.Object}.
     * Arrays and primitives are left alone: no bridge rule needs them, and rewriting them
     * incorrectly would be worse than not handling them at all.
     */
    public static String erase(String descriptorOrType) {
        if (descriptorOrType == null || descriptorOrType.indexOf('L') < 0) {
            return descriptorOrType;
        }
        StringBuilder out = new StringBuilder(descriptorOrType.length() + 16);
        int i = 0;
        while (i < descriptorOrType.length()) {
            char c = descriptorOrType.charAt(i);
            if (c == 'L') {
                int end = descriptorOrType.indexOf(';', i);
                if (end < 0) {
                    out.append(descriptorOrType.substring(i));
                    break;
                }
                String internal = descriptorOrType.substring(i + 1, end);
                out.append(internal.startsWith(OPAQUE_PREFIX) ? ERASED : "L" + internal + ";");
                i = end + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * The default rule set.
     *
     * <p>Registry and registration rules are needed by both source versions (Forge 1.21.1 and
     * Forge 1.20.1). The Minecraft-level rules only ever match a 1.20.1 mod, because the
     * 1.21.1 API no longer contains those members at all - applying them to a 1.21.1 jar is a
     * no-op, not a risk.</p>
     */
    public static RedirectTable builtin() {
        RedirectTable table = new RedirectTable();

        // ---------------------------------------------------------------- registries
        String forgeRegistries = "net/minecraftforge/registries/ForgeRegistries";
        String access = "dev/forgebridge/compat/registry/RegistryAccess";
        String registry = "Ldev/forgebridge/compat/registry/LegacyRegistry;";

        table.addFieldGet(forgeRegistries, "ITEMS", "*", access, "items", registry);
        table.addFieldGet(forgeRegistries, "BLOCKS", "*", access, "blocks", registry);
        table.addFieldGet(forgeRegistries, "ENTITIES", "*", access, "entityTypes", registry);
        table.addFieldGet(forgeRegistries, "SOUND_EVENTS", "*", access, "soundEvents", registry);
        table.addFieldGet(forgeRegistries, "FLUIDS", "*", access, "fluids", registry);
        table.addFieldGet(forgeRegistries, "MOB_EFFECTS", "*", access, "mobEffects", registry);
        table.addFieldGet(forgeRegistries, "POTIONS", "*", access, "potions", registry);
        table.addFieldGet(forgeRegistries, "ENCHANTMENTS", "*", access, "enchantments", registry);
        table.addFieldGet(forgeRegistries, "PARTICLES", "*", access, "particleTypes", registry);
        table.addFieldGet(forgeRegistries, "CREATIVE_MODE_TABS", "*", access, "creativeModeTabs", registry);
        table.addFieldGet(forgeRegistries, "RECIPE_SERIALIZERS", "*", access, "recipeSerializers", registry);
        table.addFieldGet(forgeRegistries, "BLOCK_ENTITY_TYPES", "*", access, "blockEntityTypes", registry);
        table.addFieldGet(forgeRegistries, "MENU_TYPES", "*", access, "menuTypes", registry);
        table.addFieldGet(forgeRegistries, "ATTRIBUTES", "*", access, "attributes", registry);

        // Forge's DeferredRegister.create(ResourceLocation, String) became
        // DeferredRegister.create(ResourceKey<? extends Registry<T>>, String).
        String deferred = "net/minecraftforge/registries/DeferredRegister";
        String deferredShim = "dev/forgebridge/registries/DeferredRegisterBridge";
        table.addMethod(deferred, "create",
                "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)Lnet/minecraftforge/registries/DeferredRegister;",
                deferredShim, "create",
                "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)Ljava/lang/Object;");
        table.addMethod(deferred, "create",
                "(Lnet/minecraft/core/Registry;Ljava/lang/String;)Lnet/minecraftforge/registries/DeferredRegister;",
                deferredShim, "create",
                "(Lnet/minecraft/core/Registry;Ljava/lang/String;)Ljava/lang/Object;");

        // ------------------------------------------------- 1.20.1 -> 1.21.1: ItemStack NBT
        // 1.20.5 removed ItemStack's NBT accessors in favour of data components. Every access
        // now goes through the bridge, which stores the tag in DataComponents.CUSTOM_DATA.
        String stack = "net/minecraft/world/item/ItemStack";
        String nbt = "dev/forgebridge/compat/nbt/ItemStackNbtBridge";
        table.addVirtualErased(stack, "getTag", "()Lnet/minecraft/nbt/CompoundTag;", nbt, "getTag");
        table.addVirtualErased(stack, "getOrCreateTag", "()Lnet/minecraft/nbt/CompoundTag;", nbt, "getOrCreateTag");
        table.addVirtualErased(stack, "getTagElement", "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;",
                nbt, "getTagElement");
        table.addVirtualErased(stack, "getOrCreateTagElement", "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;",
                nbt, "getOrCreateTagElement");
        table.addVirtualErased(stack, "hasTag", "()Z", nbt, "hasTag");
        table.addVirtualErased(stack, "setTag", "(Lnet/minecraft/nbt/CompoundTag;)V", nbt, "setTag");
        table.addVirtualErased(stack, "removeTagKey", "(Ljava/lang/String;)V", nbt, "removeTagKey");

        // ------------------------------------------------- 1.20.1 -> 1.21.1: ResourceLocation
        // 1.21 made the constructors private; the replacements are static factories.
        String location = "net/minecraft/resources/ResourceLocation";
        String locationShim = "dev/forgebridge/compat/LegacyResourceLocations";
        table.addConstructorErased(location, "(Ljava/lang/String;)V", locationShim, "parse");
        table.addConstructorErased(location, "(Ljava/lang/String;Ljava/lang/String;)V",
                locationShim, "fromNamespaceAndPath");

        return table;
    }
}

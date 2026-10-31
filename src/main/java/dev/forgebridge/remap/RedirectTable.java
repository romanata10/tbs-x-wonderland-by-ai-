package dev.forgebridge.remap;

import java.util.ArrayList;
import java.util.List;

/**
 * Call-site rewrites that a pure rename cannot express.
 *
 * <p>Example: {@code ForgeRegistries.ITEMS} is a static field of type
 * {@code IForgeRegistry<Item>} in Forge. In NeoForge the equivalent data lives in
 * {@code BuiltInRegistries.ITEM} and is typed {@code Registry<Item>}. Renaming the
 * owner and the field produces a {@code NoSuchFieldError} in the best case and a
 * {@code ClassCastException} in the worst, so the access is redirected to a bridge
 * method that returns a {@code LegacyRegistry} adapter instead:</p>
 *
 * <pre>
 *   GETSTATIC net/minecraftforge/registries/ForgeRegistries.ITEMS
 *     -&gt; INVOKESTATIC dev/forgebridge/compat/registry/RegistryAccess.items()
 * </pre>
 *
 * <p>Matching happens against the <em>original</em> names, i.e. before the package
 * remapper runs, and the emitted call is written in final (NeoForge) terms.</p>
 */
public final class RedirectTable {

    public static final int FIELD_GET = 1;
    public static final int FIELD_PUT = 2;
    public static final int METHOD = 3;

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
            String prefix = kind == METHOD ? "invoke" : (kind == FIELD_GET ? "get" : "put");
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

    /**
     * The default redirect set: registry access plus the two registration entry points
     * whose signatures changed between Forge 1.21.1 and NeoForge 21.1.
     */
    public static RedirectTable builtin() {
        RedirectTable table = new RedirectTable();
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
        String shim = "dev/forgebridge/registries/DeferredRegisterBridge";
        table.addMethod(deferred, "create",
                "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)Lnet/minecraftforge/registries/DeferredRegister;",
                shim, "create",
                "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)Ljava/lang/Object;");
        table.addMethod(deferred, "create",
                "(Lnet/minecraft/core/Registry;Ljava/lang/String;)Lnet/minecraftforge/registries/DeferredRegister;",
                shim, "create",
                "(Lnet/minecraft/core/Registry;Ljava/lang/String;)Ljava/lang/Object;");
        return table;
    }
}

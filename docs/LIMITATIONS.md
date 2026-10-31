# Limitations

A translator is only honest if it says where it stops. Each item below is a real constraint of
the Forge 1.20.1 → NeoForge 21.1 move, not a TODO; where the bridge does something partial, the
partial behaviour is described.

## 1. APIs NeoForge deleted have no equivalent

These types are replaced by a generated placeholder so the mod can *link*, and the placeholder
explains itself when used (`toString()`, plus a one-time stderr warning):

| Removed type | Placeholder shape | Replacement in NeoForge 21.1 |
| --- | --- | --- |
| `fml/ModLoadingContext` | class with static `get()` | constructor injection of `ModContainer` |
| `fml/javafmlmod/FMLJavaModLoadingContext` | class with static `get()` | constructor injection of `IEventBus` |
| `common/capabilities/Capability` | empty class | `CapabilityToken` / `BlockCapability` |
| `common/capabilities/CapabilityManager` | class with static `get()` | `RegisterCapabilitiesEvent` |
| `common/capabilities/ICapabilityProvider` | empty interface | capability token lookups |
| `network/simple/SimpleChannel` | class with static `get()` | payload networking (`PayloadRegistrar`) |
| `network/NetworkRegistry` | class with static `get()` | `RegisterPayloadHandlersEvent` |
| `network/NetworkDirection` | empty class | removed with the simple channel API |
| `common/capabilities/CapabilityProvider` | class with static `get()` | `RegisterCapabilitiesEvent` + token lookups |
| `common/capabilities/ICapabilitySerializable` | empty interface | removed with the capability rewrite |
| `registries/RegistryBuilder` | class with static `get()` | `NewRegistryEvent` and data-driven registry keys |
| `registries/ObjectHolder` | annotation with no elements | removed; the loader that injected the field is gone |

A placeholder makes the call site *run*, not *work*. Code that registers a channel or a
capability through those types logs a diagnostic and does nothing. Porting that part by hand is
the only correct fix.

## 2. String constants are never rewritten

`Class.forName("net.minecraftforge.common.MinecraftForge")` and friends are reported as
**residual references** in the patch report but left untouched. Renaming a string that might be a
class name, a translation key, a config path or a log message is guesswork, and a wrong guess
fails far from its cause. If a mod reflects on Forge classes, its source has to change.

The same applies to the raw fallback pipeline (`NONE`), which only understands slash-form names:
dot-form strings survive it by definition.

### 1a. Item NBT lives in `custom_data` now

1.20.5 replaced item NBT with data components, so `ItemStack.getTag()` and its family do not
exist in 1.21.1 in any shape. The bridge emulates them (`dev.forgebridge.compat.nbt`):

* a tag is stored in the `custom_data` component, or in a weak map when no game is present;
* a component is an immutable value, so reading it yields a **copy**. Forge handed out the live
  tag, and mods mutate it in place (`getOrCreateTag().putString(...)`), which a copy would silently
  swallow. The bridge therefore keeps tracking every tag it hands out, compares its content with
  what was last written, and writes the difference back on the server/client tick
  (`NbtTickBridge`). A second read of the **same** stack flushes first, so a mod that writes and
  reads back sees its own change immediately; other readers see it within one tick.

What this cannot fix:

* a second `ItemStack` that was copied/serialised **between** the mutation and the flush sees the
  old data (tracking is keyed by the stack object, and a copied stack is a different object);
* tracking is per stack object and lives as long as the stack does, so a tag handed out for a
  long-lived stack is compared on every tick. Comparison is a string form of the tag, which is
  cheap for item data and measurable for large mod-written tags;
* `setTag` on a stack that shares its `custom_data` value with another stack writes a new
  component for the first one only, exactly as a value type would;
* a mod that keeps a reference to a tag it obtained once and mutates it much later still works,
  but only through the next flush — not synchronously;
* NBT size limits differ: `custom_data` is capped by the component codec like any other component,
  so a mod that used item NBT as unbounded storage can exceed it.

Disabling `nbtBridge` in the config leaves the bytecode redirects in place and turns the periodic
flush off, which makes the one-tick window unbounded; it exists for debugging, not for play.

## 3. Mixins

* `compatibilityLevel` is raised to `JAVA_21` and Forge class names inside the config are
  remapped.
* **Refmaps are not regenerated.** A refmap produced against Forge 1.21.1 maps *Minecraft*
  members, which are unchanged by this bridge, so refmaps usually still work — but a refmap that
  also covers Forge members will not.
* Mixin apply-time failures are reported by Mixin itself; the bridge cannot intercept them.
* Mixin renames (`@Mixin(targets = "net.minecraftforge...")`) written as strings inside Java code
  are handled as annotations by ASM, but `targets` values that are built dynamically are not.

## 4. Jar-in-jar

Nested jars are reported in the patch report and **not** translated. A nested legacy jar keeps
its Forge references and fails when it loads. Unpack the nested jar, translate it separately and
re-nest it.

The ModLauncher service faces the same class of limit: a `ITransformationService` is only
discovered from the *launch* class path. If a launcher hides it, use `prepare` on the CLI
(`docs/DEPLOYMENT.md`, layout B).

## 5. Registries

* `RegistryAccess` exposes 15 registries (the 14 `ForgeRegistries` field constants plus data
  components). Custom registries are resolved via `LegacyRegistryManager.getRegistry(id)`, which
  falls back to a `NullLegacyRegistry` when it cannot find them.
* `DeferredRegisterBridge.create` needs a `ResourceKey` for the target registry. When the key
  cannot be resolved (registry not present yet, unknown id, game not running) it returns `null`
  and logs once; the legacy mod's deferred registrations are then lost rather than crashing the
  game.
* Registration ordering is NeoForge's, not Forge's. A mod that depended on Forge's
  `RegistryEvent.Register` firing before another mod's must not assume the old order.
* Forge's registry lifecycle is translated, not recreated: `RegistryLifecycleBridge` replays
  `RegistryEvent.Register` to legacy listeners and then flushes everything that was queued
  through a NeoForge `DeferredRegister` (falling back to `RegisterEvent.register`). Objects a mod
  registered through `IForgeRegistry.register` therefore appear in the registry, but they are
  created at flush time, not at the moment the mod asked for it.
* `RegistryEvent.NewRegistry` (a mod creating its own registry) has **no** translation. The
  attempt is recorded, reported, and `createRegistry(...)` returns `null`. 1.21.1 builds custom
  registries from a data pack definition; a translated mod's entries for such a registry are not
  registered. This is a hard stop, not a partial success.

## 6. Events

* A listener whose parameter type is a NeoForge `Event` (after remapping) is registered on the
  real bus and behaves normally.
* Any other listener (a legacy-shaped event class, or an event the bridge has no remap for) is
  kept in `LegacyDispatcher` and only fires when something calls `EventBusBridge.post(...)` /
  `ForgeBridgeApi.postEvent(...)`. Cross-mod traffic from a native NeoForge mod that posts an
  event the translated mod listens to with such a type will not arrive.
* Cancellation, phases and `receiveCanceled` are not emulated for legacy-dispatched listeners.

## 7. Data

* `data/forge/tags/...` is relocated to `data/c/tags/...` and `#forge:` references become `#c:`,
  which matches NeoForge's namespace convention. A mod that *reads* tag contents at runtime by
  literal id (`"forge:gems"`) still asks for the old id and must be changed.
* Recipe/loot **conditions** named `forge:*` become `neoforge:*`. Custom conditions registered by
  the mod keep working only if the mod registers them again under the new id.
* Signature files (`.SF`, `.RSA`, `.DSA`, `.EC`) are dropped, because patching invalidates them.
  A jar that was signed is therefore unsigned after translation; nothing else in the jar is
  affected.

## 8. Access transformers

`accesstransformer.cfg` is not rewritten. Forge and NeoForge both use the SRG/AT syntax with
Mojang names, and this bridge does not change *Minecraft* names — so an AT that only widens
vanilla members keeps working. An AT naming a Forge member does not.

## 9. Version scope

The table describes exactly one move: **Forge 1.20.1 → NeoForge 21.1.176** (mapping set
`forge-1.20.1->neoforge-21.1`). Within that move the bridge covers the loader/API layer, the
registry lifecycle and item NBT. It does **not** cover structural vanilla changes between
Minecraft 1.20.1 and 1.21.1 that a mod touches directly:

* worldgen / biome JSON schema changes, and datapack format bumps (the bridge rewrites the
  `pack_format` it finds, nothing else);
* block-entity and entity data `CompoundTag` layouts written with `save`/`load` overrides;
* recipe and loot-table codec changes;
* client rendering changes (`BlockEntityRenderer` signatures, `GuiGraphics` renames that the
  mapping set does not know about);
* anything a 1.20.1 mod does with `ResourceLocation` *identity* (the factories return the modern
  type, so `==` comparisons remain as wrong as they were on 1.21.1).

`BridgeConstants` and `BuiltinMappings.TABLE_VERSION` carry the scope so a report can never be
mistaken for another.

## 10. What the bridge deliberately does not do

* It does not rewrite a mod's own classes into idiomatic NeoForge code. It rewrites names and
  call sites; everything else stays the mod's original logic.
* It does not touch non-legacy jars. A jar with NeoForge metadata and no `net.minecraftforge`
  references is left byte-identical.
* It does not silently swallow translation failures: a class that cannot be remapped keeps its
  original bytes and is listed in the report with the exception that stopped it.

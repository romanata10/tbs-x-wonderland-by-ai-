# Mappings: how a Forge 1.20.1 name becomes a NeoForge 21.1 name

ForgeBridge translates three different things, and they live in three different places:

| What | Where | Shape |
| --- | --- | --- |
| Names (classes, packages, members) | `dev.forgebridge.remap.MappingTable` | prefix rules + exact class rules + member rules |
| Call sites whose *signature* changed | `dev.forgebridge.remap.RedirectTable` | `GETSTATIC ForgeRegistries.X` → `INVOKESTATIC RegistryAccess.x()`, `stack.getTag()` → `ItemStackNbtBridge.getTag(stack)` |
| Types NeoForge deleted outright | `dev.forgebridge.api.placeholder.PlaceholderLibrary` | generated stub class per removed type |

All three are applied in one pass (`ClassRemapEngine.remap`), so a class is parsed and written
once per jar.

## 1. Name mapping (`MappingTable`)

The rules are evaluated in this order, and the first match wins:

1. **Exact class rule** — `net/minecraftforge/common/MinecraftForge` → `net/neoforged/neoforge/common/NeoForge`.
2. **Prefix rule**, in declaration order — the sorted-by-length removal happens for the raw
   fallback, not here. The built-in order deliberately puts the relocated sub-trees first:

   | prefix | becomes | why |
   | --- | --- | --- |
   | `net/minecraftforge/fml/` | `net/neoforged/fml/` | FML left `net.minecraftforge` |
   | `net/minecraftforge/eventbus/` | `net/neoforged/bus/` | the bus is its own library |
   | `net/minecraftforge/api/` | `net/neoforged/api/` | dist markers |
   | `net/minecraftforge/forgespi/` | `net/neoforged/neoforgespi/` | SPI package rename |
   | `net/minecraftforge/coremod/` | `net/neoforged/coremod/` | coremod API |
   | `net/minecraftforge/access/` | `net/neoforged/accesstransformer/` | AT API |
   | `net/minecraftforge/jarmod/` | `net/neoforged/jarmod/` | JiJ helpers |
   | `net/minecraftforge/jarsplitter/` | `net/neoforged/jarsplitter/` | jar splitter |
   | `net/minecraftforge/unsafe/` | `net/neoforged/unsafe/` | unsafe helpers |
   | `net/minecraftforge/versions/` | `net/neoforged/versions/` | version helpers |
   | `net/minecraftforge/` | `net/neoforged/neoforge/` | catch-all: everything else nests one level deeper |

3. **Inner class of a renamed outer class** — `ForgeConfigSpec$Builder` is caught by its exact
   outer rule even without a rule of its own.

Anything else (vanilla classes, the mod's own classes, third-party libraries) is returned
unchanged. That matters: the bridge must never invent names for classes it does not know.

Member rules are keyed by owner and accept a `*` descriptor wildcard. They exist mostly for the
`ForgeRegistries` flattening, where `BuiltInRegistries.ITEMS` became `BuiltInRegistries.ITEM`,
`ENTITIES` became `ENTITY_TYPE`, `TILE_ENTITIES` became `BLOCK_ENTITY_TYPE`, and so on.

## 2. Call-site redirects (`RedirectTable`)

Some Forge call sites compile but cannot work after the rename, because the field or method no
longer exists in that shape. Those are rewritten into calls to the bridge:

| Forge call site | becomes |
| --- | --- |
| `GETSTATIC ForgeRegistries.ITEMS` (and 13 other registry fields) | `INVOKESTATIC RegistryAccess.items()` + `CHECKCAST LegacyRegistry` |
| `DeferredRegister.create(ResourceLocation, String)` | `INVOKESTATIC DeferredRegisterBridge.create(...)` |
| `DeferredRegister.create(Registry, String)` | `INVOKESTATIC DeferredRegisterBridge.create(...)` |
| `ItemStack.getTag()` / `hasTag()` | `INVOKESTATIC ItemStackNbtBridge.getTag/hasTag(stack)` |
| `ItemStack.getOrCreateTag()` / `getTagElement(String)` / `getOrCreateTagElement(String)` | `INVOKESTATIC ItemStackNbtBridge.*` (receiver becomes argument 0) |
| `ItemStack.setTag(CompoundTag)` / `removeTagKey(String)` | `INVOKESTATIC ItemStackNbtBridge.setTag/removeTagKey` |
| `new ResourceLocation(String)` | `INVOKESTATIC LegacyResourceLocations.parse(String)` |
| `new ResourceLocation(String, String)` | `INVOKESTATIC LegacyResourceLocations.fromNamespaceAndPath(...)` |

A field rule stores the accessor's *return type*, so the emitted descriptor is synthesised
(`()T` for a read, `(T)V` for a write) and a `CHECKCAST` is emitted whenever the declared type
and the shim's return type disagree. Both details are load-bearing: without the cast the verifier
rejects the stack frame, and without the synthesised descriptor ASM reads the class descriptor as
a method descriptor and fails.

`RegistryAccess.items()` resolves `BuiltInRegistries.ITEM` once, wraps it in
`ReflectiveLegacyRegistry`, and caches it. When the game is not running (CLI, tests) it returns a
`NullLegacyRegistry` that logs every access instead of throwing, so a translation pass never
needs a Minecraft installation.

### 2a. Instruction shapes the redirector can and cannot rewrite

Two rule kinds exist for calls that are not a simple one-to-one rename:

* **`VIRTUAL`** — the 1.20.1 receiver is turned into argument 0 of a static shim
  (`stack.getOrCreateTag()` becomes `ItemStackNbtBridge.getOrCreateTag(stack)`). The shim
  descriptor erases `Lnet/minecraft/...;` types to `Ljava/lang/Object;`, and the transformer
  appends a `CHECKCAST` to the declared type so the surrounding code still verifies. Erasing is
  unavoidable: the shim class must load even when the Minecraft class it mentions is not on the
  translator's class path.
* **`CONSTRUCTOR`** — a constructor call becomes a factory call, which means the allocation
  sequence has to change, not just the call. The redirector buffers the `NEW` + `DUP` + argument
  push + `INVOKESPECIAL` run and, when the run is resolved, emits the factory call followed by a
  `CHECKCAST` to the constructed type, dropping the `NEW` and `DUP`.

The buffering is deliberately pessimistic. A label, a frame, a jump, a try-catch block, a
`visitMaxs` or the end of a method flushes the buffer **unchanged** and records the redirect as
*unapplied*; `JarPatcher` then warns about it in the patch report and `forgebridge remap-jar`
prints it. That is the difference between "this mod was translated and something might be subtly
wrong" and "this call site was left exactly as it was written, and here it is". A rewrite is only
performed when the allocation and the constructor call are provably adjacent on every path.

## 3. Placeholders (`PlaceholderLibrary`)

Types NeoForge removed cannot be mapped to anything. They are mapped to a generated stub in
`dev/forgebridge/placeholders/...` that carries a marker field, keeps the shape the mod uses
(empty class, empty interface, or a class with a static `get()`), and explains itself in
`toString()` and in a one-time stderr warning. The stubs are compiled with `COMPUTE_FRAMES`
because they target class file version 65, where frames are mandatory at branch targets.

`docs/LIMITATIONS.md` lists which types get a stub and what still cannot work.

## 4. Extension files

Besides the built-in table, `.map` files are loaded from:

* `config/forgebridge/mappings/*.map` (game directory), in directory order,
* the class-path resource `forgebridge/mappings/neo-21.1.map` shipped in the jar.

Both are overlays: a later rule wins over an earlier one, and they never remove built-in rules.
Format (whitespace separated, `#` starts a comment):

```
class  <from/internal/Name>  <to/internal/Name>
prefix <from/prefix/>        <to/prefix/>
field  <owner> <NAME> <descriptor|*> <NEW_NAME>
method <owner> <name> <descriptor|*> <newName>
block  <internal/Name> <reason words...>
```

Example — teaching the bridge about a class a mod ships under an old name:

```
# mymod shipped com/example/legacy/Registry until 1.20, then moved it
class com/example/legacy/Registry com/example/Registry
field com/example/Registry OLD_FIELD * FIELD
```

`MappingLoader.load(configDir, loader)` loads both layers and logs how many rules each file
contributed, which is the first thing to check when a class "should" have been remapped.

## 5. Pipelines and the residual audit

`AsmSupport` picks the strongest pipeline the class path allows:

| Level | Requires | What it does |
| --- | --- | --- |
| `FULL` | `asm` + `asm-commons` | `ClassRemapper` with a `Remapper` that consults the table, plus call-site redirects |
| `CORE` | `asm` | call-site redirects only; names are rewritten afterwards by the constant-pool pass |
| `NONE` | nothing | pure constant-pool rewrite of `CONSTANT_Utf8` entries (slash-form names only) |

`-Dforgebridge.asmLevel=FULL|CORE|NONE` pins a level for troubleshooting; the detected level is
reported by `forgebridge info` and in every patch report.

After a rewrite, `ReferenceAudit` scans the output for anything that still says
`net/minecraftforge` or `net.minecraftforge.`. Whatever it finds is reported as a *residual
reference* instead of being silently rewritten, because the usual cause is a string constant used
for reflection, and guessing at a class name in a string is how a translator breaks a mod's
behaviour without a stack trace. The one benign exception is `net/minecraftforge/eventbus/api/Event`,
which native NeoForge mods legitimately still name.

## 6. Cross-version NBT emulation

`ItemStack` NBT is the one 1.20.1 API that has no renamed equivalent: 1.20.5 replaced item NBT
with data components, so `getTag`/`getOrCreateTag` do not exist in any shape on 1.21.1. The
redirects above point at `dev.forgebridge.compat.nbt.ItemStackNbtBridge`, which stores the tag in
the `custom_data` component when the game is present, and in a weak map when it is not:

| Situation | Store | Read semantics |
| --- | --- | --- |
| `DataComponents.CUSTOM_DATA` resolves (game, or a stub class path) | `CustomDataTagStore` | a copy, because components are immutable |
| otherwise (CLI, offline tests) | `ShadowTagStore` | the live tag, like Forge |

Because the copying store hands out snapshots, mutation of a handed-out tag
(`getOrCreateTag().putString(...)`) would be lost. The bridge therefore remembers every tag it
handed out and writes it back from `flushDirty()`, which `NbtTickBridge` attaches to the server
and client tick events. Mutations are durable within one tick — a window no player can observe —
and immediately on `setTag`. That compromise is documented in `docs/LIMITATIONS.md`; it is a
consequence of components being values, not something the bridge can wish away.

## 7. Keeping the table honest

`MappingTests` asserts the table from both directions: every legacy registry field must have a
redirect whose target method exists on `RegistryAccess`, every prefix must beat the catch-all,
unknown names must pass through unchanged, and the 15 registry keys exposed by `RegistryAccess`
must match the 14 Forge field constants plus `dataComponents` (which has no Forge equivalent).
When NeoForge moves something again, that suite is where the change starts.

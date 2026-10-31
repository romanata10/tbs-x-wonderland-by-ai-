# Verification

The bridge is a bytecode translator, so "it compiles" proves almost nothing: a wrong descriptor,
a missing stack map frame or a misplaced constant produces a jar that builds and then fails at
class-load time inside someone's game. The verification suite therefore *runs* what it builds.

## Why there is a second build script

This project was developed in an environment without a JDK and without network access to Maven
Central or `maven.neoforged.net`, so `./gradlew build` cannot run there. `tools/offline/build.py`
reproduces the same pipeline with what is available:

| Gradle step | Offline equivalent |
| --- | --- |
| `compileJava` | Janino 3.1.9 driven through JPype, target 21 (class file 65) |
| `compileOnly` NeoForge/ModLauncher deps | 16 compile-only stubs under `tools/offline/stubs/java` |
| `jar` | `zipfile` packaging into `build/libs/forgebridge-1.0.0.jar` with the same manifest attributes |
| `test` | `tools/offline/tests/java`, compiled and run on a real JVM with the built jar + ASM |
| a second `test` JVM | `tools/offline/game-stubs/java` stands in for the Minecraft classes the NBT emulation reflects on |

```
python3 tools/offline/build.py            # compile, package, run both test stages
python3 tools/offline/build.py --no-tests # just the artifact
python3 tools/offline/build.py --clean    # delete build/
```

Requirements: Python 3.9+, `jpype1`, and a Java 21+ runtime (`JAVA_HOME`, `jdk4py`, or `java` on
`PATH`). The vendored jars in `tools/offline/lib` (Janino, commons-compiler, ASM) keep the
harness working without network access; they are build tooling and are never packaged into the
mod.

The stubs are *compile-only*: they mirror the ModLauncher/NeoForge signatures the bridge
implements, and are not part of the jar — the same relationship `compileOnly` has in
`build.gradle`. Two of them deliberately differ from the real API and say so in their javadoc:
`@SubscribeEvent` has no enum default (Janino cannot parse one) and `ITransformerExtension`
does not extend `Comparable` (Janino has no interface default methods). Neither difference is
reachable from the bridge's runtime behaviour.

## What the suite covers (89 tests, 10 suites)

| Suite | Subjects |
| --- | --- |
| `MappingTests` | prefix/class/member rules, inner classes, descriptor mapping, redirect coverage, `.map` overlays, blocked types, bootstrap wiring |
| `TomlTests` | block splitting, multiline strings, descriptor parsing, version ranges, `mods.toml` rewrite, idempotency, modern descriptors untouched, lossless render |
| `RemapTests` | all three pipelines on a synthetic legacy class: name mapping, registry redirect + cast, `DeferredRegister` redirect, residual audit, corrupt input, blocked types |
| `JarTests` | scan, patch, manifest/descriptor/tags/recipes/mixins/services/signatures, placeholder injection, dry run, the `.forgebridge-staging` → `forgebridge/legacy` workspace protocol, re-run idempotency |
| `PlaceholderTests` | the generated classes are *loaded and invoked*: marker field, `get()`, `toString()`, class file version 65 |
| `EventTests` | NeoForge vs legacy listener split, priority ordering, reflective annotation reading, no-bus safety |
| `RegistryTests` | 15 registry keys, null-registry behaviour outside the game, cache lifecycle, the registration queue, `DeferredRegisterBridge` failure paths |
| `CompatTests` | default config generation, config parsing (including `nbtBridge`), mod detection by file name, aliases/shared tags, the compatibility profiles, generated data pack |
| `CrossVersionTests` | the 1.20.1→1.21.1 layer: `ItemStack` NBT accessors and `ResourceLocation` constructors rewritten into bridge calls, the "cannot prove it, so report it" path, the NBT emulation (shadow store round trip, copied tags written back on flush), the registry lifecycle bridge through a recording `Registrar` (deferred register, namespace stripping, fallback, custom-registry refusal), and a synthetic `RegisterEvent` reaching both a legacy `RegistryEvent.Register` listener and the queue |
| `CliTests` | every CLI command, exit codes, dry run, `prepare` on a real directory |

Everything runs headless: no Minecraft, no NeoForge, no network. The suite creates its synthetic
"legacy mod" with ASM (`Fixtures`), so the fixtures are exactly the bytecode shapes the translator
must handle (Forge type in a field descriptor, `GETSTATIC ForgeRegistries.ITEMS`, a renamed
static call, a `DeferredRegister.create` call site, a dot-form string constant).

## Bugs the suite found (and that are now fixed)

Writing tests against a translator pays for itself immediately. These were real defects, each one
invisible to a compiler:

1. **Placeholders were invalid bytecode.** `get()` invoked `warnOnce` before pushing its argument
   and created the stub with `INVOKESTATIC <init>`; the branch in `warnOnce` had no stack map
   frames even though the class targets version 65. Every placeholder would have thrown
   `VerifyError` on first use. Fixed with correct instruction order, `NEW`/`INVOKESPECIAL`, and
   `COMPUTE_FRAMES`.
2. **Field redirects emitted a class descriptor as a method descriptor.** `GETSTATIC
   ForgeRegistries.ITEMS` became `INVOKESTATIC RegistryAccess.items(Ldev/...LegacyRegistry;)` —
   ASM read it as a method descriptor and threw `StringIndexOutOfBoundsException` mid-remap, so
   *every* jar with a registry access failed to translate in `FULL` mode. The cast was also never
   emitted, because the type comparison assumed a method descriptor. Both fixed.
3. **A single-line triple-quoted TOML string swallowed the rest of the file.** `mods.toml` with
   `description='''...'''` on one line made the parser treat everything after it as string body:
   dependency blocks disappeared, so `forge` dependencies were never renamed.
4. **`mods.toml` rewrites modified native NeoForge descriptors** by adding the optional bridge
   dependency. Now restricted to legacy descriptors.
5. **Two config keys were dead code.** `[mods] extra` and `[mappings] extraFiles` were unreachable
   behind the per-mod boolean branch, so extra mod ids and mapping overlays silently never loaded.
6. **Service files were never renamed.** The service name was passed to `mapClass` in dot form,
   which matches nothing in a table keyed by internal names.
7. **`PatchOptions.dryRun` was ignored** by the jar patcher, so a dry run wrote a real output jar.
8. **`-Dforgebridge.asmLevel`, `disableEventBridge`, `disableRegistryBridge` were declared but
   never honoured.** They are wired now (and are documented in `docs/DEPLOYMENT.md`).

## The second JVM: the NBT component path

`CompoundTags.isGamePresent()` asks the class path whether `net.minecraft.nbt.CompoundTag`
exists. That question cannot be answered both ways in one process, so the harness runs a second
JVM (`GamePathMain`) with `tools/offline/game-stubs` first on the class path. Those five stand-in
classes — `CompoundTag`, `DataComponentType`, `DataComponents`, `CustomData`, `ItemStack` — have
the same shapes and the same value semantics the bridge assumes: `CustomData.of(tag)` copies on
the way in, `copyTag()` copies on the way out.

Six checks run there: the lookups resolve, `getOrCreateTag` produces a real tag rather than the
map fallback, mutations of a handed-out copy reach the component, nested tag elements round trip,
`setTag(null)` clears the component, and `NbtTickBridge.install()` reports zero listeners when no
tick event exists. That stage found two real defects in `ItemStackNbtBridge`:

* a tag created by `getOrCreateTag()` was written once and then never tracked again, so
  `getOrCreateTag().putString(...)` lost the mutation as soon as the component store copied it;
* flushing dropped the tracking entry, so a second mutation of the same handed-out object was
  invisible. Tracking is now continuous and write-back is driven by comparing content
  (`Tracked.isDirty()`), and a read of the same stack flushes first, so a mod that writes and
  reads back sees its own change immediately.

Because the stand-ins are written from the same assumptions the bridge makes, this stage proves
the *bookkeeping*, not the real API surface. If `DataComponents.CUSTOM_DATA` were spelled
differently in the game, this suite would still pass and the bridge would still log "CustomData
component not found" at runtime.

## The cross-version layer in particular

`CrossVersionTests` is the suite that was added when the target changed from "Forge 1.21.1 mods"
to "Forge **1.20.1** mods". It asserts three separate things, and it is worth being precise about
what each one buys:

| Assertion | What it proves | What it does not |
| --- | --- | --- |
| `getOrCreateTag()` becomes `INVOKESTATIC ItemStackNbtBridge.getOrCreateTag` with a trailing `CHECKCAST` | the instruction layer rewrites the call site and keeps the stack types verifiable | that the shim works against a real `ItemStack` |
| `new ResourceLocation(String)` becomes `LegacyResourceLocations.parse` with the `NEW`/`DUP` dropped | allocation and constructor were recognised as one unit | that 1.21.1's `parse` exists under that exact signature (checked reflectively at runtime, not here) |
| a construction split by a branch is replayed untouched **and** reported | the redirector refuses what it cannot prove, loudly | that the reported case still *works*: it does not, the call site keeps calling the removed constructor |

The NBT tests drive the emulation through `ItemStackNbtBridge.TagStoreFactory`, so the copying
behaviour of a data component is reproduced faithfully (a `CopyingStore` that hands out copies)
without needing `CompoundTag` on the class path. The registry tests drive
`RegistryLifecycleBridge.Registrar`, so the DeferredRegister path and the `RegisterEvent` fallback
are both exercised without NeoForge.

What remains genuinely unverified offline: whether `DataComponents.CUSTOM_DATA` is reachable under
that exact name, whether `DeferredRegister.create` accepts the registry key the bridge builds, and
whether the tick events the NBT flush subscribes to are named as assumed. All three are resolved
reflectively and degrade to a logged warning, but "degrades" is not "works".

## Limits of this verification

* It proves the bridge's own logic and the bytecode it *produces structurally*. It cannot prove
  that a translated mod behaves identically inside a running game: no Minecraft classes, no
  NeoForge bus, no registry contents exist here.
* The remap pipeline is exercised on synthetic classes and on the bridge's own sources, not on a
  real third-party mod jar (none can be downloaded in this environment).
* The Gradle build itself has never run here; `build.gradle` is written against the NeoForge
  MDK for 1.21.1 and mirrors what the harness does, but `./gradlew build` still needs a machine
  with network access.

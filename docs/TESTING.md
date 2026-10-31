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

```
python3 tools/offline/build.py            # compile, package, run the suite
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

## What the suite covers (76 tests, 9 suites)

| Suite | Subjects |
| --- | --- |
| `MappingTests` | prefix/class/member rules, inner classes, descriptor mapping, redirect coverage, `.map` overlays, blocked types, bootstrap wiring |
| `TomlTests` | block splitting, multiline strings, descriptor parsing, version ranges, `mods.toml` rewrite, idempotency, modern descriptors untouched, lossless render |
| `RemapTests` | all three pipelines on a synthetic legacy class: name mapping, registry redirect + cast, `DeferredRegister` redirect, residual audit, corrupt input, blocked types |
| `JarTests` | scan, patch, manifest/descriptor/tags/recipes/mixins/services/signatures, placeholder injection, dry run, the `.forgebridge-staging` → `forgebridge/legacy` workspace protocol, re-run idempotency |
| `PlaceholderTests` | the generated classes are *loaded and invoked*: marker field, `get()`, `toString()`, class file version 65 |
| `EventTests` | NeoForge vs legacy listener split, priority ordering, reflective annotation reading, no-bus safety |
| `RegistryTests` | 15 registry keys, null-registry behaviour outside the game, cache lifecycle, the registration queue, `DeferredRegisterBridge` failure paths |
| `CompatTests` | default config generation, config parsing, mod detection by file name, aliases/shared tags, the compatibility profiles, generated data pack |
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

## Limits of this verification

* It proves the bridge's own logic and the bytecode it *produces structurally*. It cannot prove
  that a translated mod behaves identically inside a running game: no Minecraft classes, no
  NeoForge bus, no registry contents exist here.
* The remap pipeline is exercised on synthetic classes and on the bridge's own sources, not on a
  real third-party mod jar (none can be downloaded in this environment).
* The Gradle build itself has never run here; `build.gradle` is written against the NeoForge
  MDK for 1.21.1 and mirrors what the harness does, but `./gradlew build` still needs a machine
  with network access.

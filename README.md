# ForgeBridge

Run **Forge 1.20.1 mods on NeoForge 21.1** (Minecraft 1.21.1, Java 21).

The gap between those two versions is not a rename. Forge did not die between 1.20.1 and NeoForge
21.1 — it moved and it changed shape:

* packages were relocated (`net.minecraftforge.*` → `net.neoforged.*`) and a handful of classes
  were renamed;
* `ItemStack` lost its NBT accessors (`getTag`, `getOrCreateTag`, `getOrCreateTagElement`) when
  vanilla replaced item NBT with **data components** in 1.20.5;
* `ResourceLocation` lost its public constructors in favour of static factories;
* Forge's per-registry `RegistryEvent.Register` / `RegistryEvent.NewRegistry` lifecycle was
  replaced by a single `RegisterEvent` plus `DeferredRegister` and `NewRegistryEvent`;
* some APIs were deleted outright (`ModLoadingContext`, the capability system, `SimpleChannel`).

ForgeBridge translates a mod across that gap at the jar level and then provides the runtime shims
the translated bytecode links against. It is two things in one jar:

* a **ModLauncher transformation service** that can act before FML discovers mods, and
* a **mod** that registers registry, event and NBT bridges once the game is up.

```text
  legacy jar ──► scan ──► remap classes ──► instruction redirects (NBT, ResourceLocation)
                                        ──► rewrite descriptor, mixins, services,
                                            tags, recipe conditions ──► patched jar
                                   │
                                   └──► runtime shims: RegistryAccess, LegacyRegistry,
                                        DeferredRegisterBridge, RegistryLifecycleBridge,
                                        ItemStackNbtBridge (+ custom_data flush),
                                        LegacyDispatcher, placeholders for removed APIs
```

## Download

Prebuilt jar: **[`dist/forgebridge-1.0.0.jar`](dist/forgebridge-1.0.0.jar)** (168 KB, 113 entries,
`sha256 6e7f1df5efb377bdfb3bb3f999f0274c49bd784b74b071e1e4f0e27acddf4d8d`) — see the
[v1.0.0 release](https://github.com/romanata10/tbs-x-wonderland-by-ai-/releases/tag/v1.0.0) and
[`dist/README.md`](dist/README.md) for provenance. It is built by the offline harness, has never
run inside a real NeoForge installation, and is published as a pre-release for that reason.
GitHub blocked release-asset uploads from the build sandbox, so the jar is committed rather than
attached.

## Quick start

Translate every legacy jar in a `mods/` directory (originals are preserved):

```bash
java -jar forgebridge-1.0.0.jar prepare /path/to/instance/mods
```

The jar does not bundle ASM (ModLauncher supplies it in a game launch). Standalone runs need it
next to the bridge: `java -cp forgebridge-1.0.0.jar:asm-9.jar dev.forgebridge.cli.BridgeCli ...`.

…or just drop `forgebridge-1.0.0.jar` into `mods/` and launch once: the transformation service
does the same pass at startup, then tells you to restart so FML re-reads the new descriptors.

Check what would happen first, without touching anything:

```bash
java -jar forgebridge-1.0.0.jar scan mods/some_mod-1.0.jar
java -jar forgebridge-1.0.0.jar prepare mods/ --dry-run
java -jar forgebridge-1.0.0.jar dump-mappings net.minecraftforge.common.MinecraftForge
```

## Scope

| | |
| --- | --- |
| Source | Forge **1.20.1** (Minecraft 1.20.1, Java 17 bytecode; mapping set `forge-1.20.1->neoforge-21.1`) |
| Target | NeoForge **21.1.x**, Minecraft 1.21.1, Java 21 |
| Also handled | Forge 1.21.1 mods: the same rules fire, fewer of them match, the pipeline is unchanged |
| Not supported | Mods that call the 1.20.1 *world* API in ways 1.21 changed structurally (see `docs/LIMITATIONS.md`) |

Three remapping pipelines are available and selected automatically — full ASM `ClassRemapper`
when `asm-commons` is present, call-site-only core ASM when it is not, and a dependency-free
constant-pool rewriter as a last resort. `-Dforgebridge.asmLevel=FULL|CORE|NONE` pins one.

On top of the class mapping, every pipeline carries an **instruction layer** for the calls a mapping
table cannot express:

| 1.20.1 call site | What the transformer emits |
| --- | --- |
| `stack.getTag()` | `ItemStackNbtBridge.getTag(stack)` |
| `stack.getOrCreateTag()` | `ItemStackNbtBridge.getOrCreateTag(stack)` |
| `stack.getOrCreateTagElement("display")` | `ItemStackNbtBridge.getOrCreateTagElement(stack, "display")` |
| `new ResourceLocation("mod:thing")` | `LegacyResourceLocations.parse("mod:thing")` |

## Documentation

| Document | Contents |
| --- | --- |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | install layouts, CLI, config keys, files on disk, troubleshooting |
| [docs/MAPPINGS.md](docs/MAPPINGS.md) | how names are mapped, redirects, mapping file format, pipelines, residual audit |
| [docs/LIMITATIONS.md](docs/LIMITATIONS.md) | removed APIs and their placeholders, NBT emulation, registries, mixins, data |
| [docs/TESTING.md](docs/TESTING.md) | the offline verification harness, its two test classes-of-path (with and without game classes), and what it cannot prove |

## Building

On a machine with network access:

```bash
./gradlew build      # NeoForge 21.1 MDK build, jar in build/libs
```

Without a JDK toolchain or network (how this repository was developed), use the offline harness,
which compiles with Janino, packages the same jar and runs the verification suite:

```bash
python3 tools/offline/build.py        # 89 tests + a second JVM for the NBT component path
```

## Status

* 70 source files, three remap pipelines, two compatibility profiles (`wonderland`, `the_broken_script`).
* 89 verification tests across 10 suites plus 6 NBT component-path checks in a second JVM, all
  green offline.
* Not yet exercised in a real NeoForge installation — see the limits section of
  `docs/TESTING.md` before trusting it with a world you care about.

## Licence

See `gradle.properties` (`mod_license`) and `src/main/resources/META-INF/mods.toml`.

# ForgeBridge

Run **Forge 1.21.1 mods on NeoForge 21.1** (Minecraft 1.21.1, Java 21).

Forge did not die between 1.21.1 and NeoForge 21.1 — it moved. Packages were relocated
(`net.minecraftforge.*` → `net.neoforged.*`), a handful of classes were renamed, some registries
were flattened, and a few APIs were deleted outright (`ModLoadingContext`, capabilities,
`SimpleChannel`). ForgeBridge translates a mod across that gap at the jar level and then provides
the runtime shims the translated bytecode links against.

It is two things in one jar:

* a **ModLauncher transformation service** that can act before FML discovers mods, and
* a **mod** that registers registry/event bridges once the game is up.

```text
  legacy jar ──► scan ──► remap classes ──► rewrite descriptor, mixins, services,
                                            tags, recipe conditions ──► patched jar
                                   │
                                   └──► runtime shims: RegistryAccess, LegacyRegistry,
                                        DeferredRegisterBridge, LegacyDispatcher,
                                        placeholder classes for removed APIs
```

## Quick start

Translate every legacy jar in a `mods/` directory (originals are preserved):

```bash
java -jar forgebridge-1.0.0.jar prepare /path/to/instance/mods
```

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
| Source | Forge **1.21.1** (mapping set `forge-1.21.1->neoforge-21.1.176`) |
| Target | NeoForge **21.1.x**, Minecraft 1.21.1, Java 21 |
| Not supported | Forge 1.20.x and older (the vanilla 1.20 → 1.21 changes are out of scope) |

Three remapping pipelines are available and selected automatically — full ASM `ClassRemapper`
when `asm-commons` is present, call-site-only core ASM when it is not, and a dependency-free
constant-pool rewriter as a last resort. `-Dforgebridge.asmLevel=FULL|CORE|NONE` pins one.

## Documentation

| Document | Contents |
| --- | --- |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | install layouts, CLI, config keys, files on disk, troubleshooting |
| [docs/MAPPINGS.md](docs/MAPPINGS.md) | how names are mapped, redirects, mapping file format, pipelines, residual audit |
| [docs/LIMITATIONS.md](docs/LIMITATIONS.md) | removed APIs and their placeholders, mixins, jar-in-jar, registries, events, data |
| [docs/TESTING.md](docs/TESTING.md) | the offline verification harness, what it covers, and the bugs it caught |

## Building

On a machine with network access:

```bash
./gradlew build      # NeoForge 21.1 MDK build, jar in build/libs
```

Without a JDK toolchain or network (how this repository was developed), use the offline harness,
which compiles with Janino, packages the same jar and runs the verification suite:

```bash
python3 tools/offline/build.py        # 76 tests, no game or network required
```

## Status

* 61 source files, three remap pipelines, two compatibility profiles (`wonderland`,
  `the_broken_script`).
* 76 verification tests across 9 suites, all green offline.
* Not yet exercised in a real NeoForge installation — see the limits section of
  `docs/TESTING.md` before trusting it with a world you care about.

## Licence

See `gradle.properties` (`mod_license`) and `src/main/resources/META-INF/mods.toml`.

# Deploying ForgeBridge

ForgeBridge lets a **Forge 1.20.1 mod** run on **NeoForge 21.1.x** (Minecraft 1.21.1, Java 21).
It does that in two independent halves, and you can use either one:

1. **Translation** — a jar-level pass that rewrites class names, call sites, the descriptor,
   mixin configs, service files, tags and recipe conditions. It runs from the CLI or
   automatically at launch.
2. **Runtime** — shims the rewritten jar links against: `RegistryAccess`, `LegacyRegistry`,
   `DeferredRegisterBridge`, `RegistryLifecycleBridge`, `ItemStackNbtBridge` (with its per-tick
   flush), `LegacyDispatcher`, placeholder classes for removed APIs.

Translation alone gets a mod to load; the runtime shims are what keep it working afterwards.
Install both halves — that means the bridge jar stays in `mods/` even if you translate with the
CLI.

## Requirements

* Minecraft 1.21.1 with NeoForge 21.1.x (built and tested against 21.1.176), Java 21.
* Nothing else at runtime. ASM is used when the launcher provides it (it always does); without it
  the bridge falls back to a core-ASM or pure constant-pool pipeline.

The jar does **not** bundle ASM, so the CLI needs it on the class path when it is run outside a
launch:

```bash
java -cp forgebridge-1.0.0.jar:asm-9.7.jar dev.forgebridge.cli.BridgeCli prepare /path/to/mods
```

Without ASM the CLI exits with status 3 and says exactly that, instead of a stack trace.

## Layout A — let the bridge translate at launch (simplest)

1. Put `forgebridge-1.0.0.jar` in `mods/` next to the legacy mod.
2. Start the game once. The ModLauncher service scans `mods/`, translates every legacy jar it
   finds, keeps the original under `forgebridge/legacy/`, and writes `forgebridge/patch-report.txt`.
3. **Restart.** FML has already frozen the mod list for the launch in which the translation
   happened, so the new descriptor is only read on the next start. The log says so explicitly.

This works because the bridge registers itself as a `ITransformationService`. If you launch
through a launcher that only puts `mods/` jars on the *mod* class path (no ModLauncher service
discovery), the service never runs and you get the unmodified mod. Use layout B in that case.

## Layout B — translate first, ship the result (deterministic)

```
java -cp forgebridge-1.0.0.jar:asm-9.7.jar dev.forgebridge.cli.BridgeCli prepare /path/to/mods
```

`prepare` rewrites the legacy jars in place, moves the originals to
`forgebridge/legacy/<name>` and writes `forgebridge/patch-report.txt` in the game directory.
The bridge jar itself must still be present in `mods/` at runtime.

Other commands:

```
java -jar forgebridge-1.0.0.jar info                      # mapping set, pipeline, registry keys
java -jar forgebridge-1.0.0.jar scan mods/foo-1.0.jar     # is this a legacy jar?
java -jar forgebridge-1.0.0.jar remap-jar in.jar out.jar  # translate one jar to a new file
java -jar forgebridge-1.0.0.jar remap-class in.class out.class
java -jar forgebridge-1.0.0.jar placeholders outDir/      # inspect the generated stubs
java -jar forgebridge-1.0.0.jar dump-mappings [class...]  # show the rules
```

`scan` and `remap-jar` never modify their input, and `--dry-run` makes `prepare` report what it
*would* do without touching the filesystem.

## What ends up on disk

| Path (relative to the game directory) | Contents |
| --- | --- |
| `forgebridge/legacy/<jar>` | the original, untranslated jar |
| `forgebridge/patch-report.txt` | one report per patched jar: classes, redirects, blocked types, residual references, warnings |
| `forgebridge/generated-data/` | data pack with the generated `c:` tags, written only when shared tags exist |
| `forgebridge/forgebridge-compat.toml` | feature switches (written on first run) |
| `forgebridge/mappings/*.map` | your mapping overlays |
| `mods/<jar>` | the translated jar (in place) |

The translated jar carries `ForgeBridge-Patched: true`, `ForgeBridge-Version`,
`ForgeBridge-Pipeline` and `ForgeBridge-Source-Sha256` in its manifest, so a second pass
recognises it and skips it instead of translating twice. `Automatic-Module-Name` is rewritten
when it still says `minecraftforge`.

## Configuration

`config/forgebridge/forgebridge-compat.toml` (inside the game directory). Unknown keys are
ignored; missing keys keep their default.

```toml
[general]
enabled = true          # master switch for the compatibility layer
patchOnStartup = true   # run a translation pass when the bridge's mod constructor runs
verbose = false         # debug logging

[features]
eventBridge = true      # route @SubscribeEvent listeners
registryBridge = true   # route RegisterEvent into legacy registry objects
tagBridge = true        # write the generated c: tags
placeholders = true     # inject stub classes for removed APIs
crossModHooks = true    # enable the wonderland / the_broken_script profiles

[mods]
wonderland = true
the_broken_script = true
extra = ["some_other_legacy_mod"]   # additional ids to look for

[mappings]
extraFiles = ["my-overlay.map"]
```

System properties (all optional):

| Property | Effect |
| --- | --- |
| `-Dforgebridge.autopatch=false` | never rewrite jars at launch; only report what would change |
| `-Dforgebridge.asmLevel=FULL\|CORE\|NONE` | pin the remapping pipeline (troubleshooting) |
| `-Dforgebridge.disableEventBridge=true` | skip event bus wiring |
| `-Dforgebridge.disableRegistryBridge=true` | skip registry bridging |
| `-Dforgebridge.debug=true` | debug logging |

## Uninstalling

Delete the bridge jar and restore the jars from `forgebridge/legacy/` over the ones in `mods/`.
Nothing else outside `forgebridge/` and `config/forgebridge/` is modified, and no game files are
touched.

## Troubleshooting

**`NoClassDefFoundError: net/neoforged/...` while the mod loads.** The mapping table has no rule
for a class the mod uses. Check `forgebridge/patch-report.txt` (residual references), add a rule
to a `.map` file under `config/forgebridge/mappings/`, and re-run `prepare`.

**`[ForgeBridge] placeholder in use: ...` on stderr.** The mod calls an API NeoForge removed.
The message names the replacement; `docs/LIMITATIONS.md` explains what can and cannot be
bridged.

**A patched jar is not picked up.** Verify the manifest marker (`unzip -p mods/x.jar
META-INF/MANIFEST.MF`), and remember the restart requirement: FML scans `mods/` before the
bridge's mod code runs.

**Registry lookups return nothing.** Outside a running game (CLI, tests) every registry is a
`NullLegacyRegistry` by design. In game, check that `registryBridge` is not disabled and that the
log shows `Registry bridge attached to the mod event bus`.

**Translation changes nothing (0 classes remapped).** The jar probably was not a legacy jar.
`scan` prints the reason: descriptor dependency on `forge`, a `loaderVersion` in the Forge line,
or classes referencing `net.minecraftforge`. A jar carrying only NeoForge metadata is skipped on
purpose.

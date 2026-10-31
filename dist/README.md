# Published artifacts

`forgebridge-1.0.0.jar` — the ForgeBridge mod jar, built from the sources at this commit.

| | |
| --- | --- |
| Size | 168 KB (113 entries) |
| SHA-256 | `6e7f1df5efb377bdfb3bb3f999f0274c49bd784b74b071e1e4f0e27acddf4d8d` |
| Main class | `dev.forgebridge.cli.BridgeCli` |
| Manifest `ForgeBridge-MappingSet` | `forge-1.20.1->neoforge-21.1` |

Verify the download before using it:

```bash
sha256sum forgebridge-1.0.0.jar
```

## How it was built

By `tools/offline/build.py`, the Janino-based harness, because the build sandbox cannot reach
Maven Central or `maven.neoforged.net` and therefore cannot run `./gradlew build`. Sources and
resources are the same as the Gradle build; the manifest is smaller (no vendor or
`Enable-Native-Access` attributes) and the classes are compiled by Janino instead of `javac`.

```bash
python3 tools/offline/build.py --clean
# passed: 89, failed: 0 ; game path: all checks passed
```

## Caveat

This jar has never been launched inside a real NeoForge installation. The reflective lookups listed
in `docs/TESTING.md` (the `custom_data` component, `DeferredRegister.create`, the tick event names)
are unverified: each degrades to a logged warning rather than a crash. Test it on a copied instance,
not on a world you care about.

A GitHub release asset could not be uploaded from the build sandbox (`uploads.github.com` is not
reachable); the file is committed here instead so it is still downloadable:

```bash
curl -LO https://raw.githubusercontent.com/romanata10/tbs-x-wonderland-by-ai-/<commit>/dist/forgebridge-1.0.0.jar
```

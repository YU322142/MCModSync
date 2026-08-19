# MCSync 2.0

MCSync 2.0 is a Java 21 pre-start OTA publisher and client updater for Minecraft modpacks. It keeps the historical `mcmodsync` technical mod ID and configuration paths so installed 1.9.x clients can upgrade in place, while new channels use the structured `mods-v5.json` manifest.

## Core behavior

- Checks the selected release before normal mod initialization.
- Downloads and verifies changed files before applying one atomic transaction.
- Uses a monotonic release sequence to reject downgrades and same-sequence forks.
- Supports required and recommended mods, optional resource/shader packs, managed server lists, and key-level TOML/JSON/properties configuration changes.
- Keeps save data, player data, worlds, screenshots, maps, logs, and launcher state outside managed scopes.

## Mod identity and upstream resolution

Only direct `mods/*.jar` files may use Modrinth or CurseForge sources.

1. MCSync computes the current JAR SHA-256 for local identity and manifest locking.
2. Modrinth lookup uses the exact current-file SHA-512.
3. CurseForge lookup uses the exact official fingerprint.
4. A platform source is accepted only after an exact match. Otherwise the publisher creates a local-hosted entry.
5. Filename, display name, and version text never establish upstream identity.
6. A unique mod ID may inherit descriptions and selection metadata from an older v5 manifest after an upgrade, but the download source is always resolved again from the new JAR.
7. Every official, mirror, direct, or publisher-hosted download must match the v5 size and SHA-256 before installation.

## Compatibility

- Product and artifact name: `MCSync`
- Current release: `2.0.0`
- Technical mod ID: `mcmodsync`
- Current manifest: `mods-v5.json`
- Preserved migration inputs: legacy v1-v4 manifests, `modsync.properties`, `.modsync/`, and the configuration bootstrap JAR

Legacy materials belong at their historical URLs. A new v5 channel does not need to duplicate v4 files beside `mods-v5.json`.

## Publisher workflow

Run `java -jar MCSync-2.0.0.jar`, select a tested client root, review the Mods tab, define managed scopes and configuration mutations, then validate and export. Upload immutable release files first and publish `mods-v5.json` last.

The Mods tab can import a previous v5 catalog without changing other project settings. Current client files remain authoritative: deleted mods are not resurrected, exact hashes are matched first, and descriptions are retained only where the current mod can be identified safely.

See also:

- [Operations](MCSYNC-2.0-OPERATIONS.md)
- [Requirements and security boundaries](MCSYNC-2.0-REQUIREMENTS.md)
- [Development structure](MCSYNC-2.0-DEVELOPMENT.md)

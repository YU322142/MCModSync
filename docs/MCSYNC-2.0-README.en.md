# MCSync 2.0

MCSync is a Java 21 pre-start OTA publisher and client updater for Minecraft modpacks.

Current release: **2.0.0**

Current manifest: **mods-v5.json**
Technical mod ID: **mcmodsync**

The historical technical identity is intentionally preserved so installed 1.9.x clients can upgrade in place.

## Managed content

MCSync can manage mods, resource packs, shader packs, KubeJS content, TACZ packs, maid model packs, selected configuration files, first-install options, and an optional server list.

It does not synchronize worlds, player data, map exploration, screenshots, logs, launcher accounts, Java settings, or secrets.

## Startup lifecycle

1. Read local configuration.
2. Fetch and validate the v5 manifest.
3. Reject release-sequence downgrade or fork.
4. Ask for newly introduced optional content.
5. Download and verify changed files.
6. Apply one backed-up transaction.
7. Exit for restart when loaded content changed; otherwise continue Minecraft startup.

Loaded JARs cannot be safely replaced in the same JVM, so mod and startup-configuration changes require a restart.

## File identity

Content establishes identity.

- SHA-256 identifies local files and locks manifest payloads.
- Modrinth lookup uses exact SHA-512.
- CurseForge lookup uses the official fingerprint.
- Filename, display name, and version text never prove upstream identity.
- A unique mod ID may inherit descriptions and selection metadata after an upgrade, but the current JAR is always resolved again.
- Every official, mirror, direct, or publisher-hosted download must match the v5 size and SHA-256.

Only direct `mods/*.jar` files are queried against mod platforms. Other content is handled as ordinary release files.

## Publishing

Run:

```text
java -jar MCSync-2.0.0.jar
```

Select a tested client root, review Mods, choose managed scopes, add guarded configuration mutations, validate, and export. Upload immutable files first and publish `mods-v5.json` last.

Importing an older v5 catalog in the Mods tab preserves safely matched editorial metadata without changing other project settings or resurrecting deleted mods.

## Documentation

- [Chinese README](../README.md)
- [Operations](MCSYNC-2.0-OPERATIONS.md)
- [Requirements and security boundaries](MCSYNC-2.0-REQUIREMENTS.md)
- [Development structure](MCSYNC-2.0-DEVELOPMENT.md)
- [Legacy upgrade guide](中文使用指南.md)

## Build

```powershell
.\build.ps1
```

Artifacts are written to `out/`.

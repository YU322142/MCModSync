# MCSync 2.0

MCSync is a pre-start OTA tool for Minecraft modpacks. Before normal mod initialization, it checks the release manifest, downloads and verifies changes, and then updates the client as a transaction.

Current stable version: **2.0.0**

Runtime: **Java 21**

Current manifest: **mods-v5.json**
Technical mod ID: **mcmodsync** (retained for compatibility with 1.9.x)

[中文](../README.md) · [更新日志](../CHANGELOG.zh-CN.md) · [Changelog](../CHANGELOG.md) · [Documentation index](README.md) · [Publishing and operations](MCSYNC-2.0-OPERATIONS.md) · [Requirements and security boundaries](MCSYNC-2.0-REQUIREMENTS.md) · [Development structure](MCSYNC-2.0-DEVELOPMENT.md) · [Legacy upgrade guide](中文使用指南.md)

## What MCSync Manages

The following content may be included in a publishing project by default:

- `mods/`
- `resourcepacks/`
- `shaderpacks/`
- `kubejs/`
- `tacz/`
- `tlm_custom_pack/`
- explicitly selected files or settings from `config/` and `defaultconfigs/`
- `options.txt` for first-time installation
- an optional `servers.dat`

MCSync does not synchronize save state and must not manage:

- `saves/`, world chunks, player data, or SavedData
- Xaero/JourneyMap exploration data
- logs, crash reports, screenshots, or caches
- launcher accounts, Java paths, memory settings, or login credentials
- server secrets, tokens, whitelists, operator lists, or private addresses

## Startup Flow

1. MCSync reads the local `modsync.properties`.
2. It fetches and strictly parses `mods-v5.json`.
3. It checks the release sequence and rejects both downgrades and forks at the same sequence.
4. It presents the selection screen for newly added recommended content.
5. It downloads selected changes and verifies every file's size and SHA-256.
6. It creates a backup and applies one atomic transaction.
7. If a JAR or startup-time configuration changed, it exits so the player can restart before entering the game.
8. If nothing changed, normal Minecraft loading continues.

MCSync does not claim to hot-replace mods after the JVM has loaded their JARs. Updates involving mods, KubeJS startup scripts, or startup-time configuration require a restart.

During NeoForge 1.21.1 startup, MCSync reuses NeoForge's early-loading window to show the phase, current file, percentage, completion state, and errors; it does not open a separate synchronization window. Fabric, mobile, or helper-process environments without an early loading window fall back to the title, logs, and `.modsync` status files. The Minecraft-window recommended-content selection remains available once the game UI can be displayed.

## File Identity and Upstream Matching

File content is the only reliable identity.

- Local installation, v5 import, backup, and rollback use **SHA-256**.
- Modrinth lookup uses the current JAR's **SHA-512**.
- CurseForge's fingerprint is only a platform-file matching signal, not byte-level proof; the publisher must also download the candidate and verify the current JAR's size and SHA-256. If that verification cannot be completed, the CurseForge source is abandoned and no downloadable candidate is generated.
- Filenames, display names, and version strings are not used to confirm an upstream file.
- A unique `modId` may inherit editable metadata such as descriptions and required/recommended state after a version upgrade.
- After metadata inheritance, the current JAR is still queried against upstream sources again; old download coordinates are not reused directly.
- Official, mirror, direct-link, and publisher-hosted downloads must all match the size and SHA-256 locked in v5.

During export, the publisher resolves pinned Modrinth/CurseForge coordinates into file URLs whose content matches the current JAR hash. At startup, a player fetches only the server-published v5 manifest and verifies local files; no mod platform is contacted while local files are correct, and a download occurs only when a file is missing or damaged. Metadata lookup remains only as a compatibility fallback for an older v5 manifest that lacks a pinned Modrinth file URL.

Only JARs located directly in `mods/` are queried against Modrinth, CurseForge, or their mirrors. Resource packs, shaders, KubeJS, configuration, TACZ packs, and maid model packs are never mistakenly sent to mod-platform matching.

## Required and Recommended Content

- **Required**: a missing file or hash mismatch must be repaired; failure blocks the current launch.
- **Recommended**: when first introduced, or when the recommended set grows, content is selected inside the Minecraft window and is selected by default; after a player deselects it, MCSync does not forcibly restore it.
- Resource packs and shader packs may also be optional and support select-all and clear-all actions.
- Mods deleted from the current client are not resurrected when an older v5 manifest is imported.

## Publishing a v5 Release

1. Prepare and fully test a client root.
2. Run `java -jar MCSync-2.0.0.jar`.
3. Select the client root under “Publishing Project”.
4. On the “Mods” tab, review required/recommended state, bilingual descriptions, and upstream matching results.
5. Under “Synchronization Scope”, confirm the directories to manage.
6. Under “Configuration OTA”, add only configuration keys that genuinely need a uniform change.
7. If server-list synchronization is needed, select a tested `servers.dat`.
8. Under “Validation and Export”, resolve every blocker before exporting.
9. Upload immutable files first, and upload `mods-v5.json` last.

The **complete output directory of the previous publication** may be selected on the “Publishing Project” tab. MCSync automatically locates its newest release record and compares it with the actual files in the current client directory by size and SHA-256; the publisher does not manually select an earlier `mods-v5.json`. Unchanged publisher-hosted files reuse their previous immutable URLs and are not copied again, while the current `releases/<releaseSequence>/` contains only new or changed upgrade files. The output root also contains `UPLOAD-PLAN.json`, `UPLOAD-GUIDE.zh-CN.md`, and a fully equivalent `UPLOAD-GUIDE.en.md`, identifying additions/replacements, reuse, external downloads, and removed paths.

When republishing, an older `mods-v5.json` may be imported only on the Mods tab. It inherits safely attributable mod metadata without changing other publishing settings; hashes and download-source matching are then recalculated from the current JARs.

When “Scan and Detect Upgrades” is used directly, the current `mods/` directory becomes the authoritative set and rebuilds the Mods table. A newer JAR with a unique mod ID replaces the old row and inherits required/recommended state, bilingual descriptions, side, and platform restrictions; download sources are still rematched from the newer JAR's hash. If multiple different JARs or versions with the same mod ID coexist, the GUI marks every related row as a conflict and blocks export.

## Recommended Cloud Layout

```text
channel/stable/
├─ mods-v5.json
├─ releases/
│  └─ <release-sequence>/
│     ├─ mods/
│     ├─ resourcepacks/
│     ├─ shaderpacks/
│     ├─ kubejs/
│     └─ other-managed-files/
└─ server-list/
   ├─ serverlist.txt
   └─ servers.dat
```

Upgrade materials used by legacy 1.6.x, 1.7, and 1.9.x clients must remain at their original URLs. The new v5 directory does not need an adjacent v4 file. See the [legacy upgrade guide](中文使用指南.md).

## Minimal Client Configuration

```properties
manifest=https://files.example.com/minecraft/channel/stable/mods-v5.json
language=auto
strict=true
requireManifest=true
syncResourcePacks=false
syncServerList=false
connectTimeoutSeconds=15
requestTimeoutSeconds=300
fileOperationRetries=12
```

Real endpoints and credentials must not be committed to a public source repository.

## Build and Test

Windows PowerShell:

```powershell
.\build.ps1
```

The build runs the complete test suite and produces:

- `out/MCSync-2.0.0.jar`
- `out/MCSync-2.0.0-source.zip`
- Chinese documentation and example configuration

## Compatibility Notes

The product name has changed to MCSync, but the following technical entry points are retained so installed legacy clients can upgrade:

- `mcmodsync` mod ID
- `modsync.properties`
- `.modsync/`
- `MCModSync-Config.jar`
- v1-v4 manifest parsing
- the 1.9.x upgrade chain

Do not remove these entry points merely to make the naming uniform.

## License

See [LICENSE](../LICENSE).

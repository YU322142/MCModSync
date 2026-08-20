# Changelog

[中文](CHANGELOG.zh-CN.md)

## 2.0.0

- Replaced timestamp-named release payload directories with a SHA-256 content-addressed object store: `objects/sha256/<first-two>/<full-hash>`. Release sequences now serve anti-downgrade checks only. Complete historical manifests live under `manifests/<releaseId>.json`, and the stable manifest is replaced last.
- Added a fresh full-publication mode that can reuse exact-hash platform verification evidence without reusing or omitting publisher-hosted payloads. This allows a clean baseline publication while avoiding redundant Modrinth/CurseForge verification downloads.
- Changed legacy gateway generation to opt-in in the GUI and added a command-line full cloud publisher. The generated MCSync JAR remains a required managed Mod and is verified against the manifest/object hash.
- The Remote and Legacy Upgrade page can now include legacy gateways in a complete publication or generate a standalone upload package without rescanning the client or rebuilding the schema-v5 object store. The package contains permanent 1.9.x and 1.6.x/1.7.x entries, the post-upgrade stable v5 address, a machine-readable artifact manifest, SHA-256 sums, and content-equivalent Chinese and English upload guides. Older publisher projects that do not contain this option keep it disabled.
- Prevented launch loops caused by self-rewriting Iris, Indigo, and PacketFixer property files: Iris/Indigo are first-install files, while PacketFixer is routed away from whole-file replacement.

- Fixed publisher scans failing to append files after adding synchronization scopes. Safe-content scanning now follows the current scope table and includes `config/`, `defaultconfigs/`, and `configureddefaults/`. Account/session data, credentials, backups, temporary/runtime caches, and credential-shaped configuration documents are skipped by path/content blacklists with visible reasons; precise configuration repairs still use key-level OTA.
- Added automatic configuration routing: gameplay-consistency files remain `additive`; client graphics, volume, keybinding, and UI files receive exact-file `first-install` scopes; files mixing gameplay and personal keys are withheld from full-file publication and explicitly routed to key-level configuration OTA.
- Fixed scans reading the previous value when a custom scope was typed and scanned before its table edit committed. FancyMenu layouts, assets, and themes under `config/fancymenu/` are recognized as publisher UI content, while last-world state, Buddy player progress, browser state, and temporary cache files under `fancymenu_data/` are excluded with visible reasons.
- Fixed Simplified-Chinese Windows installations being routed as English when a launcher supplied `-Duser.language=en`. Download routing now reads the operating-system user locale first and only falls back to JVM/environment locales.
- Verified CurseForge ForgeCDN file URLs are now normalized as official global endpoints even when their metadata was discovered through a Chinese mirror API. This preserves mirror-first behavior for Simplified Chinese while giving every other locale a valid official fallback.
- Clarified self-update behavior: a client can only install the MCSync hash currently published by the stable `mods-v5.json`; an unpublished local build cannot be discovered automatically, and a broken release manifest blocks the entire atomic transaction including self-update.

- Added a real publisher progress bar covering hashing and platform verification, hosted-file copying, manifest writing, and cloud-bundle assembly; expensive file validation no longer blocks the GUI thread.
- When a previous publication is selected, platform Mods now reuse verified download evidence by SHA-256, size, and pinned platform coordinates; unchanged files no longer repeat platform API lookup or full-Mod verification downloads.
- Successful platform verification is now persisted immediately as a small atomic evidence cache at `.modsync/publisher-platform-verifications-v1.json`; a later publication failure no longer causes already verified Mods to be downloaded again on retry.

- CurseForge publication now converts ForgeCDN paths returned by the MCIMirror API into mirror file URLs and verifies every candidate by size and SHA-256. Simplified-Chinese systems prefer the mirror with official fallback; other locales use official endpoints only.
- If CurseForge's dedicated `download-url` endpoint is temporarily unavailable, publication falls back to the full metadata for the same pinned `projectId/fileId`, then performs the normal full-file SHA-256 verification.

### Highlights

- Renamed MCModSync to MCSync while preserving the legacy upgrade entry points.
- Added the `mods-v5.json` manifest, release-sequence downgrade protection, and same-sequence fork rejection.
- Added early-start transactional synchronization with verified download, backup, commit, and rollback.
- Added in-game required/recommended selection for first launch and newly added optional content.
- Added the 2.0 publishing GUI for Mods, managed files, key-level configuration OTA, optional packs, server lists, validation, and export.
- Matches Modrinth by content hash. A CurseForge fingerprint only locates a candidate; the publisher must download it and verify exact size and SHA-256, otherwise that platform source is abandoned.
- Detects a unique modId replacement as an upgrade, inherits publisher-edited settings, and re-resolves the new JAR by content hash.
- Marks every JAR in a duplicate-modId set as a conflict and blocks export instead of choosing a version automatically.
- Restricts mod-platform matching to JAR files directly under `mods/`; other managed files use ordinary download sources.
- Supports direct hosting, Modrinth, CurseForge, and configurable mirrors with final size and SHA-256 verification.
- Includes upgrade material for supported 1.6.x, 1.7, and 1.9.x clients.
- Selects the complete previous publisher output directory and compares it with the current client directory by size and SHA-256. No earlier `mods-v5.json` is selected manually; unchanged files reuse immutable URLs and the current release directory contains only new or changed upgrade content.
- Generates a machine-readable incremental plan and content-equivalent Chinese and English upload/replacement guides.
- Resolves pinned Modrinth versions to hash-matching file URLs during publication, so clients with valid local files no longer query mod-platform metadata.
- Fixed MCSync progress being pushed outside the visible NeoForge early window in large modpacks. MCSync now prepends its progress meter and uses status labels that the early window's ASCII font can render reliably.
- Clearly separates download/cache/hash verification shown in the Minecraft window from the hidden atomic commit after exit, instead of reporting the hidden commit helper as an unavailable GUI window.
- Shows a non-skippable countdown in the Minecraft early window before the hidden commit starts, explicitly telling players to wait for the atomic commit before relaunching.
- Shows an estimated hidden-commit duration before Minecraft exits, based on the actual number and size of files to write, while warning that many small files or slower disks can take longer.
- Fixed schema-v5 releases restaging, backing up, and rewriting every file in the complete desired-state manifest whenever the release sequence changed. The complete manifest is still verified and recorded in the ownership ledger, but the transaction now commits only additions, changes, removals, and configuration mutations.
- The previous baseline can now be a complete publisher output, a standalone `manifest-v5.json`/`mods-v5.json`, or a ZIP upgrade package. An upgrade package only needs the complete desired-state index, not duplicate old payloads; publication is blocked when that index is absent.
- Fixed the legacy upgrade ordering: `legacy/1.9/mods-v4.txt` and `legacy/1.6/mods.txt` now always advertise a v4 address that old clients can parse; the downloaded configuration bootstrap switches the restarted 2.0 client to the final `mods-v5.json` only after the upgrade components are installed, preventing 1.9.x from terminating before downloading them.
- Hardened Windows cache commits: when antivirus, indexing, or another launch briefly holds a `.modsync/cache-v5` target, the completed download now retries with backoff; if another process has already written the same valid SHA-256 cache, it is reused instead of treating one temporary-file move failure as a sync failure.

## 1.9.6

### Highlights

- Final public MCModSync 1.9.x maintenance release.
- Fixed desktop synchronization dialogs for NeoForge/Linux helper processes.
- Used up to eight parallel download threads and retried only failed tasks.
- Preserved validated downloads and kept a local `mods-v4.txt` catalog copy.
- Continued editing earlier catalogs with a new timestamp-based catalog version.
- Included bilingual UI/logging and Fabric/NeoForge 1.21.1 metadata.

Older 1.9.x development history remains available in Git history.

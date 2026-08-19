# Changelog

[中文](CHANGELOG.zh-CN.md)

## 2.0.0

- CurseForge publication now converts ForgeCDN paths returned by the MCIMirror API into mirror file URLs and verifies every candidate by size and SHA-256. Simplified-Chinese systems prefer the mirror with official fallback; other locales use official endpoints only.

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

## 1.9.6

### Highlights

- Final public MCModSync 1.9.x maintenance release.
- Fixed desktop synchronization dialogs for NeoForge/Linux helper processes.
- Used up to eight parallel download threads and retried only failed tasks.
- Preserved validated downloads and kept a local `mods-v4.txt` catalog copy.
- Continued editing earlier catalogs with a new timestamp-based catalog version.
- Included bilingual UI/logging and Fabric/NeoForge 1.21.1 metadata.

Older 1.9.x development history remains available in Git history.

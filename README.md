# MCModSync

MCModSync is a client-side Fabric utility that synchronizes mods, resource packs, and server-list entries from MD5 manifests before the game launches.

## Privacy-first defaults

This public source release contains no production URLs, credentials, tokens, private filesystem paths, or personal author identifiers. All built-in manifest addresses point to `example.invalid` and cannot contact a real service.

To use it, copy `modsync.properties.example` to your Minecraft game directory as `modsync.properties`, then replace the example URLs with manifests hosted by you.

## Requirements

- Java 21 or newer
- Fabric Loader 0.16 or newer
- Minecraft 1.21.11 or newer

## Build and test (Windows)

```powershell
./build.ps1
```

The resulting jar is written to `build/dist/`.

## Configuration

1. Install the built JAR in the Minecraft instance's `mods` directory.
2. Copy `modsync.properties.example` to the instance root (the directory that contains `mods`) and rename it to `modsync.properties`.
3. Replace the `example.invalid` values with HTTPS URLs you operate. A single manifest is a UTF-8 text file whose non-comment rows are:

   ```text
   <MD5>\t<mod-id-or->\t<file-name>
   ```

   The manifest and every downloaded file must be reachable from each player's device. `PublisherMain` in the JAR can generate manifests from a directory when run from the command line.

`modsync.properties` supports:

- `manifest`: mods manifest URL
- `resourcePackManifest`: resource-pack manifest URL
- `serverListManifest`: server-list manifest URL
- `mobileManifest` and `mobileResourcePackManifest`: optional mobile-launcher overrides

The example configuration contains safe placeholders only. Keep your real configuration out of Git; `.gitignore` already excludes `modsync.properties`.

Set `syncResourcePacks=false` and/or `syncServerList=false` if your deployment does not use those features. The default `strict=true` and `requireManifest=true` make the client stop rather than launch with an unavailable or invalid manifest.

## License

Released under the MIT License. See [LICENSE](LICENSE).

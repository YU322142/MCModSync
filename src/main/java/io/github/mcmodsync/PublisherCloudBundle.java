package io.github.mcmodsync;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a content-addressed object store, historical manifests, and the stable channel pointer. */
final class PublisherCloudBundle {
    record LegacyResult(
            Path outputRoot,
            Path v4Manifest,
            Path v2Manifest,
            Path clientProperties,
            Path artifactManifest,
            Path sha256Sums,
            Path guideZh,
            Path guideEn) {
    }

    record Result(
            PublisherProjectV5.Publication publication,
            Path stableManifest,
            Path clientProperties,
            Path serverListManifest,
            Path uploadPlan,
            Path uploadGuideZh,
            Path uploadGuideEn) {
    }

    private PublisherCloudBundle() {
    }

    static LegacyResult publishLegacyGateways(
            Map<String, Object> project,
            Path outputRoot,
            Path updaterJar) throws IOException {
        Path output = outputRoot.toAbsolutePath().normalize();
        ensureEmptyTarget(output);
        if (updaterJar == null || !Files.isRegularFile(updaterJar)) {
            throw new IOException("生成旧版网关时缺少 MCSync 2.0.0 JAR");
        }
        Map<String, Object> remote = object(project.get("remote"), "remote");
        String base = normalizeBase(String.valueOf(remote.get("baseUrl")));
        String stableRelative = validatePath(
                String.valueOf(remote.getOrDefault("stablePath", "channel/stable/mods-v5.json")),
                "mods-v5.json");
        String v4Relative = validatePath(
                String.valueOf(remote.getOrDefault("legacyV4Path", "legacy/1.9/mods-v4.txt")),
                "mods-v4.txt");
        String v2Relative = validatePath(
                String.valueOf(remote.getOrDefault("legacyV2Path", "legacy/1.6/mods.txt")),
                "mods.txt");
        long sequence = number(project.get("releaseSequence"), "releaseSequence").longValueExact();
        Path parent = output.getParent();
        if (parent == null) throw new IOException("旧版升级输出目录必须有父目录: " + output);
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, "." + output.getFileName() + ".legacy-");
        boolean committed = false;
        try {
            Path properties = staging.resolve("client-modsync.properties");
            String serverListUrl = null;
            if (Boolean.TRUE.equals(remote.get("syncServerList"))) {
                String serverListRelative = validatePath(
                        String.valueOf(remote.getOrDefault(
                                "serverListManifestPath", "server-list/serverlist.txt")),
                        "serverlist.txt");
                serverListUrl = base + "/" + serverListRelative;
            }
            writeClientProperties(properties, base + "/" + stableRelative, serverListUrl);
            ManagedClientConfig finalConfig = ManagedClientConfig.fromPropertiesFile(properties);
            ManagedClientConfig legacyCatalogConfig = finalConfig.forLegacyGateway(base + "/" + v4Relative);
            buildLegacyDirectory(staging.resolve(parent(v4Relative)), updaterJar,
                    finalConfig, legacyCatalogConfig, true, sequence);
            buildLegacyDirectory(staging.resolve(parent(v2Relative)), updaterJar,
                    finalConfig, legacyCatalogConfig, false, sequence);
            Path guideZh = staging.resolve("LEGACY-UPGRADE.zh-CN.md");
            Path guideEn = staging.resolve("LEGACY-UPGRADE.en.md");
            Files.writeString(guideZh,
                    "# MCSync 旧版永久升级入口\n\n"
                            + "- 1.9.x 入口：`" + base + "/" + v4Relative + "`\n"
                            + "- 1.6.x/1.7.x 入口：`" + base + "/" + v2Relative + "`\n"
                            + "- 2.0 稳定入口：`" + base + "/" + stableRelative + "`\n\n"
                            + "旧客户端先从对应 legacy 入口取得 MCSync 与配置引导组件。"
                            + "游戏退出并完成替换、再次启动后，配置引导会把客户端切换到 2.0 稳定 v5 入口。\n\n"
                            + "上传时保持相对路径不变。以后重新发布 2.0 内容时不必改动永久 legacy URL；"
                            + "只有需要更换升级器本身时，才重新生成并上传本目录。\n",
                    StandardCharsets.UTF_8);
            Files.writeString(guideEn,
                    "# MCSync permanent legacy upgrade gateways\n\n"
                            + "- 1.9.x entry: `" + base + "/" + v4Relative + "`\n"
                            + "- 1.6.x/1.7.x entry: `" + base + "/" + v2Relative + "`\n"
                            + "- 2.0 stable entry: `" + base + "/" + stableRelative + "`\n\n"
                            + "A legacy client first downloads MCSync and the configuration bootstrap from its "
                            + "legacy gateway. After the game exits, replacement completes, and the client starts "
                            + "again, the bootstrap switches it to the stable schema-v5 entry.\n\n"
                            + "Preserve all relative paths while uploading. Normal 2.0 republishing does not require "
                            + "changing these permanent legacy URLs; regenerate this directory only when the updater "
                            + "itself changes.\n",
                    StandardCharsets.UTF_8);

            List<Path> artifacts;
            try (var paths = Files.walk(staging)) {
                artifacts = paths.filter(Files::isRegularFile)
                        .filter(path -> !path.equals(staging.resolve("legacy-artifacts.json")))
                        .filter(path -> !path.equals(staging.resolve("SHA256SUMS.txt")))
                        .sorted(Comparator.comparing(path -> staging.relativize(path).toString()))
                        .toList();
            }
            List<Object> artifactRows = new ArrayList<>();
            for (Path path : artifacts) {
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                row.put("path", staging.relativize(path).toString().replace('\\', '/'));
                row.put("sha256", uncheckedSha256(path));
                row.put("size", BigDecimal.valueOf(uncheckedSize(path)));
                artifactRows.add(row);
            }
            Path artifactManifest = staging.resolve("legacy-artifacts.json");
            Files.writeString(artifactManifest, StrictJson.stringify(Map.of(
                    "schema", BigDecimal.ONE,
                    "releaseSequence", BigDecimal.valueOf(sequence),
                    "stableUrl", base + "/" + stableRelative,
                    "legacyV4Url", base + "/" + v4Relative,
                    "legacyV2Url", base + "/" + v2Relative,
                    "artifacts", artifactRows)) + "\n", StandardCharsets.UTF_8);
            ArrayList<Path> hashedArtifacts = new ArrayList<>(artifacts);
            hashedArtifacts.add(artifactManifest);
            hashedArtifacts.sort(Comparator.comparing(path -> staging.relativize(path).toString()));
            StringBuilder sums = new StringBuilder();
            for (Path path : hashedArtifacts) {
                sums.append(Hashing.sha256(path)).append("  ")
                        .append(staging.relativize(path).toString().replace('\\', '/')).append('\n');
            }
            Path sha256Sums = staging.resolve("SHA256SUMS.txt");
            Files.writeString(sha256Sums, sums, StandardCharsets.UTF_8);
            commitStaging(staging, output);
            committed = true;
            return new LegacyResult(
                    output,
                    output.resolve(v4Relative),
                    output.resolve(v2Relative),
                    output.resolve("client-modsync.properties"),
                    output.resolve("legacy-artifacts.json"),
                    output.resolve("SHA256SUMS.txt"),
                    output.resolve("LEGACY-UPGRADE.zh-CN.md"),
                    output.resolve("LEGACY-UPGRADE.en.md"));
        } finally {
            if (!committed) deleteStaging(staging);
        }
    }

    static Result publish(
            Path gameRoot,
            Map<String, Object> project,
            Path outputRoot,
            String baseUrl,
            String stablePath,
            String legacyV4Path,
            String legacyV2Path,
            Path serverListSource,
            String serverListManifestPath,
            boolean legacyGateways,
            Path updaterJar) throws IOException {
        return publish(gameRoot, project, outputRoot, baseUrl, stablePath, legacyV4Path, legacyV2Path,
                serverListSource, serverListManifestPath, legacyGateways, updaterJar, null);
    }

    static Result publish(
            Path gameRoot,
            Map<String, Object> project,
            Path outputRoot,
            String baseUrl,
            String stablePath,
            String legacyV4Path,
            String legacyV2Path,
            Path serverListSource,
            String serverListManifestPath,
            boolean legacyGateways,
            Path updaterJar,
            ReleaseManifestV5 previousManifest) throws IOException {
        return publish(gameRoot, project, outputRoot, baseUrl, stablePath, legacyV4Path, legacyV2Path,
                serverListSource, serverListManifestPath, legacyGateways, updaterJar, previousManifest,
                PublisherProgress.NONE);
    }

    static Result publish(
            Path gameRoot,
            Map<String, Object> project,
            Path outputRoot,
            String baseUrl,
            String stablePath,
            String legacyV4Path,
            String legacyV2Path,
            Path serverListSource,
            String serverListManifestPath,
            boolean legacyGateways,
            Path updaterJar,
            ReleaseManifestV5 previousManifest,
            PublisherProgress progress) throws IOException {
        return publishInternal(gameRoot, project, outputRoot, baseUrl, stablePath, legacyV4Path, legacyV2Path,
                serverListSource, serverListManifestPath, legacyGateways, updaterJar, previousManifest,
                progress, true);
    }

    static Result publishFull(
            Path gameRoot,
            Map<String, Object> project,
            Path outputRoot,
            String baseUrl,
            String stablePath,
            String legacyV4Path,
            String legacyV2Path,
            Path serverListSource,
            String serverListManifestPath,
            ReleaseManifestV5 verificationEvidence,
            PublisherProgress progress) throws IOException {
        return publishInternal(gameRoot, project, outputRoot, baseUrl, stablePath, legacyV4Path, legacyV2Path,
                serverListSource, serverListManifestPath, false, null, verificationEvidence,
                progress, false);
    }

    private static Result publishInternal(
            Path gameRoot,
            Map<String, Object> project,
            Path outputRoot,
            String baseUrl,
            String stablePath,
            String legacyV4Path,
            String legacyV2Path,
            Path serverListSource,
            String serverListManifestPath,
            boolean legacyGateways,
            Path updaterJar,
            ReleaseManifestV5 previousManifest,
            PublisherProgress progress,
            boolean reuseHostedObjects) throws IOException {
        PublisherProgress reporter = progress == null ? PublisherProgress.NONE : progress;
        reporter.update(PublisherProgress.Stage.PREPARE, 0, 1, "检查云端布局与发布参数");
        Path output = outputRoot.toAbsolutePath().normalize();
        ensureEmptyTarget(output);
        String base = normalizeBase(baseUrl);
        String stableRelative = validatePath(stablePath, "mods-v5.json");
        String v4Relative = validatePath(legacyV4Path, "mods-v4.txt");
        String v2Relative = validatePath(legacyV2Path, "mods.txt");
        boolean syncServerList = serverListSource != null;
        String serverListRelative = syncServerList
                ? validatePath(serverListManifestPath, "serverlist.txt") : "";
        if (syncServerList) ServerListManifest.fromFile(serverListSource);
        if (legacyGateways) {
            if (updaterJar == null || !Files.isRegularFile(updaterJar)) {
                throw new IOException("生成旧版网关时缺少 MCSync 2.0.0 JAR");
            }
        }

        long sequence = number(project.get("releaseSequence"), "releaseSequence").longValueExact();
        if (previousManifest != null && previousManifest.releaseSequence() >= sequence) {
            throw new IOException("上一版 releaseSequence 必须小于当前发布序号");
        }

        Path parent = output.getParent();
        if (parent == null) throw new IOException("发布输出目录必须有父目录: " + output);
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, "." + output.getFileName() + ".mcsync-publish-");
        boolean committed = false;
        try {
            Result staged = publishInto(gameRoot, project, staging, base, stableRelative, v4Relative, v2Relative,
                    serverListSource, serverListRelative, syncServerList, legacyGateways, updaterJar,
                    previousManifest, reporter, sequence, reuseHostedObjects);
            commitStaging(staging, output);
            committed = true;
            reporter.update(PublisherProgress.Stage.COMPLETE, 1, 1, "发布完成");
            return relocate(staged, staging, output);
        } finally {
            if (!committed) deleteStaging(staging);
        }
    }

    private static Result publishInto(
            Path gameRoot,
            Map<String, Object> project,
            Path output,
            String base,
            String stableRelative,
            String v4Relative,
            String v2Relative,
            Path serverListSource,
            String serverListRelative,
            boolean syncServerList,
            boolean legacyGateways,
            Path updaterJar,
            ReleaseManifestV5 previousManifest,
            PublisherProgress reporter,
            long sequence,
            boolean reuseHostedObjects) throws IOException {
        Path assemblyRoot = output.resolve(".publication-stage");
        PublisherProjectV5.Publication assembled = PublisherProjectV5.publish(
                gameRoot, project, assemblyRoot, String.valueOf(project.get("releaseId")) + ".publisher.json",
                previousManifest, reporter,
                (path, sha256, size, download) -> withObjectEndpoint(download, base, sha256),
                reuseHostedObjects);
        materializeObjects(output, assemblyRoot, assembled);

        String releaseName = safeReleaseName(assembled.manifest().releaseId());
        Path historicalManifest = output.resolve("manifests").resolve(releaseName + ".json");
        Path historicalReport = output.resolve("reports").resolve(releaseName + ".publication.json");
        Files.createDirectories(historicalManifest.getParent());
        Files.createDirectories(historicalReport.getParent());
        Files.copy(assembled.manifestPath(), historicalManifest, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(assembled.reportPath(), historicalReport, StandardCopyOption.REPLACE_EXISTING);
        PublisherProjectV5.Publication publication = new PublisherProjectV5.Publication(
                assembled.manifest(), historicalManifest, historicalReport,
                assembled.hostedFiles(), assembled.reusedHostedFiles(),
                assembled.reusedPlatformVerifications(), assembled.reusedHostedPaths());
        deleteStaging(assemblyRoot);

        reporter.update(PublisherProgress.Stage.BUILD_CLOUD_BUNDLE, 0, 5, "复制 2.0 稳定入口");
        Path stable = output.resolve(stableRelative.replace('/', java.io.File.separatorChar));
        Files.createDirectories(stable.getParent());
        Files.copy(publication.manifestPath(), stable, StandardCopyOption.REPLACE_EXISTING);
        String stableUrl = base + "/" + stableRelative;
        Path properties = output.resolve("client-modsync.properties");
        Path serverListManifest = null;
        String serverListUrl = null;
        if (syncServerList) {
            serverListManifest = output.resolve(serverListRelative.replace('/', java.io.File.separatorChar));
            Files.createDirectories(serverListManifest.getParent());
            Files.copy(serverListSource, serverListManifest.getParent().resolve(ServerListManifest.FILE_NAME),
                    StandardCopyOption.REPLACE_EXISTING);
            ServerListManifest.fromFile(serverListSource).write(serverListManifest);
            serverListUrl = base + "/" + serverListRelative;
        }
        writeClientProperties(properties, stableUrl, serverListUrl);
        reporter.update(PublisherProgress.Stage.BUILD_CLOUD_BUNDLE, 2, 5, "生成客户端引导配置");
        if (legacyGateways) {
            ManagedClientConfig finalConfig = ManagedClientConfig.fromPropertiesFile(properties);
            ManagedClientConfig legacyCatalogConfig = finalConfig.forLegacyGateway(base + "/" + v4Relative);
            buildLegacyDirectory(output.resolve(parent(v4Relative)), updaterJar,
                    finalConfig, legacyCatalogConfig, true, sequence);
            buildLegacyDirectory(output.resolve(parent(v2Relative)), updaterJar,
                    finalConfig, legacyCatalogConfig, false, sequence);
        }
        reporter.update(PublisherProgress.Stage.BUILD_CLOUD_BUNDLE, 3, 5, "生成旧版升级材料");
        writeGuide(output.resolve("REMOTE-DEPLOYMENT.md"), sequence, stableRelative, stableUrl,
                serverListRelative, legacyGateways);
        reporter.update(PublisherProgress.Stage.BUILD_CLOUD_BUNDLE, 4, 5, "生成增量上传计划");
        ReleaseManifestV5 deltaBaseline = reuseHostedObjects ? previousManifest : null;
        PublisherReleaseDelta.Plan delta = PublisherReleaseDelta.plan(
                deltaBaseline, publication.manifest(), publication.reusedHostedPaths());
        PublisherReleaseDelta.Paths deltaPaths = PublisherReleaseDelta.write(
                output, delta, deltaBaseline, publication.manifest(), stableRelative, serverListRelative);
        reporter.update(PublisherProgress.Stage.BUILD_CLOUD_BUNDLE, 5, 5, "云端发布目录整理完成");
        return new Result(publication, stable, properties, serverListManifest,
                deltaPaths.json(), deltaPaths.zh(), deltaPaths.en());
    }

    private static Result relocate(Result staged, Path staging, Path output) {
        PublisherProjectV5.Publication publication = staged.publication();
        PublisherProjectV5.Publication relocatedPublication = new PublisherProjectV5.Publication(
                publication.manifest(),
                relocate(publication.manifestPath(), staging, output),
                relocate(publication.reportPath(), staging, output),
                publication.hostedFiles(),
                publication.reusedHostedFiles(),
                publication.reusedPlatformVerifications(),
                publication.reusedHostedPaths());
        return new Result(
                relocatedPublication,
                relocate(staged.stableManifest(), staging, output),
                relocate(staged.clientProperties(), staging, output),
                relocate(staged.serverListManifest(), staging, output),
                relocate(staged.uploadPlan(), staging, output),
                relocate(staged.uploadGuideZh(), staging, output),
                relocate(staged.uploadGuideEn(), staging, output));
    }

    private static Path relocate(Path path, Path staging, Path output) {
        return path == null ? null : output.resolve(staging.relativize(path));
    }

    private static void commitStaging(Path staging, Path output) throws IOException {
        ensureEmptyTarget(output);
        if (Files.exists(output)) Files.delete(output);
        try {
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(staging, output);
        }
    }

    private static void deleteStaging(Path staging) throws IOException {
        if (!Files.exists(staging)) return;
        try (var paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Map<String, Object> withObjectEndpoint(
            Map<String, Object> original, String baseUrl, String sha256) {
        LinkedHashMap<String, Object> download = new LinkedHashMap<>(original);
        String normalizedHash = sha256.toLowerCase(Locale.ROOT);
        String objectUrl = normalizeBase(baseUrl) + "/" + objectRelative(normalizedHash);
        download.put("endpoints", List.of(Map.of(
                "url", objectUrl,
                "role", "official", "purpose", "file", "region", "global",
                "priority", 100, "thirdParty", false)));
        return download;
    }

    private static void materializeObjects(
            Path output, Path assemblyRoot, PublisherProjectV5.Publication publication) throws IOException {
        for (ReleaseManifestV5.FileEntry file : publication.manifest().files()) {
            if (!"publisher-hosted".equals(file.download().type())
                    || publication.reusedHostedPaths().contains(file.path())) continue;
            Path source = assemblyRoot.resolve(file.path()).normalize();
            if (!source.startsWith(assemblyRoot) || !Files.isRegularFile(source)) {
                throw new IOException("发布暂存文件缺失: " + file.path());
            }
            Path object = output.resolve(objectRelative(file.sha256())).normalize();
            if (!object.startsWith(output)) throw new IOException("对象路径逃逸: " + file.path());
            Files.createDirectories(object.getParent());
            if (Files.exists(object)) {
                if (!Hashing.sha256(object).equalsIgnoreCase(file.sha256())) {
                    throw new IOException("对象仓库发生哈希冲突: " + object);
                }
                Files.delete(source);
            } else {
                Files.move(source, object);
            }
            if (!Hashing.sha256(object).equalsIgnoreCase(file.sha256())) {
                throw new IOException("对象写入后哈希不一致: " + file.path());
            }
        }
    }

    private static String objectRelative(String sha256) {
        String hash = sha256.toLowerCase(Locale.ROOT);
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("无效 SHA-256: " + sha256);
        return "objects/sha256/" + hash.substring(0, 2) + "/" + hash;
    }

    private static String safeReleaseName(String releaseId) {
        String safe = releaseId == null ? "" : releaseId.strip().replaceAll("[^A-Za-z0-9._-]", "-");
        while (safe.startsWith(".")) safe = safe.substring(1);
        return safe.isBlank() ? "release" : safe;
    }

    private static void buildLegacyDirectory(
            Path directory,
            Path updater,
            ManagedClientConfig finalConfig,
            ManagedClientConfig legacyCatalogConfig,
            boolean v4,
            long releaseSequence) throws IOException {
        Files.createDirectories(directory);
        Files.copy(updater, directory.resolve("MCSync-2.0.0.jar"), StandardCopyOption.REPLACE_EXISTING);
        ManagedClientConfig.writeBootstrapJar(directory, finalConfig);
        ModManifest catalog = ModManifest.scan(directory).withManagedClientConfig(legacyCatalogConfig)
                .withCatalogVersion(Long.toString(releaseSequence));
        if (v4) catalog.write(directory.resolve("mods-v4.txt"));
        else LegacyUpgradeManifest.write(catalog, directory.resolve("mods.txt"));
    }

    private static void writeClientProperties(Path output, String manifestUrl, String serverListManifestUrl)
            throws IOException {
        Files.writeString(output,
                "# MCSync 2.0 server-managed bootstrap\n"
                        + "manifest=" + manifestUrl + "\n"
                        + "syncResourcePacks=false\n"
                        + "syncServerList=" + (serverListManifestUrl != null) + "\n"
                        + (serverListManifestUrl == null ? ""
                                : "serverListManifest=" + serverListManifestUrl + "\n")
                        + "strict=true\n"
                        + "requireManifest=true\n",
                StandardCharsets.UTF_8);
    }

    private static void writeGuide(
            Path output,
            long sequence,
            String stablePath,
            String stableUrl,
            String serverListPath,
            boolean legacy) throws IOException {
        Files.writeString(output,
                "# MCSync cloud deployment\n\n"
                        + "Release sequence: " + sequence + "\n\n"
                        + "1. Upload new files under `objects/sha256/` first. Existing hash objects are never overwritten.\n"
                        + (legacy
                                ? "2. Copy the generated files under `legacy/` to the separately managed legacy download locations.\n"
                                : "2. Legacy gateways were not generated.\n")
                        + "3. Upload the complete historical manifest under `manifests/`.\n"
                        + "4. Atomically replace `" + stablePath + "` last.\n"
                        + "5. Configure clients with `manifest=" + stableUrl + "`.\n\n"
                        + (serverListPath.isBlank() ? ""
                                : "6. Upload `" + serverListPath + "` and its sibling `servers.dat`.\n\n")
                        + "Payload URLs are derived from SHA-256, not release timestamps. A full publication uploads every referenced local object; later OTA publications upload only hashes not already present.\n"
                        + "Do not overwrite hash objects. Rollback still uses a new, larger releaseSequence.\n",
                StandardCharsets.UTF_8);
    }

    private static void ensureEmptyTarget(Path output) throws IOException {
        if (!Files.exists(output)) return;
        if (!Files.isDirectory(output)) throw new IOException("发布输出不是目录: " + output);
        try (var entries = Files.list(output)) {
            if (entries.findAny().isPresent()) throw new IOException("发布输出目录必须为空: " + output);
        }
    }

    private static String normalizeBase(String value) {
        String base = value == null ? "" : value.strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URI uri = URI.create(base);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getFragment() != null) {
            throw new IllegalArgumentException("公开根地址必须是无片段的 HTTPS 绝对地址");
        }
        return base;
    }

    private static String validatePath(String value, String name) {
        String path = value == null ? "" : value.strip().replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isBlank() || path.contains("..") || path.contains(":") || !path.endsWith("/" + name)) {
            throw new IllegalArgumentException("云端路径必须安全且以 /" + name + " 结尾: " + value);
        }
        return path;
    }

    private static String parent(String path) throws IOException {
        int separator = path.lastIndexOf('/');
        if (separator < 1) throw new IOException("旧版入口必须放在独立目录: " + path);
        return path.substring(0, separator);
    }

    private static BigDecimal number(Object value, String name) {
        if (!(value instanceof BigDecimal number)) throw new IllegalArgumentException(name + " 必须是整数");
        return number;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(name + " 必须是对象");
        return (Map<String, Object>) map;
    }

    private static String uncheckedSha256(Path path) {
        try {
            return Hashing.sha256(path);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static long uncheckedSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}

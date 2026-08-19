package io.github.mcmodsync;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Materializes a reviewed publisher project into a deterministic schema-v5 release directory. */
final class PublisherProjectV5 {
    private static final DateTimeFormatter RELEASE_SEQUENCE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final Set<String> ROOT_KEYS = Set.of(
            "schema", "releaseId", "releaseSequence", "minimumMCSyncVersion",
            "managedScopes", "files", "configOperations", "remote");
    private static final Set<String> FILE_KEYS = Set.of(
            "path", "kind", "required", "restartRequired", "side", "download",
            "modId", "displayName", "version", "descriptionZh", "descriptionEn",
            "incompatiblePlatforms");

    private PublisherProjectV5() {
    }

    static long currentTimeReleaseSequence() {
        return Long.parseLong(LocalDateTime.now().format(RELEASE_SEQUENCE_TIME));
    }

    static long nextReleaseSequence(long previous) {
        long now = currentTimeReleaseSequence();
        if (previous == Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(now, previous + 1L);
    }

    record Publication(
            ReleaseManifestV5 manifest,
            Path manifestPath,
            Path reportPath,
            int hostedFiles,
            int reusedHostedFiles,
            Set<String> reusedHostedPaths) {
        Publication {
            reusedHostedPaths = Set.copyOf(reusedHostedPaths);
        }
    }

    /** Performs the same strict project/file/config checks as publication without network or output writes. */
    static ReleaseManifestV5 validateProject(Path gameRoot, Map<String, Object> source) throws IOException {
        Path root = gameRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("发布源游戏目录不存在: " + root);
        requireKeys(source.keySet(), ROOT_KEYS, "project root");
        if (!new BigDecimal("1").equals(source.get("schema"))) throw new IOException("发布项目 schema 必须为 1");
        LinkedHashMap<String, Object> manifestJson = new LinkedHashMap<>();
        manifestJson.put("schema", ReleaseManifestV5.SCHEMA);
        manifestJson.put("releaseId", source.get("releaseId"));
        manifestJson.put("releaseSequence", source.get("releaseSequence"));
        manifestJson.put("minimumMCSyncVersion", source.getOrDefault("minimumMCSyncVersion", BuildInfo.VERSION));
        manifestJson.put("managedScopes", source.getOrDefault("managedScopes", List.of()));
        ManagedPathPolicy pathPolicy = new ManagedPathPolicy(root, List.of());
        ArrayList<Object> generatedFiles = new ArrayList<>();
        for (Object raw : array(source.get("files"), "files")) {
            Map<String, Object> file = object(raw, "files[]");
            requireKeys(file.keySet(), FILE_KEYS, "files[]");
            if (!(file.get("path") instanceof String relative)) throw new IOException("files[].path 必须是字符串");
            Path local = pathPolicy.resolve(relative, false);
            if (!Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS)) throw new IOException("发布项目中的文件不存在: " + relative);
            byte[] bytes = Files.readAllBytes(local);
            if ((relative.startsWith("config/") || relative.startsWith("defaultconfigs/")
                    || relative.startsWith("kubejs/config/"))
                    && SensitiveDataPolicy.looksLikeCredentialDocument(bytes)) {
                throw new IOException("检测到可能含凭据的配置文件，请改用键级 OTA: " + relative);
            }
            LinkedHashMap<String, Object> generated = new LinkedHashMap<>(file);
            generated.put("sha256", Hashing.sha256(bytes));
            generated.put("size", bytes.length);
            generatedFiles.add(generated);
        }
        manifestJson.put("files", generatedFiles);
        manifestJson.put("configOperations", source.getOrDefault("configOperations", List.of()));
        try {
            return ReleaseManifestV5.parse((StrictJson.stringify(manifestJson) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw new IOException("发布项目无法生成有效 v5 清单: " + failure.getMessage(), failure);
        }
    }

    static Publication publish(Path gameRoot, Path projectFile, Path outputDirectory) throws IOException {
        Path root = gameRoot.toAbsolutePath().normalize();
        Path project = projectFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(project)) throw new IOException("v5 发布项目不存在: " + project);
        Map<String, Object> source = object(
                StrictJson.parse(Files.readString(project, StandardCharsets.UTF_8)), "root");
        return publish(gameRoot, source, outputDirectory, project.getFileName().toString());
    }

    static Publication publish(
            Path gameRoot,
            Map<String, Object> source,
            Path outputDirectory,
            String projectName) throws IOException {
        return publish(gameRoot, source, outputDirectory, projectName, null);
    }

    static Publication publish(
            Path gameRoot,
            Map<String, Object> source,
            Path outputDirectory,
            String projectName,
            ReleaseManifestV5 previousManifest) throws IOException {
        return publish(gameRoot, source, outputDirectory, projectName, previousManifest, PublisherProgress.NONE);
    }

    static Publication publish(
            Path gameRoot,
            Map<String, Object> source,
            Path outputDirectory,
            String projectName,
            ReleaseManifestV5 previousManifest,
            PublisherProgress progress) throws IOException {
        PublisherProgress reporter = progress == null ? PublisherProgress.NONE : progress;
        reporter.update(PublisherProgress.Stage.PREPARE, 0, 1, "检查发布目录与项目结构");
        Path root = gameRoot.toAbsolutePath().normalize();
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("发布源游戏目录不存在: " + root);
        if (Files.exists(output) && (!Files.isDirectory(output) || hasEntries(output))) {
            throw new IOException("发布输出目录必须不存在或为空，避免混入旧版本文件: " + output);
        }
        Files.createDirectories(output);

        requireKeys(source.keySet(), ROOT_KEYS, "project root");
        if (!new BigDecimal("1").equals(source.get("schema"))) {
            throw new IOException("发布项目 schema 必须为 1");
        }
        LinkedHashMap<String, Object> manifestJson = new LinkedHashMap<>();
        manifestJson.put("schema", ReleaseManifestV5.SCHEMA);
        manifestJson.put("releaseId", source.get("releaseId"));
        manifestJson.put("releaseSequence", source.get("releaseSequence"));
        manifestJson.put("minimumMCSyncVersion", source.getOrDefault("minimumMCSyncVersion", BuildInfo.VERSION));
        manifestJson.put("managedScopes", source.getOrDefault("managedScopes", List.of()));

        ManagedPathPolicy pathPolicy = new ManagedPathPolicy(root, List.of());
        List<Object> files = array(source.get("files"), "files");
        ArrayList<Object> generatedFiles = new ArrayList<>();
        LinkedHashMap<String, Path> localFiles = new LinkedHashMap<>();
        LinkedHashSet<String> reusedHostedPaths = new LinkedHashSet<>();
        PublisherPlatformResolver platformResolver = new PublisherPlatformResolver();
        int inspected = 0;
        reporter.update(PublisherProgress.Stage.HASH_AND_PLATFORM, 0, files.size(), "准备计算文件哈希");
        for (Object raw : files) {
            Map<String, Object> file = object(raw, "files[]");
            requireKeys(file.keySet(), FILE_KEYS, "files[]");
            Object relativeRaw = file.get("path");
            if (!(relativeRaw instanceof String relative)) throw new IOException("files[].path 必须是字符串");
            Path local = pathPolicy.resolve(relative, false);
            if (!Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("发布项目中的文件不存在或不是普通文件: " + relative);
            }
            byte[] localBytes = Files.readAllBytes(local);
            if ((relative.startsWith("config/") || relative.startsWith("defaultconfigs/")
                    || relative.startsWith("kubejs/config/"))
                    && SensitiveDataPolicy.looksLikeCredentialDocument(localBytes)) {
                throw new IOException("检测到可能含凭据的配置文件，禁止整文件进入发布目录；请改用非敏感键级 OTA: " + relative);
            }
            LinkedHashMap<String, Object> generated = new LinkedHashMap<>(file);
            String sha256 = Hashing.sha256(localBytes);
            String sha512 = Hashing.sha512(localBytes);
            if (generated.get("download") instanceof Map<?, ?> download) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typedDownload = (Map<String, Object>) download;
                try {
                    generated.put("download", platformResolver.resolve(
                            typedDownload, sha256, sha512, localBytes.length));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("解析平台固定下载地址时被中断: " + relative, interrupted);
                } catch (IOException failure) {
                    if ("redistributable".equals(typedDownload.get("distributionPolicy"))) {
                        generated.put("download", Map.of(
                                "type", "publisher-hosted",
                                "distributionPolicy", "redistributable"));
                    } else {
                        throw new IOException("无法解析平台来源: " + relative + "；" + failure.getMessage(), failure);
                    }
                }
            }
            generated.put("sha256", sha256);
            generated.put("size", localBytes.length);
            if (generated.get("download") instanceof Map<?, ?> rawDownload
                    && "publisher-hosted".equals(rawDownload.get("type"))) {
                ReleaseManifestV5.FileEntry reusable = PublisherReleaseDelta.reusable(
                        previousManifest, relative, sha256, localBytes.length);
                if (reusable != null) {
                    generated.put("download", ReleaseManifestV5.downloadJson(reusable.download()));
                    reusedHostedPaths.add(relative);
                }
            }
            generatedFiles.add(generated);
            localFiles.put(relative, local);
            inspected++;
            reporter.update(PublisherProgress.Stage.HASH_AND_PLATFORM, inspected, files.size(), relative);
        }
        manifestJson.put("files", generatedFiles);
        manifestJson.put("configOperations", source.getOrDefault("configOperations", List.of()));
        ReleaseManifestV5 manifest;
        try {
            manifest = ReleaseManifestV5.parse(
                    (StrictJson.stringify(manifestJson) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw new IOException("发布项目无法生成有效 v5 清单: " + failure.getMessage(), failure);
        }

        int hosted = 0;
        int hostedTotal = (int) manifest.files().stream()
                .filter(file -> file.download().type().equals("publisher-hosted"))
                .filter(file -> !reusedHostedPaths.contains(file.path()))
                .count();
        reporter.update(PublisherProgress.Stage.COPY_HOSTED, 0, hostedTotal, "准备复制本地托管文件");
        for (ReleaseManifestV5.FileEntry file : manifest.files()) {
            if (!file.download().type().equals("publisher-hosted")) continue;
            if (reusedHostedPaths.contains(file.path())) continue;
            Path destination = output.resolve(file.path()).normalize();
            if (!destination.startsWith(output)) throw new IOException("发布目标路径逃逸: " + file.path());
            Files.createDirectories(destination.getParent());
            Files.copy(localFiles.get(file.path()), destination, StandardCopyOption.COPY_ATTRIBUTES);
            if (!Hashing.sha256(destination).equals(file.sha256())) {
                throw new IOException("发布复制后哈希不一致: " + file.path());
            }
            hosted++;
            reporter.update(PublisherProgress.Stage.COPY_HOSTED, hosted, hostedTotal, file.path());
        }
        reporter.update(PublisherProgress.Stage.WRITE_MANIFEST, 0, 1, "写入 manifest-v5.json 与发布报告");
        Path manifestPath = output.resolve("manifest-v5.json");
        Files.write(manifestPath, manifest.serialize());
        LinkedHashMap<String, Object> report = new LinkedHashMap<>();
        report.put("schema", 1);
        report.put("status", "PASS");
        report.put("generatedAt", Instant.now().toString());
        report.put("sourceRoot", "<local-game-root>");
        report.put("project", projectName == null || projectName.isBlank()
                ? "<gui-project>" : projectName);
        report.put("releaseId", manifest.releaseId());
        report.put("releaseSequence", manifest.releaseSequence());
        report.put("manifestSha256", Hashing.sha256(manifestPath));
        report.put("fileCount", manifest.files().size());
        report.put("publisherHostedFileCount", hosted);
        report.put("reusedPublisherHostedFileCount", reusedHostedPaths.size());
        report.put("reusedPublisherHostedPaths", reusedHostedPaths.stream().sorted().toList());
        report.put("downloadSourceTypes", manifest.files().stream()
                .map(file -> file.download().type()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        Path reportPath = output.resolve("publication-report.json");
        Files.writeString(reportPath, StrictJson.stringify(report) + "\n", StandardCharsets.UTF_8);
        reporter.update(PublisherProgress.Stage.WRITE_MANIFEST, 1, 1, "manifest-v5.json");
        return new Publication(manifest, manifestPath, reportPath, hosted,
                reusedHostedPaths.size(), reusedHostedPaths);
    }

    static void writeTemplate(Path output) throws IOException {
        String template = """
                {
                  "schema": 1,
                  "releaseId": "motiquies-2.0.0-ota.1",
                  "releaseSequence": %d,
                  "minimumMCSyncVersion": "2.0.0",
                  "remote": {
                    "baseUrl":"https://files.example.com/mcsync",
                    "stablePath":"channel/stable/mods-v5.json",
                    "legacyV4Path":"legacy/1.9/mods-v4.txt",
                    "legacyV2Path":"legacy/1.6/mods.txt",
                    "syncServerList":false,
                    "serverListSource":"",
                    "serverListManifestPath":"server-list/serverlist.txt",
                    "gameRoot":"",
                    "outputDirectory":"",
                    "previousOutputDirectory":"",
                    "generateLegacyGateways":true
                  },
                  "managedScopes": [
                    {"path":"mods","policy":"managed"},
                    {"path":"resourcepacks","policy":"managed"},
                    {"path":"shaderpacks","policy":"managed"},
                    {"path":"kubejs","policy":"managed"},
                    {"path":"tacz","policy":"managed"},
                    {"path":"tlm_custom_pack","policy":"managed"},
                    {"path":"config","policy":"additive"},
                    {"path":"defaultconfigs","policy":"additive"},
                    {"path":"configureddefaults","policy":"first-install"},
                    {"path":"options.txt","policy":"first-install"}
                  ],
                  "files": [
                    {
                      "path":"mods/our-adapted-mod.jar",
                      "kind":"mod","required":true,"restartRequired":true,"side":["client"],
                      "download":{"type":"publisher-hosted","distributionPolicy":"redistributable"}
                    },
                    {
                      "path":"mods/upstream-mod.jar",
                      "kind":"mod","required":true,"restartRequired":true,"side":["client"],
                      "download":{
                        "type":"modrinth","projectId":"PROJECT_ID","versionId":"VERSION_ID",
                        "distributionPolicy":"upstream-only",
                        "endpoints":[
                          {"url":"https://mod.mcimirror.top/modrinth/v2/","role":"mirror","purpose":"api","region":"cn","priority":10,"thirdParty":true},
                          {"url":"https://api.modrinth.com/v2/","role":"official","purpose":"api","region":"global","priority":100}
                        ]
                      }
                    }
                  ],
                  "configOperations": []
                }
                """.formatted(currentTimeReleaseSequence());
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.writeString(output, template, StandardCharsets.UTF_8);
    }

    private static boolean hasEntries(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isPresent();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String where) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException(where + " 必须是 JSON 对象");
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String where) throws IOException {
        if (!(value instanceof List<?> list)) throw new IOException(where + " 必须是 JSON 数组");
        return (List<Object>) list;
    }

    private static void requireKeys(Set<String> actual, Set<String> allowed, String where) throws IOException {
        Set<String> unknown = new LinkedHashSet<>(actual);
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) throw new IOException(where + " 包含未知字段: " + unknown);
    }
}

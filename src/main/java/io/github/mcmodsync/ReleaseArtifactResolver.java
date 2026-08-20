package io.github.mcmodsync;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** Resolves pinned v5 sources into hash-checked bytes; mirrors are transport candidates only. */
final class ReleaseArtifactResolver implements ReleaseTransactionEngine.ArtifactProvider {
    private static final Semaphore PLATFORM_METADATA_LIMIT = new Semaphore(8, true);
    private static final Object[] CACHE_LOCKS = createCacheLocks();
    private static final int CACHE_COMMIT_ATTEMPTS = 8;
    private static final long CACHE_COMMIT_RETRY_MILLIS = 125L;
    private final ModSyncConfig config;
    private final HttpClient client;
    private final Consumer<String> logger;
    private final SyncObserver observer;
    private final AtomicInteger completedFiles = new AtomicInteger();
    private int totalFiles = 1;

    ReleaseArtifactResolver(ModSyncConfig config, Consumer<String> logger) {
        this(config, logger, SyncObserver.NONE);
    }

    ReleaseArtifactResolver(ModSyncConfig config, Consumer<String> logger, SyncObserver observer) {
        this.config = config;
        this.logger = logger;
        this.observer = observer;
        this.client = RequiredManifestFetcher.createClient(config.connectTimeout());
    }

    void setTotalFiles(int totalFiles) {
        this.totalFiles = Math.max(totalFiles, 1);
        completedFiles.set(0);
    }

    void prefetch(List<ReleaseManifestV5.FileEntry> entries) throws IOException, InterruptedException {
        setTotalFiles(entries.size());
        ParallelDownloadRunner.run(entries.size(), index -> fetch(entries.get(index)));
    }

    byte[] readCached(ReleaseManifestV5.FileEntry entry) throws IOException {
        Path cached = cachePath(entry);
        byte[] bytes = readValidCache(cached, entry);
        if (bytes == null) {
            throw new IOException("预下载缓存缺失或已损坏: " + entry.path());
        }
        return bytes;
    }

    @Override
    public byte[] fetch(ReleaseManifestV5.FileEntry entry) throws IOException, InterruptedException {
        Path cached = cachePath(entry);
        byte[] cachedBytes = readValidCache(cached, entry);
        if (cachedBytes != null) {
            byte[] bytes = cachedBytes;
            reportCompleted(entry, bytes.length);
            return bytes;
        }
        List<URI> candidates = resolveCandidates(entry);
        IOException last = null;
        for (URI candidate : candidates) {
            try {
                byte[] bytes = RequiredManifestFetcher.fetch(
                        client,
                        candidate,
                        config.requestTimeout(),
                        Math.min(config.maxFileBytes(), Math.max(entry.size(), 1L)),
                        BuildInfo.USER_AGENT,
                        "MCSync file " + entry.path(),
                        logger);
                if (bytes.length != entry.size() || !Hashing.sha256(bytes).equals(entry.sha256())) {
                    throw new IOException("候选源返回的文件与清单锁定哈希不一致");
                }
                storeCache(cached, bytes, entry.sha256());
                reportCompleted(entry, bytes.length);
                return bytes;
            } catch (IOException failure) {
                last = failure;
                logger.accept("MCSync 下载候选失败，正在尝试下一来源: " + candidate + " — " + failure.getMessage());
            }
        }
        throw new IOException("所有下载候选均失败: " + entry.path(), last);
    }

    private void reportCompleted(ReleaseManifestV5.FileEntry entry, long bytes) {
        int completed = completedFiles.incrementAndGet();
        observer.downloadProgress(new SyncObserver.DownloadProgress(
                entry.path(), completed, totalFiles, bytes, bytes,
                completed, totalFiles, completed * 1000 / totalFiles));
    }

    private Path cachePath(ReleaseManifestV5.FileEntry entry) throws IOException {
        Path directory = config.gameDirectory().resolve(".modsync").resolve("cache-v5");
        Files.createDirectories(directory);
        return directory.resolve(entry.sha256() + ".bin");
    }

    private static byte[] readValidCache(Path target, ReleaseManifestV5.FileEntry entry) throws IOException {
        synchronized (cacheLock(target)) {
            if (!isValidCache(target, entry.size(), entry.sha256())) return null;
            return Files.readAllBytes(target);
        }
    }

    private static void storeCache(Path target, byte[] bytes, String expectedSha256) throws IOException {
        synchronized (cacheLock(target)) {
            if (isValidCache(target, bytes.length, expectedSha256)) return;
            Path temporary = Files.createTempFile(target.getParent(), ".download-", ".part");
            try {
                Files.write(temporary, bytes);
                FileSystemException lastSharingFailure = null;
                for (int attempt = 1; attempt <= CACHE_COMMIT_ATTEMPTS; attempt++) {
                    if (isValidCache(target, bytes.length, expectedSha256)) return;
                    try {
                        try {
                            Files.move(temporary, target,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (AtomicMoveNotSupportedException failure) {
                            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (!isValidCache(target, bytes.length, expectedSha256)) {
                            throw new IOException("下载缓存提交后哈希校验失败: " + target.getFileName());
                        }
                        return;
                    } catch (FileSystemException failure) {
                        lastSharingFailure = failure;
                        if (isValidCache(target, bytes.length, expectedSha256)) return;
                        if (attempt == CACHE_COMMIT_ATTEMPTS) throw failure;
                        try {
                            Thread.sleep(CACHE_COMMIT_RETRY_MILLIS * attempt);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("等待下载缓存提交重试时被中断", interrupted);
                        }
                    }
                }
                if (lastSharingFailure != null) {
                    throw lastSharingFailure;
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static boolean isValidCache(Path target, long expectedSize, String expectedSha256) throws IOException {
        return Files.isRegularFile(target)
                && Files.size(target) == expectedSize
                && Hashing.sha256(target).equals(expectedSha256);
    }

    private static Object cacheLock(Path target) {
        int index = Math.floorMod(target.toAbsolutePath().normalize().hashCode(), CACHE_LOCKS.length);
        return CACHE_LOCKS[index];
    }

    private static Object[] createCacheLocks() {
        Object[] locks = new Object[256];
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
        return locks;
    }

    private List<URI> resolveCandidates(ReleaseManifestV5.FileEntry entry)
            throws IOException, InterruptedException {
        ReleaseManifestV5.DownloadSource source = entry.download();
        ArrayList<URI> result = new ArrayList<>();
        preferredEndpoints(source.endpoints(), "file", Locale.getDefault(Locale.Category.DISPLAY)).stream()
                .map(ReleaseManifestV5.DownloadEndpoint::uri)
                .forEach(result::add);
        switch (source.type()) {
            case "publisher-hosted" -> {
                if (result.isEmpty()) result.add(resolvePublisherPath(entry.path()));
            }
            case "direct" -> {
                // Explicit file endpoints above are the complete candidate set.
            }
            case "modrinth" -> {
                // New v5 publications pin exact file endpoints at publication time. Metadata lookup is
                // retained only as a compatibility fallback for older v5 manifests without file URLs.
                if (result.isEmpty()) result.addAll(resolveModrinth(source));
            }
            case "curseforge" -> {
                if (result.isEmpty()) {
                    throw new IOException("CurseForge 文件必须由发布器解析成固定 file URL；客户端不携带 API key");
                }
            }
            case "manual" -> throw new IOException("manual 文件不参与自动下载");
            default -> throw new IOException("未知下载源: " + source.type());
        }
        return result.stream().distinct().toList();
    }

    private List<URI> resolveModrinth(ReleaseManifestV5.DownloadSource source)
            throws IOException, InterruptedException {
        List<ReleaseManifestV5.DownloadEndpoint> apiEndpoints = preferredEndpoints(
                source.endpoints(), "api", Locale.getDefault(Locale.Category.DISPLAY));
        ArrayList<URI> result = new ArrayList<>();
        IOException last = null;
        for (ReleaseManifestV5.DownloadEndpoint endpoint : apiEndpoints) {
            URI metadataUri = endpoint.uri().resolve("version/" + Rfc3986.encodePathSegment(source.versionId()));
            boolean acquired = false;
            try {
                PLATFORM_METADATA_LIMIT.acquire();
                acquired = true;
                byte[] response = RequiredManifestFetcher.fetch(
                        client, metadataUri, config.requestTimeout(), 2 * 1024 * 1024L,
                        BuildInfo.USER_AGENT, "Modrinth pinned version metadata", logger);
                Map<String, Object> metadata = object(StrictJson.parse(new String(response, java.nio.charset.StandardCharsets.UTF_8)));
                if (!source.projectId().equals(metadata.get("project_id"))) {
                    throw new IOException("Modrinth version 不属于清单锁定 projectId");
                }
                Object files = metadata.get("files");
                if (!(files instanceof List<?> list)) throw new IOException("Modrinth metadata 缺少 files");
                for (Object raw : list) {
                    Map<String, Object> file = object(raw);
                    Object url = file.get("url");
                    Object hashesRaw = file.get("hashes");
                    if (!(url instanceof String text) || !(hashesRaw instanceof Map<?, ?> hashes)
                            || !(hashes.get("sha256") instanceof String)) continue;
                    URI uri = URI.create(text);
                    if (!uri.isAbsolute() || !uri.getScheme().equalsIgnoreCase("https")) continue;
                    result.add(uri);
                }
            } catch (IOException failure) {
                last = failure;
                logger.accept("Modrinth 固定版本解析失败，尝试下一 API: " + metadataUri
                        + " — " + failure.getMessage());
            } finally {
                if (acquired) PLATFORM_METADATA_LIMIT.release();
            }
        }
        if (result.isEmpty()) throw new IOException("无法解析锁定的 Modrinth versionId", last);
        return result;
    }

    static List<ReleaseManifestV5.DownloadEndpoint> preferredEndpoints(
            List<ReleaseManifestV5.DownloadEndpoint> endpoints, String purpose, Locale locale) {
        boolean chinaMirror = isSimplifiedChinese(locale);
        return endpoints.stream()
                .filter(endpoint -> endpoint.purpose().equals(purpose))
                .filter(endpoint -> chinaMirror || isGlobalOfficial(endpoint))
                .sorted(chinaMirror
                        ? Comparator.comparingInt(ReleaseManifestV5.DownloadEndpoint::priority)
                        : Comparator.comparingInt((ReleaseManifestV5.DownloadEndpoint endpoint) ->
                                isGlobalOfficial(endpoint) ? 0 : 1)
                                .thenComparingInt(ReleaseManifestV5.DownloadEndpoint::priority))
                .toList();
    }

    static boolean isSimplifiedChinese(Locale locale) {
        if (locale == null || !"zh".equalsIgnoreCase(locale.getLanguage())) return false;
        if ("Hans".equalsIgnoreCase(locale.getScript())) return true;
        return "CN".equalsIgnoreCase(locale.getCountry());
    }

    private static boolean isGlobalOfficial(ReleaseManifestV5.DownloadEndpoint endpoint) {
        return !endpoint.thirdParty()
                && !"mirror".equals(endpoint.role())
                && !"cn".equals(endpoint.region());
    }

    private URI resolvePublisherPath(String path) {
        String encoded = String.join("/", java.util.Arrays.stream(path.split("/"))
                .map(Rfc3986::encodePathSegment).toList());
        return config.manifestUri().resolve("./" + encoded);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException("平台 API 返回值不是 JSON 对象");
        return (Map<String, Object>) map;
    }
}

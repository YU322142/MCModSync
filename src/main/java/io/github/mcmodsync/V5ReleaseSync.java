package io.github.mcmodsync;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Detects schema-v5 manifests while leaving every legacy text manifest on the 1.9 path. */
final class V5ReleaseSync {
    record Loaded(ReleaseManifestV5 manifest, byte[] bytes, String sha256) {
        Loaded {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private V5ReleaseSync() {
    }

    static Optional<Loaded> load(ModSyncConfig config, Consumer<String> logger)
            throws IOException, InterruptedException {
        HttpClient client = RequiredManifestFetcher.createClient(config.connectTimeout());
        byte[] bytes = RequiredManifestFetcher.fetch(
                client,
                config.manifestUri(),
                config.requestTimeout(),
                Math.max(config.maxManifestBytes(), ReleaseManifestV5.MAX_MANIFEST_BYTES),
                BuildInfo.USER_AGENT,
                "MCSync manifest",
                logger);
        int first = 0;
        while (first < bytes.length && Character.isWhitespace(bytes[first])) first++;
        if (first >= bytes.length || bytes[first] != '{') return Optional.empty();
        ReleaseManifestV5 manifest;
        try {
            manifest = ReleaseManifestV5.parse(bytes);
        } catch (IllegalArgumentException failure) {
            throw new IOException("MCSync v5 清单无效: " + failure.getMessage(), failure);
        }
        if (VersionOrder.compare(BuildInfo.VERSION, manifest.minimumMcsyncVersion()) < 0) {
            throw new IOException("该发布要求 MCSync >= " + manifest.minimumMcsyncVersion()
                    + "，当前为 " + BuildInfo.VERSION);
        }
        return Optional.of(new Loaded(manifest, bytes, Hashing.sha256(bytes)));
    }

    static SyncProbeResult probe(
            ModSyncConfig config,
            Loaded loaded,
            Consumer<String> logger,
            SyncObserver observer) throws IOException, InterruptedException {
        V5RecommendedSelectionStore.Resolution selection = V5RecommendedSelectionStore.resolve(
                loaded.manifest(), config.gameDirectory(), RuntimeEnvironment.detect());
        ReleaseManifestV5 effective = selection.effectiveManifest();
        if (selection.selectionPending()) {
            System.setProperty("modsync.recommendedSelectionPending", "true");
            logger.accept("MCSync 推荐 Mod 清单需要在 Minecraft 窗口内确认；本轮只处理必须项和既有选择");
        }
        boolean changes = new ReleaseTransactionEngine(config.gameDirectory(), config.fileOperationRetries())
                .needsApply(effective, loaded.sha256());
        if (changes) {
            observer.phaseChanged("检测到 v5 OTA，正在游戏窗口内完成下载与哈希校验……");
            ReleaseArtifactResolver resolver = new ReleaseArtifactResolver(config, logger, observer);
            resolver.prefetch(downloadsNeeded(config, effective));
        }
        CommitWorkload workload = changes ? commitWorkload(config, effective) : CommitWorkload.NONE;
        return new SyncProbeResult(
                changes ? SyncProbeResult.Status.CHANGES_REQUIRED : SyncProbeResult.Status.UP_TO_DATE,
                workload.files(),
                workload.bytes());
    }

    static SyncResult synchronize(
            ModSyncConfig config,
            Loaded loaded,
            Consumer<String> logger,
            SyncObserver observer) throws IOException, InterruptedException {
        V5RecommendedSelectionStore.Resolution selection = V5RecommendedSelectionStore.resolve(
                loaded.manifest(), config.gameDirectory(), RuntimeEnvironment.detect());
        ReleaseManifestV5 effective = selection.effectiveManifest();
        if (selection.selectionPending()) {
            System.setProperty("modsync.recommendedSelectionPending", "true");
        }
        observer.phaseChanged("正在暂存并校验 MCSync v5 发布事务……");
        ReleaseArtifactResolver resolver = new ReleaseArtifactResolver(config, logger, observer);
        resolver.prefetch(downloadsNeeded(config, effective));
        ReleaseTransactionEngine.Result result = new ReleaseTransactionEngine(
                config.gameDirectory(), config.fileOperationRetries())
                .apply(effective, loaded.sha256(), resolver::readCached);
        if (!result.changed()) return new SyncResult(SyncResult.Status.UNCHANGED, 0, 0, 0);
        return new SyncResult(SyncResult.Status.UPDATED, result.installed(), result.removed(), 0);
    }

    private static List<ReleaseManifestV5.FileEntry> downloadsNeeded(
            ModSyncConfig config,
            ReleaseManifestV5 manifest) throws IOException {
        List<ReleaseManifestV5.FileEntry> downloads = new java.util.ArrayList<>();
        ManagedPathPolicy paths = new ManagedPathPolicy(config.gameDirectory(), manifest.managedScopes());
        for (ReleaseManifestV5.FileEntry entry : manifest.files()) {
            if (!(entry.side().contains("client") || entry.side().contains("both"))
                    || entry.download().type().equals("manual")) continue;
            Path target = paths.resolve(entry.path(), true);
            if (paths.policyFor(entry.path()).equals("first-install") && Files.exists(target)) continue;
            if (Files.isRegularFile(target) && Files.size(target) == entry.size()
                    && Hashing.sha256(target).equals(entry.sha256())) continue;
            downloads.add(entry);
        }
        return downloads;
    }

    private static CommitWorkload commitWorkload(ModSyncConfig config, ReleaseManifestV5 manifest)
            throws IOException {
        ManagedPathPolicy paths = new ManagedPathPolicy(config.gameDirectory(), manifest.managedScopes());
        LinkedHashMap<String, Long> staged = new LinkedHashMap<>();
        java.util.LinkedHashSet<String> desiredPaths = new java.util.LinkedHashSet<>();
        for (ReleaseManifestV5.FileEntry entry : manifest.files()) {
            if (!(entry.side().contains("client") || entry.side().contains("both"))) continue;
            desiredPaths.add(entry.path());
            Path target = paths.resolve(entry.path(), true);
            if (paths.policyFor(entry.path()).equals("first-install") && Files.exists(target)) continue;
            if (Files.isRegularFile(target) && Files.size(target) == entry.size()
                    && Hashing.sha256(target).equals(entry.sha256())) continue;
            if (!entry.download().type().equals("manual")) staged.put(entry.path(), entry.size());
        }
        for (ReleaseManifestV5.ConfigOperation operation : manifest.configOperations()) {
            if (!operation.phase().equals("prelaunch")
                    || !(operation.side().contains("client")
                    || operation.side().contains("integrated_server")
                    || operation.side().contains("both"))) continue;
            desiredPaths.add(operation.path());
            if (operation.operation().equals("file-replace") || staged.containsKey(operation.path())) continue;
            Path target = paths.resolve(operation.path(), true);
            byte[] base = Files.isRegularFile(target)
                    ? Files.readAllBytes(target) : emptyConfigDocument(operation.format());
            ConfigMutationEngine.MutationResult mutation = ConfigMutationEngine.apply(base, operation);
            if (mutation.changed()) staged.put(operation.path(), (long) mutation.bytes().length);
        }
        for (Map.Entry<String, String> previous : new ReleaseOwnershipLedger(
                config.gameDirectory().resolve(".modsync")).read().entrySet()) {
            if (desiredPaths.contains(previous.getKey()) || !paths.isManaged(previous.getKey())) continue;
            Path target = paths.resolve(previous.getKey(), true);
            if (Files.isRegularFile(target) && Hashing.sha256(target).equals(previous.getValue())) {
                staged.put(previous.getKey(), Files.size(target));
            }
        }
        long bytes = 0L;
        for (long size : staged.values()) bytes = saturatedAdd(bytes, size);
        return new CommitWorkload(staged.size(), bytes);
    }

    private static byte[] emptyConfigDocument(String format) throws IOException {
        return switch (format) {
            case "json" -> "{}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case "toml", "properties" -> new byte[0];
            default -> throw new IOException("缺失配置文件不能用该格式创建: " + format);
        };
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + Math.max(right, 0L);
    }

    private record CommitWorkload(int files, long bytes) {
        private static final CommitWorkload NONE = new CommitWorkload(0, 0L);
    }

}

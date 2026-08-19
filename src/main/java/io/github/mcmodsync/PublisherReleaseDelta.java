package io.github.mcmodsync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compares immutable v5 releases and writes a minimal upload/replacement plan. */
final class PublisherReleaseDelta {
    record Action(String path, long size, String sha256, String source) {
    }

    record Plan(
            List<Action> upload,
            List<Action> reuse,
            List<Action> external,
            List<Action> removed,
            long uploadBytes,
            long reuseBytes) {
        Plan {
            upload = List.copyOf(upload);
            reuse = List.copyOf(reuse);
            external = List.copyOf(external);
            removed = List.copyOf(removed);
        }
    }

    private PublisherReleaseDelta() {
    }

    static ReleaseManifestV5.FileEntry reusable(
            ReleaseManifestV5 previous, String currentPath, String sha256, long size) {
        if (previous == null) return null;
        return previous.files().stream()
                .filter(file -> file.size() == size && file.sha256().equalsIgnoreCase(sha256))
                .filter(file -> file.download().type().equals("publisher-hosted"))
                .filter(file -> !file.download().endpoints().isEmpty())
                .sorted(Comparator.comparing((ReleaseManifestV5.FileEntry file) -> !file.path().equals(currentPath))
                        .thenComparing(ReleaseManifestV5.FileEntry::path))
                .findFirst().orElse(null);
    }

    static Plan plan(
            ReleaseManifestV5 previous,
            ReleaseManifestV5 current,
            Set<String> reusedHostedPaths) {
        Set<String> reused = new LinkedHashSet<>(reusedHostedPaths);
        ArrayList<Action> upload = new ArrayList<>();
        ArrayList<Action> reuse = new ArrayList<>();
        ArrayList<Action> external = new ArrayList<>();
        for (ReleaseManifestV5.FileEntry file : current.files()) {
            Action action = new Action(file.path(), file.size(), file.sha256(), endpoint(file.download()));
            if (reused.contains(file.path())) reuse.add(action);
            else if (file.download().type().equals("publisher-hosted")) upload.add(action);
            else external.add(action);
        }
        Set<String> currentPaths = current.files().stream()
                .map(ReleaseManifestV5.FileEntry::path)
                .collect(java.util.stream.Collectors.toSet());
        ArrayList<Action> removed = new ArrayList<>();
        if (previous != null) {
            for (ReleaseManifestV5.FileEntry file : previous.files()) {
                if (!currentPaths.contains(file.path())) {
                    removed.add(new Action(file.path(), file.size(), file.sha256(), endpoint(file.download())));
                }
            }
        }
        Comparator<Action> byPath = Comparator.comparing(Action::path);
        upload.sort(byPath);
        reuse.sort(byPath);
        external.sort(byPath);
        removed.sort(byPath);
        return new Plan(upload, reuse, external, removed,
                upload.stream().mapToLong(Action::size).sum(),
                reuse.stream().mapToLong(Action::size).sum());
    }

    static Paths write(
            Path outputRoot,
            Plan plan,
            ReleaseManifestV5 previous,
            ReleaseManifestV5 current,
            String stablePath,
            String serverListPath) throws IOException {
        Path json = outputRoot.resolve("UPLOAD-PLAN.json");
        Path zh = outputRoot.resolve("UPLOAD-GUIDE.zh-CN.md");
        Path en = outputRoot.resolve("UPLOAD-GUIDE.en.md");
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("schema", 1);
        root.put("previousReleaseId", previous == null ? "" : previous.releaseId());
        root.put("previousReleaseSequence", previous == null ? null : previous.releaseSequence());
        root.put("currentReleaseId", current.releaseId());
        root.put("currentReleaseSequence", current.releaseSequence());
        root.put("uploadFileCount", plan.upload().size());
        root.put("uploadBytes", plan.uploadBytes());
        root.put("reusedFileCount", plan.reuse().size());
        root.put("reusedBytes", plan.reuseBytes());
        root.put("externalFileCount", plan.external().size());
        root.put("removedPathCount", plan.removed().size());
        root.put("upload", rows(plan.upload()));
        root.put("reuse", rows(plan.reuse()));
        root.put("external", rows(plan.external()));
        root.put("removed", rows(plan.removed()));
        root.put("stableManifestPath", stablePath);
        root.put("serverListManifestPath", serverListPath);
        Files.writeString(json, StrictJson.stringify(root) + "\n", StandardCharsets.UTF_8);
        Files.writeString(zh, guide(plan, current, stablePath, serverListPath, true), StandardCharsets.UTF_8);
        Files.writeString(en, guide(plan, current, stablePath, serverListPath, false), StandardCharsets.UTF_8);
        return new Paths(json, zh, en);
    }

    record Paths(Path json, Path zh, Path en) {
    }

    private static List<Object> rows(List<Action> actions) {
        return actions.stream().map(action -> (Object) Map.of(
                "path", action.path(), "size", action.size(), "sha256", action.sha256(),
                "source", action.source())).toList();
    }

    private static String endpoint(ReleaseManifestV5.DownloadSource source) {
        if (source.endpoints().isEmpty()) return source.type();
        return source.endpoints().getFirst().uri().toASCIIString();
    }

    private static String guide(
            Plan plan, ReleaseManifestV5 current, String stablePath, String serverListPath, boolean zh) {
        StringBuilder out = new StringBuilder();
        if (zh) {
            out.append("# MCSync 增量上传与替换指南\n\n")
                    .append("当前发布：`").append(current.releaseId()).append("` / `")
                    .append(current.releaseSequence()).append("`\n\n")
                    .append("## 汇总\n\n")
                    .append("- 需要上传：").append(plan.upload().size()).append(" 个文件，")
                    .append(plan.uploadBytes()).append(" 字节。\n")
                    .append("- 复用上一版：").append(plan.reuse().size()).append(" 个文件，避免重复上传 ")
                    .append(plan.reuseBytes()).append(" 字节。\n")
                    .append("- 上游或外部下载：").append(plan.external().size()).append(" 个文件，不上传到发布目录。\n")
                    .append("- 本版不再引用：").append(plan.removed().size()).append(" 个旧路径；不要删除旧不可变版本，保留回滚能力。\n\n")
                    .append("## 操作顺序\n\n")
                    .append("1. 只上传下方“需要上传”的文件，保持导出目录中的相对路径。\n")
                    .append("2. “复用上一版”的文件已经在新清单中指向旧的不可变 URL，不要重复上传。\n")
                    .append("3. 上游或外部下载项由客户端按清单获取，不上传到你的版本目录。\n")
                    .append("   本次升级包中的完整 `manifest-v5.json` 是下一次增量发布的基线；它会列出全部终态文件，即使旧文件本体不在本包中。\n")
                    .append("   不要删除历史 `releases/`，因为新清单可能继续引用其中的不可变文件。\n")
                    .append(serverListPath.isBlank() ? "" : "4. 上传服务器列表清单及同目录的 `servers.dat`。\n")
                    .append(serverListPath.isBlank() ? "4. " : "5. ")
                    .append("最后原子替换 `").append(stablePath).append("`；在此之前客户端仍使用上一版。\n\n")
                    .append("## 需要上传\n\n");
        } else {
            out.append("# MCSync Incremental Upload and Replacement Guide\n\n")
                    .append("Current release: `").append(current.releaseId()).append("` / `")
                    .append(current.releaseSequence()).append("`\n\n")
                    .append("## Summary\n\n")
                    .append("- Upload required: ").append(plan.upload().size()).append(" files, ")
                    .append(plan.uploadBytes()).append(" bytes.\n")
                    .append("- Reused from the previous release: ").append(plan.reuse().size())
                    .append(" files, avoiding ").append(plan.reuseBytes()).append(" bytes of duplicate upload.\n")
                    .append("- Upstream or external downloads: ").append(plan.external().size())
                    .append(" files, not uploaded to the release directory.\n")
                    .append("- No longer referenced: ").append(plan.removed().size())
                    .append(" old paths; keep immutable old releases for rollback instead of deleting them.\n\n")
                    .append("## Operation Order\n\n")
                    .append("1. Upload only the files under “Upload Required”, preserving their relative paths in the export directory.\n")
                    .append("2. Files under “Reused from the Previous Release” already point to old immutable URLs in the new manifest; do not upload them again.\n")
                    .append("3. Upstream or external items are fetched by the client from the manifest and are not uploaded to your release directory.\n")
                    .append("   The complete `manifest-v5.json` in this upgrade package is the baseline for the next incremental publication; it lists the entire desired state even when old payloads are absent.\n")
                    .append("   Do not delete historical `releases/` directories because the new manifest may still reference their immutable files.\n")
                    .append(serverListPath.isBlank() ? "" : "4. Upload the server-list manifest and its sibling `servers.dat`.\n")
                    .append(serverListPath.isBlank() ? "4. " : "5. ")
                    .append("Atomically replace `").append(stablePath).append("` last; clients continue using the previous release until then.\n\n")
                    .append("## Upload Required\n\n");
        }
        appendActions(out, plan.upload(), zh);
        out.append(zh ? "\n## 复用上一版\n\n" : "\n## Reused from the Previous Release\n\n");
        appendActions(out, plan.reuse(), zh);
        out.append(zh ? "\n## 上游或外部下载\n\n" : "\n## Upstream or External Downloads\n\n");
        appendActions(out, plan.external(), zh);
        out.append(zh ? "\n## 本版不再引用\n\n" : "\n## No Longer Referenced\n\n");
        appendActions(out, plan.removed(), zh);
        return out.toString();
    }

    private static void appendActions(StringBuilder out, List<Action> actions, boolean zh) {
        if (actions.isEmpty()) {
            out.append(zh ? "- 无。\n" : "- None.\n");
            return;
        }
        for (Action action : actions) {
            out.append("- `").append(action.path()).append("` — ")
                    .append(action.size()).append(" bytes — `").append(action.sha256()).append("` — ")
                    .append(action.source()).append('\n');
        }
    }
}

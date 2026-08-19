package io.github.mcmodsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Locates the newest immutable v5 release metadata inside a previous publisher output tree. */
final class PublisherOutputBaseline {
    private PublisherOutputBaseline() {
    }

    static ReleaseManifestV5 read(Path outputRoot) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
            return readArchiveOrManifest(root);
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("上一版发布输出/升级包不存在: " + root);
        }
        ArrayList<Path> candidates = new ArrayList<>();
        addIfFile(candidates, root.resolve("manifest-v5.json"));
        addIfFile(candidates, root.resolve("channel/stable/mods-v5.json"));
        try (var stream = Files.walk(root, 6)) {
            candidates.addAll(stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(PublisherOutputBaseline::isManifestName)
                    .toList());
        }
        if (candidates.isEmpty()) {
            throw new IOException("上一版发布输出中未找到发布记录；请选择由 MCSync 生成的完整输出目录");
        }
        ReleaseManifestV5 newest = null;
        IOException lastFailure = null;
        for (Path candidate : candidates.stream().distinct().toList()) {
            try {
                ReleaseManifestV5 parsed = ReleaseManifestV5.parse(Files.readAllBytes(candidate));
                if (newest == null || parsed.releaseSequence() > newest.releaseSequence()) newest = parsed;
            } catch (Exception failure) {
                lastFailure = new IOException(candidate + ": " + failure.getMessage(), failure);
            }
        }
        if (newest == null) throw new IOException("上一版发布输出中的发布记录均无法解析", lastFailure);
        return newest;
    }

    private static ReleaseManifestV5 readArchiveOrManifest(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("manifest-v5.json") || name.equals("mods-v5.json")) {
            try {
                return ReleaseManifestV5.parse(Files.readAllBytes(file));
            } catch (RuntimeException failure) {
                throw new IOException("上一版 v5 清单无法解析: " + file, failure);
            }
        }
        if (!name.endsWith(".zip")) {
            throw new IOException("上一版基线必须是 MCSync 输出目录、manifest-v5.json 或 ZIP 升级包: " + file);
        }
        ReleaseManifestV5 newest = null;
        IOException lastFailure = null;
        try (ZipFile archive = new ZipFile(file.toFile())) {
            var entries = archive.stream()
                    .filter(entry -> !entry.isDirectory() && isManifestName(entry.getName()))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : entries) {
                try (var input = archive.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes(ReleaseManifestV5.MAX_MANIFEST_BYTES + 1);
                    if (bytes.length > ReleaseManifestV5.MAX_MANIFEST_BYTES) {
                        throw new IOException("ZIP 内 v5 清单超过大小限制: " + entry.getName());
                    }
                    ReleaseManifestV5 parsed = ReleaseManifestV5.parse(bytes);
                    if (newest == null || parsed.releaseSequence() > newest.releaseSequence()) newest = parsed;
                } catch (Exception failure) {
                    lastFailure = new IOException(entry.getName() + ": " + failure.getMessage(), failure);
                }
            }
        }
        if (newest == null) {
            throw new IOException("ZIP 升级包中未找到可解析的完整 manifest-v5.json/mods-v5.json", lastFailure);
        }
        return newest;
    }

    private static boolean isManifestName(Path path) {
        return isManifestName(path.getFileName().toString());
    }

    private static boolean isManifestName(String path) {
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = (slash >= 0 ? name.substring(slash + 1) : name).toLowerCase(Locale.ROOT);
        return name.equals("manifest-v5.json") || name.equals("mods-v5.json");
    }

    private static void addIfFile(ArrayList<Path> candidates, Path path) {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) candidates.add(path);
    }
}

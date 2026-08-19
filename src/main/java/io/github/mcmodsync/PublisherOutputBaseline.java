package io.github.mcmodsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;

/** Locates the newest immutable v5 release metadata inside a previous publisher output tree. */
final class PublisherOutputBaseline {
    private PublisherOutputBaseline() {
    }

    static ReleaseManifestV5 read(Path outputRoot) throws IOException {
        Path root = outputRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("上一版发布输出目录不存在或不是目录: " + root);
        }
        ArrayList<Path> candidates = new ArrayList<>();
        addIfFile(candidates, root.resolve("manifest-v5.json"));
        addIfFile(candidates, root.resolve("channel/stable/mods-v5.json"));
        Path releases = root.resolve("releases");
        if (Files.isDirectory(releases, LinkOption.NOFOLLOW_LINKS)) {
            try (var stream = Files.walk(releases, 3)) {
                candidates.addAll(stream
                        .filter(path -> path.getFileName().toString().equalsIgnoreCase("manifest-v5.json"))
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .toList());
            }
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

    private static void addIfFile(ArrayList<Path> candidates, Path path) {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) candidates.add(path);
    }
}

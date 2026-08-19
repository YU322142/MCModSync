package io.github.mcmodsync;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;

/** Reuses platform file candidates that were already verified in the previous immutable release. */
final class PublisherPlatformVerificationCache {
    private PublisherPlatformVerificationCache() {
    }

    static ReleaseManifestV5.FileEntry reusable(
            ReleaseManifestV5 previous,
            String currentPath,
            String sha256,
            long size,
            Map<String, Object> currentSource) {
        if (previous == null || currentSource == null) return null;
        String type = text(currentSource.get("type"));
        if (!type.equals("modrinth") && !type.equals("curseforge")) return null;
        String projectId = text(currentSource.get("projectId"));
        String versionId = text(currentSource.get("versionId"));
        Long fileId = integer(currentSource.get("fileId"));
        String distributionPolicy = text(currentSource.get("distributionPolicy"));
        return previous.files().stream()
                .filter(file -> file.size() == size && file.sha256().equalsIgnoreCase(sha256))
                .filter(file -> sourceMatches(
                        file.download(), type, projectId, versionId, fileId, distributionPolicy))
                .filter(file -> file.download().endpoints().stream()
                        .anyMatch(endpoint -> endpoint.purpose().equals("file")))
                .sorted(Comparator.comparing((ReleaseManifestV5.FileEntry file) ->
                                !file.path().equals(currentPath))
                        .thenComparing(ReleaseManifestV5.FileEntry::path))
                .findFirst().orElse(null);
    }

    private static boolean sourceMatches(
            ReleaseManifestV5.DownloadSource previous,
            String type,
            String projectId,
            String versionId,
            Long fileId,
            String distributionPolicy) {
        if (!previous.type().equals(type)
                || !previous.projectId().equals(projectId)
                || !previous.distributionPolicy().equals(distributionPolicy)) return false;
        if (type.equals("modrinth")) return previous.versionId().equals(versionId);
        return previous.fileId() != null && previous.fileId().equals(fileId);
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }

    private static Long integer(Object value) {
        if (value instanceof BigDecimal number) {
            try {
                return number.longValueExact();
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        if (value instanceof Number number) return number.longValue();
        return null;
    }
}

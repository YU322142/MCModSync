package io.github.mcmodsync;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable, evidence-only cache for platform files already verified against local bytes. */
final class PublisherPlatformVerificationStore {
    static final String RELATIVE_PATH = ".modsync/publisher-platform-verifications-v1.json";
    private static final int SCHEMA = 1;
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final Path path;
    private final LinkedHashMap<String, Object> entries;

    private PublisherPlatformVerificationStore(Path path, LinkedHashMap<String, Object> entries) {
        this.path = path;
        this.entries = entries;
    }

    static PublisherPlatformVerificationStore open(Path gameRoot) {
        Path path = gameRoot.toAbsolutePath().normalize()
                .resolve(RELATIVE_PATH.replace('/', java.io.File.separatorChar));
        LinkedHashMap<String, Object> entries = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(path) && Files.size(path) <= MAX_BYTES) {
                Object parsed = StrictJson.parse(Files.readString(path, StandardCharsets.UTF_8));
                if (parsed instanceof Map<?, ?> root
                        && new BigDecimal(Integer.toString(SCHEMA)).equals(root.get("schema"))
                        && root.get("entries") instanceof Map<?, ?> cached) {
                    for (Map.Entry<?, ?> entry : cached.entrySet()) {
                        if (entry.getKey() instanceof String key && entry.getValue() instanceof Map<?, ?> value) {
                            entries.put(key, new LinkedHashMap<>(cast(value)));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // A cache is only an optimization. Corrupt or obsolete evidence is ignored and rebuilt.
            entries.clear();
        }
        return new PublisherPlatformVerificationStore(path, entries);
    }

    Map<String, Object> lookup(Map<String, Object> source, String sha256, long size) {
        Identity identity = Identity.of(source, sha256, size);
        if (identity == null) return null;
        Object raw = entries.get(identity.key());
        if (!(raw instanceof Map<?, ?> cached) || !identity.matches(cached)) return null;
        Object download = cached.get("download");
        if (!(download instanceof Map<?, ?> map) || !hasFileEndpoint(map)) return null;
        return new LinkedHashMap<>(cast(map));
    }

    void put(Map<String, Object> source, String sha256, long size, Map<String, Object> resolved) {
        Identity identity = Identity.of(source, sha256, size);
        if (identity == null || !hasFileEndpoint(resolved)) return;
        LinkedHashMap<String, Object> evidence = identity.json();
        evidence.put("verifiedAt", Instant.now().toString());
        evidence.put("download", new LinkedHashMap<>(resolved));
        entries.put(identity.key(), evidence);
        try {
            write();
        } catch (IOException ignored) {
            // Publishing remains valid if this optional optimization cannot be persisted.
        }
    }

    void put(
            Map<String, Object> source,
            String sha256,
            long size,
            ReleaseManifestV5.DownloadSource verified) {
        put(source, sha256, size, ReleaseManifestV5.downloadJson(verified));
    }

    int size() {
        return entries.size();
    }

    private void write() throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, StrictJson.stringify(Map.of(
                "schema", SCHEMA,
                "entries", entries)) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean hasFileEndpoint(Map<?, ?> source) {
        if (!(source.get("endpoints") instanceof Iterable<?> endpoints)) return false;
        for (Object endpoint : endpoints) {
            if (endpoint instanceof Map<?, ?> map && "file".equals(map.get("purpose"))) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private record Identity(
            String type,
            String projectId,
            String versionId,
            Long fileId,
            String distributionPolicy,
            String sha256,
            long size) {
        static Identity of(Map<String, Object> source, String sha256, long size) {
            if (source == null || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}") || size < 0) return null;
            String type = text(source.get("type"));
            if (!type.equals("modrinth") && !type.equals("curseforge")) return null;
            String projectId = text(source.get("projectId"));
            String versionId = text(source.get("versionId"));
            Long fileId = integer(source.get("fileId"));
            String policy = text(source.get("distributionPolicy"));
            if (projectId.isBlank() || policy.isBlank()) return null;
            if (type.equals("modrinth") && versionId.isBlank()) return null;
            if (type.equals("curseforge") && (fileId == null || fileId < 1)) return null;
            return new Identity(type, projectId, versionId, fileId, policy, sha256.toLowerCase(), size);
        }

        String key() {
            String source = type + "\u0000" + projectId + "\u0000" + versionId + "\u0000"
                    + (fileId == null ? "" : fileId) + "\u0000" + distributionPolicy + "\u0000"
                    + sha256 + "\u0000" + size;
            return Hashing.sha256(source.getBytes(StandardCharsets.UTF_8));
        }

        LinkedHashMap<String, Object> json() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("type", type);
            result.put("projectId", projectId);
            result.put("versionId", versionId);
            result.put("fileId", fileId);
            result.put("distributionPolicy", distributionPolicy);
            result.put("sha256", sha256);
            result.put("size", size);
            return result;
        }

        boolean matches(Map<?, ?> value) {
            return type.equals(value.get("type"))
                    && projectId.equals(value.get("projectId"))
                    && versionId.equals(value.get("versionId"))
                    && java.util.Objects.equals(fileId, integer(value.get("fileId")))
                    && distributionPolicy.equals(value.get("distributionPolicy"))
                    && sha256.equals(value.get("sha256"))
                    && size == longValue(value.get("size"));
        }

        private static String text(Object value) {
            return value instanceof String text ? text : "";
        }

        private static Long integer(Object value) {
            if (value == null) return null;
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

        private static long longValue(Object value) {
            Long parsed = integer(value);
            return parsed == null ? Long.MIN_VALUE : parsed;
        }
    }
}

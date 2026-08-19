package io.github.mcmodsync;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves publisher-only platform APIs into credential-free, pinned client file candidates. */
final class PublisherPlatformResolver {
    private static final String CURSEFORGE_KEY_PROPERTY = "mcsync.curseforgeApiKey";
    private static final String CURSEFORGE_KEY_ENVIRONMENT = "MCSYNC_CURSEFORGE_API_KEY";
    private final HttpClient client;

    PublisherPlatformResolver() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    PublisherPlatformResolver(HttpClient client) {
        this.client = client;
    }

    Map<String, Object> resolve(Map<String, Object> raw) throws IOException, InterruptedException {
        return resolve(raw, "", -1L);
    }

    Map<String, Object> resolve(Map<String, Object> raw, String expectedSha256, long expectedSize)
            throws IOException, InterruptedException {
        return resolve(raw, expectedSha256, "", expectedSize);
    }

    Map<String, Object> resolve(Map<String, Object> raw, String expectedSha256, String expectedSha512,
                                long expectedSize) throws IOException, InterruptedException {
        LinkedHashMap<String, Object> source = new LinkedHashMap<>(raw);
        if ("modrinth".equals(source.get("type"))) {
            return resolveModrinth(source, expectedSha256, expectedSha512, expectedSize);
        }
        if (!"curseforge".equals(source.get("type"))) return source;
        String projectId = text(source.get("projectId"), "CurseForge projectId");
        long fileId = integer(source.get("fileId"), "CurseForge fileId");
        List<Map<String, Object>> endpoints = withMirrorFallback(
                endpointMaps(source.get("endpoints")), "curseforge");
        boolean alreadyResolved = endpoints.stream().anyMatch(endpoint -> "file".equals(endpoint.get("purpose")));
        if (alreadyResolved) {
            if (!strictHashRequested(expectedSha256, expectedSize)) return source;
            IOException last = null;
            ArrayList<Map<String, Object>> verified = new ArrayList<>(endpoints.stream()
                    .filter(value -> !"file".equals(value.get("purpose"))).toList());
            for (Map<String, Object> endpoint : endpoints.stream()
                    .filter(value -> "file".equals(value.get("purpose")))
                    .sorted(Comparator.comparingInt(value -> ((Number) value.getOrDefault("priority", 100)).intValue()))
                    .toList()) {
                try {
                    URI fileUri = URI.create(text(endpoint.get("url"), "CurseForge file endpoint"));
                    if (downloadMatches(fileUri, expectedSha256, expectedSize)) {
                        addDistinctEndpoint(verified, endpoint);
                    } else {
                        last = new IOException("CurseForge 固定文件未通过 SHA-256/大小复核");
                    }
                } catch (IOException failure) {
                    last = failure;
                }
            }
            if (verified.stream().noneMatch(value -> "file".equals(value.get("purpose")))) {
                throw new IOException("CurseForge 固定文件复核失败，已放弃该平台来源", last);
            }
            source.put("endpoints", verified);
            return source;
        }

        ArrayList<Map<String, Object>> resolved = new ArrayList<>(endpoints);
        IOException last = null;
        for (Map<String, Object> endpoint : endpoints.stream()
                .filter(value -> "api".equals(value.get("purpose")))
                .sorted(Comparator.comparingInt(value -> ((Number) value.getOrDefault("priority", 100)).intValue()))
                .toList()) {
            URI apiBase = URI.create(text(endpoint.get("url"), "CurseForge API endpoint"));
            URI requestUri = apiBase.resolve("mods/" + Rfc3986.encodePathSegment(projectId)
                    + "/files/" + fileId + "/download-url");
            try {
                URI upstreamFile = requestDownloadUrl(requestUri);
                ArrayList<ResolvedCandidate> candidates = new ArrayList<>();
                URI regionalFile = mirrorCurseForgeDownloadUrl(apiBase, upstreamFile);
                candidates.add(new ResolvedCandidate(regionalFile, endpoint));
                if (!regionalFile.equals(upstreamFile)) {
                    LinkedHashMap<String, Object> official = new LinkedHashMap<>(endpoint);
                    official.put("role", "official");
                    official.put("region", "global");
                    official.put("priority", Math.max(100,
                            ((Number) endpoint.getOrDefault("priority", 100)).intValue()));
                    official.put("thirdParty", false);
                    candidates.add(new ResolvedCandidate(upstreamFile, official));
                }
                for (ResolvedCandidate candidate : candidates) {
                    if (strictHashRequested(expectedSha256, expectedSize)
                            && !downloadMatches(candidate.uri(), expectedSha256, expectedSize)) {
                        last = new IOException("CurseForge 候选未通过 SHA-256/大小复核: " + candidate.uri());
                        continue;
                    }
                    LinkedHashMap<String, Object> fileEndpoint = new LinkedHashMap<>(candidate.template());
                    fileEndpoint.put("url", candidate.uri().toASCIIString());
                    fileEndpoint.put("purpose", "file");
                    addDistinctEndpoint(resolved, fileEndpoint);
                }
            } catch (IOException failure) {
                last = failure;
            }
        }
        if (resolved.stream().noneMatch(endpoint -> "file".equals(endpoint.get("purpose")))) {
            throw new IOException("无法把 CurseForge 固定 fileId 解析为下载地址", last);
        }
        source.put("endpoints", resolved);
        return source;
    }

    private static void addDistinctEndpoint(List<Map<String, Object>> endpoints, Map<String, Object> candidate) {
        String url = String.valueOf(candidate.get("url"));
        if (endpoints.stream().noneMatch(existing -> url.equals(String.valueOf(existing.get("url")))
                && String.valueOf(candidate.get("purpose")).equals(String.valueOf(existing.get("purpose"))))) {
            endpoints.add(new LinkedHashMap<>(candidate));
        }
    }

    private record ResolvedCandidate(URI uri, Map<String, Object> template) {
    }

    /**
     * MCIMirror's CurseForge API intentionally preserves the upstream ForgeCDN URL in metadata.
     * For mainland-China delivery the documented mirror form keeps the exact path while replacing
     * the ForgeCDN origin with mod.mcimirror.top. The resulting bytes are still verified against
     * the publisher's local SHA-256 and size before the URL is accepted.
     */
    static URI mirrorCurseForgeDownloadUrl(URI apiBase, URI upstreamFile) throws IOException {
        if (!"mod.mcimirror.top".equalsIgnoreCase(apiBase.getHost())) return upstreamFile;
        String host = upstreamFile.getHost();
        if (!("edge.forgecdn.net".equalsIgnoreCase(host)
                || "media.forgecdn.net".equalsIgnoreCase(host)
                || "mediafilez.forgecdn.net".equalsIgnoreCase(host))) {
            throw new IOException("MCIMirror CurseForge API 返回了非 ForgeCDN 文件地址");
        }
        String rawPath = upstreamFile.getRawPath();
        if (rawPath == null || !rawPath.startsWith("/")) {
            throw new IOException("ForgeCDN 文件地址缺少绝对路径");
        }
        try {
            return URI.create("https://mod.mcimirror.top" + rawPath
                    + (upstreamFile.getRawQuery() == null ? "" : "?" + upstreamFile.getRawQuery()));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("无法构造 MCIMirror CurseForge 文件镜像地址", invalid);
        }
    }

    private static boolean strictHashRequested(String expectedSha256, long expectedSize) {
        return expectedSha256 != null && expectedSha256.matches("[0-9a-fA-F]{64}") && expectedSize >= 0;
    }

    private boolean downloadMatches(URI fileUri, String expectedSha256, long expectedSize)
            throws IOException, InterruptedException {
        Path temporary = Files.createTempFile("mcsync-curseforge-verify-", ".jar");
        try {
            HttpResponse<Path> response = client.send(HttpRequest.newBuilder(fileUri)
                    .timeout(Duration.ofSeconds(90))
                    .header("User-Agent", BuildInfo.USER_AGENT)
                    .GET().build(), HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() < 200 || response.statusCode() >= 300) return false;
            return Files.size(temporary) == expectedSize
                    && Hashing.sha256(temporary).equalsIgnoreCase(expectedSha256);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Map<String, Object> resolveModrinth(
            LinkedHashMap<String, Object> source, String expectedSha256, String expectedSha512, long expectedSize)
            throws IOException, InterruptedException {
        String projectId = text(source.get("projectId"), "Modrinth projectId");
        String versionId = text(source.get("versionId"), "Modrinth versionId");
        boolean hasSha256 = expectedSha256 != null && expectedSha256.matches("[0-9a-fA-F]{64}");
        boolean hasSha512 = expectedSha512 != null && expectedSha512.matches("[0-9a-fA-F]{128}");
        if ((!hasSha256 && !hasSha512) || expectedSize < 0) {
            throw new IOException("解析 Modrinth 固定文件需要当前 JAR 的 SHA-256 或 SHA-512 和大小");
        }
        List<Map<String, Object>> endpoints = withMirrorFallback(
                endpointMaps(source.get("endpoints")), "modrinth");
        boolean alreadyResolved = endpoints.stream().anyMatch(endpoint -> "file".equals(endpoint.get("purpose")));
        if (alreadyResolved) return source;
        ArrayList<Map<String, Object>> resolved = new ArrayList<>(endpoints);
        IOException last = null;
        for (Map<String, Object> endpoint : endpoints.stream()
                .filter(value -> "api".equals(value.get("purpose")))
                .sorted(Comparator.comparingInt(value -> ((Number) value.getOrDefault("priority", 100)).intValue()))
                .toList()) {
            URI apiBase = URI.create(text(endpoint.get("url"), "Modrinth API endpoint"));
            URI requestUri = apiBase.resolve("version/" + Rfc3986.encodePathSegment(versionId));
            try {
                Map<String, Object> metadata = requestJsonObject(requestUri);
                if (projectId.equals(metadata.get("project_id")) && metadata.get("files") instanceof List<?> files) {
                    appendMatchingModrinthFiles(
                            files, endpoint, resolved, expectedSha256, expectedSha512, expectedSize);
                }
            } catch (IOException failure) {
                last = failure;
            }
            // A saved publisher project can contain a versionId that is stale, deleted,
            // or no longer visible from the selected API endpoint. A 404 here must not
            // suppress the hash repair: enumerate the locked project and resolve the
            // current local JAR by its exact platform hash and size.
            if (resolved.stream().noneMatch(value -> "file".equals(value.get("purpose")))) {
                try {
                    Object versions = requestJson(apiBase.resolve("project/" +
                            Rfc3986.encodePathSegment(projectId) + "/version"));
                    if (versions instanceof List<?> versionList) {
                        for (Object version : versionList) {
                            if (!(version instanceof Map<?, ?> rawVersion)) continue;
                            if (!projectId.equals(rawVersion.get("project_id"))) continue;
                            Object versionFiles = rawVersion.get("files");
                            if (versionFiles instanceof List<?> list) {
                                int before = resolved.size();
                                appendMatchingModrinthFiles(list, endpoint, resolved,
                                        expectedSha256, expectedSha512, expectedSize);
                                if (resolved.size() > before && rawVersion.get("id") instanceof String currentVersionId) {
                                    source.put("versionId", currentVersionId);
                                }
                            }
                            if (resolved.stream().anyMatch(value -> "file".equals(value.get("purpose")))) break;
                        }
                    }
                } catch (IOException failure) {
                    last = failure;
                }
            }
            if (resolved.stream().anyMatch(value -> "file".equals(value.get("purpose")))) break;
        }
        if (resolved.stream().noneMatch(endpoint -> "file".equals(endpoint.get("purpose")))) {
            throw new IOException("无法把 Modrinth 固定 versionId 解析为当前哈希文件", last);
        }
        source.put("endpoints", resolved);
        return source;
    }

    private URI requestDownloadUrl(URI uri) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", BuildInfo.USER_AGENT)
                .GET();
        if ("api.curseforge.com".equalsIgnoreCase(uri.getHost())) {
            String key = System.getProperty(CURSEFORGE_KEY_PROPERTY, "").strip();
            if (key.isEmpty()) key = System.getenv().getOrDefault(CURSEFORGE_KEY_ENVIRONMENT, "").strip();
            if (key.isEmpty()) {
                throw new IOException("官方 CurseForge API 需要发布者本机 API key；客户端与清单不会保存该 key");
            }
            request.header("x-api-key", key);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("CurseForge API HTTP " + response.statusCode());
        }
        Object parsed = StrictJson.parse(response.body());
        if (!(parsed instanceof Map<?, ?> object) || !(object.get("data") instanceof String value)) {
            throw new IOException("CurseForge API 缺少 data 下载地址");
        }
        URI file = URI.create(value);
        if (!"https".equalsIgnoreCase(file.getScheme()) || file.getHost() == null
                || file.getUserInfo() != null || file.getFragment() != null) {
            throw new IOException("CurseForge API 返回了不安全的下载地址");
        }
        return file;
    }

    private Map<String, Object> requestJsonObject(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", BuildInfo.USER_AGENT)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Modrinth API HTTP " + response.statusCode());
        }
        Object parsed = StrictJson.parse(response.body());
        if (!(parsed instanceof Map<?, ?> raw)) throw new IOException("Modrinth API 返回值不是对象");
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) raw;
        return result;
    }

    private Object requestJson(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", BuildInfo.USER_AGENT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Modrinth API HTTP " + response.statusCode());
        }
        return StrictJson.parse(response.body());
    }

    @SuppressWarnings("unchecked")
    private static void appendMatchingModrinthFiles(
            List<?> files, Map<String, Object> endpoint, ArrayList<Map<String, Object>> resolved,
            String expectedSha256, String expectedSha512, long expectedSize) {
        for (Object value : files) {
            if (!(value instanceof Map<?, ?> rawFile)) continue;
            Map<String, Object> file = (Map<String, Object>) rawFile;
            if (!(file.get("url") instanceof String url)
                    || !(file.get("size") instanceof Number size)
                    || !(file.get("hashes") instanceof Map<?, ?> hashes)
                    || size.longValue() != expectedSize) continue;
            Object sha256Value = hashes.get("sha256");
            Object sha512Value = hashes.get("sha512");
            boolean sha256Matches = expectedSha256 != null && expectedSha256.matches("[0-9a-fA-F]{64}")
                    && sha256Value instanceof String sha256 && sha256.equalsIgnoreCase(expectedSha256);
            boolean sha512Matches = expectedSha512 != null && expectedSha512.matches("[0-9a-fA-F]{128}")
                    && sha512Value instanceof String sha512 && sha512.equalsIgnoreCase(expectedSha512);
            if (!sha256Matches && !sha512Matches) continue;
            URI fileUri = URI.create(url);
            if (!"https".equalsIgnoreCase(fileUri.getScheme()) || fileUri.getHost() == null
                    || fileUri.getUserInfo() != null || fileUri.getFragment() != null) continue;
            LinkedHashMap<String, Object> fileEndpoint = new LinkedHashMap<>(endpoint);
            fileEndpoint.put("url", fileUri.toASCIIString());
            fileEndpoint.put("purpose", "file");
            resolved.add(fileEndpoint);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> endpointMaps(Object value) throws IOException {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new IOException("download.endpoints 必须是数组");
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new IOException("download.endpoints[] 必须是对象");
            result.add(new LinkedHashMap<>((Map<String, Object>) map));
        }
        return result;
    }

    static List<Map<String, Object>> withMirrorFallback(
            List<Map<String, Object>> endpoints, String platform) {
        URI official = "curseforge".equals(platform)
                ? DownloadEndpointPresets.CURSEFORGE_OFFICIAL : DownloadEndpointPresets.MODRINTH_OFFICIAL;
        URI mirror = "curseforge".equals(platform)
                ? DownloadEndpointPresets.CURSEFORGE_MCIMIRROR : DownloadEndpointPresets.MODRINTH_MCIMIRROR;
        boolean hasOfficial = endpoints.stream().anyMatch(endpoint -> "api".equals(endpoint.get("purpose"))
                && official.equals(safeUri(endpoint.get("url"))));
        boolean hasMirror = endpoints.stream().anyMatch(endpoint -> "api".equals(endpoint.get("purpose"))
                && mirror.equals(safeUri(endpoint.get("url"))));
        if (!hasOfficial || hasMirror) return endpoints;
        ArrayList<Map<String, Object>> augmented = new ArrayList<>(endpoints);
        augmented.add(new LinkedHashMap<>(Map.of(
                "url", mirror.toASCIIString(), "role", "mirror", "purpose", "api",
                "region", "cn", "priority", 10, "thirdParty", true)));
        return List.copyOf(augmented);
    }

    private static URI safeUri(Object value) {
        if (!(value instanceof String text)) return null;
        try {
            return URI.create(text);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String text(Object value, String field) throws IOException {
        if (!(value instanceof String text) || text.isBlank()) throw new IOException(field + " 缺失");
        return text;
    }

    private static long integer(Object value, String field) throws IOException {
        if (!(value instanceof java.math.BigDecimal number)) throw new IOException(field + " 缺失");
        try {
            return number.longValueExact();
        } catch (ArithmeticException failure) {
            throw new IOException(field + " 必须是整数", failure);
        }
    }
}

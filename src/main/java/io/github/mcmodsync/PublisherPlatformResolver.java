package io.github.mcmodsync;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    PublisherPlatformResolver(HttpClient client) {
        this.client = client;
    }

    Map<String, Object> resolve(Map<String, Object> raw) throws IOException, InterruptedException {
        return resolve(raw, "", -1L);
    }

    Map<String, Object> resolve(Map<String, Object> raw, String expectedSha256, long expectedSize)
            throws IOException, InterruptedException {
        LinkedHashMap<String, Object> source = new LinkedHashMap<>(raw);
        if ("modrinth".equals(source.get("type"))) {
            return resolveModrinth(source, expectedSha256, expectedSize);
        }
        if (!"curseforge".equals(source.get("type"))) return source;
        String projectId = text(source.get("projectId"), "CurseForge projectId");
        long fileId = integer(source.get("fileId"), "CurseForge fileId");
        List<Map<String, Object>> endpoints = endpointMaps(source.get("endpoints"));
        boolean alreadyResolved = endpoints.stream().anyMatch(endpoint -> "file".equals(endpoint.get("purpose")));
        if (alreadyResolved) return source;

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
                URI fileUri = requestDownloadUrl(requestUri);
                if (strictHashRequested(expectedSha256, expectedSize)
                        && !downloadMatches(fileUri, expectedSha256, expectedSize)) {
                    throw new IOException("CurseForge 下载内容未通过 SHA-256/大小复核，已放弃该平台来源");
                }
                LinkedHashMap<String, Object> fileEndpoint = new LinkedHashMap<>(endpoint);
                fileEndpoint.put("url", fileUri.toASCIIString());
                fileEndpoint.put("purpose", "file");
                resolved.add(fileEndpoint);
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

    private static boolean strictHashRequested(String expectedSha256, long expectedSize) {
        return expectedSha256 != null && expectedSha256.matches("[0-9a-fA-F]{64}") && expectedSize >= 0;
    }

    private boolean downloadMatches(URI fileUri, String expectedSha256, long expectedSize)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(fileUri)
                .timeout(Duration.ofSeconds(90))
                .header("User-Agent", BuildInfo.USER_AGENT)
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) return false;
        byte[] bytes = response.body();
        return bytes.length == expectedSize && Hashing.sha256(bytes).equalsIgnoreCase(expectedSha256);
    }

    private Map<String, Object> resolveModrinth(
            LinkedHashMap<String, Object> source, String expectedSha256, long expectedSize)
            throws IOException, InterruptedException {
        String projectId = text(source.get("projectId"), "Modrinth projectId");
        String versionId = text(source.get("versionId"), "Modrinth versionId");
        if (!expectedSha256.matches("[0-9a-fA-F]{64}") || expectedSize < 0) {
            throw new IOException("解析 Modrinth 固定文件需要当前 JAR 的 SHA-256 和大小");
        }
        List<Map<String, Object>> endpoints = endpointMaps(source.get("endpoints"));
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
                if (!projectId.equals(metadata.get("project_id"))) {
                    throw new IOException("Modrinth version 不属于锁定 projectId");
                }
                Object filesRaw = metadata.get("files");
                if (!(filesRaw instanceof List<?> files)) throw new IOException("Modrinth metadata 缺少 files");
                for (Object value : files) {
                    if (!(value instanceof Map<?, ?> rawFile)) continue;
                    @SuppressWarnings("unchecked") Map<String, Object> file = (Map<String, Object>) rawFile;
                    if (!(file.get("url") instanceof String url)
                            || !(file.get("size") instanceof Number size)
                            || !(file.get("hashes") instanceof Map<?, ?> hashes)
                            || !(hashes.get("sha256") instanceof String sha256)
                            || size.longValue() != expectedSize
                            || !sha256.equalsIgnoreCase(expectedSha256)) continue;
                    URI fileUri = URI.create(url);
                    if (!"https".equalsIgnoreCase(fileUri.getScheme()) || fileUri.getHost() == null
                            || fileUri.getUserInfo() != null || fileUri.getFragment() != null) continue;
                    LinkedHashMap<String, Object> fileEndpoint = new LinkedHashMap<>(endpoint);
                    fileEndpoint.put("url", fileUri.toASCIIString());
                    fileEndpoint.put("purpose", "file");
                    resolved.add(fileEndpoint);
                }
            } catch (IOException failure) {
                last = failure;
            }
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

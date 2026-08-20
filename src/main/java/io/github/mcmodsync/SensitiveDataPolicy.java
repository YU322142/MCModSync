package io.github.mcmodsync;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Prevents release manifests and hosted config snapshots from becoming credential distribution channels. */
final class SensitiveDataPolicy {
    private static final int INSPECTION_WINDOW_BYTES = 1024 * 1024;
    private static final int INSPECTION_OVERLAP_BYTES = 512;
    private static final Set<String> CONFIG_ROOTS = Set.of(
            "config", "defaultconfigs", "configureddefaults");
    private static final Set<String> BLACKLISTED_PATH_SEGMENTS = Set.of(
            ".archive-unpack", ".cache", "cache", "caches", "backup", "backups",
            "account", "accounts", "session", "sessions", "credentials", "secrets");
    private static final Set<String> BLACKLISTED_FILE_NAMES = Set.of(
            "web_server.json", "resourceful-config-web.json", "auth.json", "credentials.json",
            "secrets.json", "tokens.json", "accounts.json", "session.json", "sessions.json");
    private static final Set<String> BLACKLISTED_SUFFIXES = Set.of(
            ".log", ".lock", ".tmp", ".bak", ".old");
    private static final Set<String> SENSITIVE_SEGMENTS = Set.of(
            "token", "password", "passwd", "secret", "credential", "credentials",
            "apikey", "api_key", "accesskey", "access_key", "privatekey", "private_key",
            "authtoken", "auth_token");
    private static final Pattern JSON_KEY = Pattern.compile(
            "(?i)\\\"(?:token|password|passwd|secret|credential|credentials|api[_-]?key|access[_-]?key|private[_-]?key|auth[_-]?token)\\\"\\s*:");
    private static final Pattern TEXT_KEY = Pattern.compile(
            "(?im)^\\s*(?:token|password|passwd|secret|credential|credentials|api[_-]?key|access[_-]?key|private[_-]?key|auth[_-]?token)\\s*[=:]");

    private SensitiveDataPolicy() {
    }

    static void rejectSensitiveConfigKey(String key) {
        if (key == null || key.isBlank()) return;
        for (String segment : key.toLowerCase(Locale.ROOT).split("[._-]")) {
            if (SENSITIVE_SEGMENTS.contains(segment)
                    || segment.endsWith("token") || segment.endsWith("password")
                    || segment.endsWith("secret") || segment.endsWith("credential")
                    || segment.endsWith("apikey")) {
                throw new IllegalArgumentException("凭据键禁止通过 MCSync OTA 管理: " + key);
            }
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
        if (SENSITIVE_SEGMENTS.contains(normalized)) {
            throw new IllegalArgumentException("凭据键禁止通过 MCSync OTA 管理: " + key);
        }
    }

    static boolean looksLikeCredentialDocument(byte[] bytes) {
        if (bytes.length == 0) return false;
        int start = 0;
        while (start < bytes.length) {
            int end = Math.min(bytes.length, start + INSPECTION_WINDOW_BYTES);
            String text = new String(bytes, start, end - start, StandardCharsets.UTF_8);
            if (JSON_KEY.matcher(text).find() || TEXT_KEY.matcher(text).find()) return true;
            if (end == bytes.length) break;
            start = end - INSPECTION_OVERLAP_BYTES;
        }
        return false;
    }

    static boolean isConfigTree(String relative) {
        if (relative == null) return false;
        String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
        String first = normalized.split("/", 2)[0];
        return CONFIG_ROOTS.contains(first);
    }

    /** Returns a human-readable reason when a publisher-side full-file scan must omit this path. */
    static String publisherScanExclusionReason(String relative, byte[] bytes) {
        if (!isConfigTree(relative)) return null;
        String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
        String[] segments = normalized.split("/");
        for (int index = 1; index < segments.length - 1; index++) {
            if (BLACKLISTED_PATH_SEGMENTS.contains(segments[index])) {
                return "配置运行态/身份目录已列入黑名单: " + segments[index];
            }
        }
        String fileName = segments[segments.length - 1];
        if (BLACKLISTED_FILE_NAMES.contains(fileName)) {
            return "已知凭据或身份文件已列入黑名单";
        }
        for (String suffix : BLACKLISTED_SUFFIXES) {
            if (fileName.endsWith(suffix)) {
                return "配置备份、临时或运行态文件已列入黑名单: " + suffix;
            }
        }
        String stem = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        for (String segment : stem.split("[._-]")) {
            if (SENSITIVE_SEGMENTS.contains(segment)
                    || segment.endsWith("password") || segment.endsWith("secret")
                    || segment.endsWith("credential") || segment.endsWith("apikey")
                    || segment.endsWith("authtoken")) {
                return "文件名疑似包含凭据类型: " + segment;
            }
        }
        if (bytes != null && looksLikeCredentialDocument(bytes)) {
            return "配置内容包含 token/password/secret/API key 等敏感键";
        }
        return null;
    }
}

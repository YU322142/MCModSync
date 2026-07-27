package io.github.mcmodsync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ModManifest {
    static final String MAGIC_V1 = "# mcmod-sync-v1";
    static final String MAGIC_V2 = "# mcmod-sync-v2";
    static final String MAGIC = MAGIC_V2;
    private static final Pattern MD5_PATTERN = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Set<Character> WINDOWS_FORBIDDEN = Set.of('<', '>', ':', '"', '|', '?', '*');

    private final List<ManifestEntry> entries;

    private ModManifest(List<ManifestEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    static ModManifest fromEntries(List<ManifestEntry> entries) {
        return new ModManifest(entries);
    }

    static ModManifest scan(Path modsDirectory) throws IOException {
        return scan(modsDirectory, new String[0]);
    }

    static ModManifest scan(Path modsDirectory, String... excludedModIds) throws IOException {
        Path normalized = modsDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IOException("Mod 目录不存在或不是文件夹: " + normalized);
        }

        List<Path> jars;
        try (var stream = Files.list(normalized)) {
            jars = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }

        if (jars.isEmpty()) {
            throw new IOException("目录中没有找到任何 .jar Mod；为防止生成会清空客户端的空清单，操作已取消。");
        }

        Set<String> excluded = new HashSet<>();
        for (String modId : excludedModIds) {
            excluded.add(modId.toLowerCase(Locale.ROOT));
        }
        List<ManifestEntry> entries = new ArrayList<>(jars.size());
        for (Path jar : jars) {
            String fileName = jar.getFileName().toString();
            validateFileName(fileName);
            String modId = FabricModMetadata.readModId(jar);
            String lowerFileName = fileName.toLowerCase(Locale.ROOT);
            boolean legacySyncToolName = excluded.contains("mcmodsync")
                    && (lowerFileName.equals("mcmodsync.jar")
                            || (lowerFileName.startsWith("mcmodsync-") && lowerFileName.endsWith(".jar")));
            if (excluded.contains(modId) || legacySyncToolName) {
                continue;
            }
            entries.add(new ManifestEntry(Hashing.md5(jar), modId, fileName));
        }
        if (entries.isEmpty()) {
            throw new IOException("没有可发布的 .jar Mod；为防止生成空清单，操作已取消。");
        }
        return new ModManifest(entries);
    }

    static ModManifest parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("清单内容为空");
        }

        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int format = 0;
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.equals(MAGIC_V1)) {
                if (format == 2) {
                    throw new IllegalArgumentException("清单不能同时声明 v1 和 v2");
                }
                format = 1;
            } else if (stripped.equals(MAGIC_V2)) {
                if (format == 1) {
                    throw new IllegalArgumentException("清单不能同时声明 v1 和 v2");
                }
                format = 2;
            }
        }
        if (format == 0) {
            throw new IllegalArgumentException("不是受支持的清单：缺少 " + MAGIC_V1 + " 或 " + MAGIC_V2);
        }

        List<ManifestEntry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }

            int firstSeparator = line.indexOf('\t');
            if (firstSeparator <= 0 || firstSeparator == line.length() - 1) {
                throw new IllegalArgumentException("清单第 " + (index + 1) + " 行格式错误");
            }

            String md5 = line.substring(0, firstSeparator).strip().toLowerCase(Locale.ROOT);
            String modId = "";
            String fileName;
            if (format == 2) {
                int secondSeparator = line.indexOf('\t', firstSeparator + 1);
                if (secondSeparator <= firstSeparator + 1 || secondSeparator == line.length() - 1) {
                    throw new IllegalArgumentException(
                            "清单第 " + (index + 1) + " 行格式错误，应为 MD5、Mod ID、文件名");
                }
                String rawModId = line.substring(firstSeparator + 1, secondSeparator).strip();
                if (!rawModId.equals("-")) {
                    modId = rawModId.toLowerCase(Locale.ROOT);
                    if (!FabricModMetadata.isValidModId(modId)) {
                        throw new IllegalArgumentException("清单第 " + (index + 1) + " 行 Mod ID 无效: " + rawModId);
                    }
                }
                fileName = line.substring(secondSeparator + 1);
            } else {
                fileName = line.substring(firstSeparator + 1);
            }
            if (!MD5_PATTERN.matcher(md5).matches()) {
                throw new IllegalArgumentException("清单第 " + (index + 1) + " 行 MD5 无效: " + md5);
            }
            validateFileName(fileName);
            String key = fileName.toLowerCase(Locale.ROOT);
            if (!names.add(key)) {
                throw new IllegalArgumentException("清单包含重复文件名（忽略大小写）: " + fileName);
            }
            entries.add(new ManifestEntry(md5, modId, fileName));
            if (entries.size() > 10_000) {
                throw new IllegalArgumentException("清单条目超过安全上限 10000");
            }
        }

        if (entries.isEmpty()) {
            throw new IllegalArgumentException("清单不包含任何 Mod；为防止误清空客户端，已拒绝执行");
        }
        return new ModManifest(entries);
    }

    void write(Path output) throws IOException {
        Path normalized = output.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                normalized,
                serialize(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    String serialize() {
        StringBuilder builder = new StringBuilder();
        builder.append(MAGIC).append('\n');
        builder.append("# minecraft=1.21.11\n");
        builder.append("# loader=fabric\n");
        for (ManifestEntry entry : entries) {
            builder.append(entry.md5())
                    .append('\t')
                    .append(entry.modId().isEmpty() ? "-" : entry.modId())
                    .append('\t')
                    .append(entry.fileName())
                    .append('\n');
        }
        return builder.toString();
    }

    List<ManifestEntry> entries() {
        return entries;
    }

    void ensureUniqueModIds() {
        Set<String> seen = new HashSet<>();
        for (ManifestEntry entry : entries) {
            if (!entry.modId().isEmpty() && !seen.add(entry.modId())) {
                throw new IllegalArgumentException("清单包含重复 Fabric Mod ID: " + entry.modId());
            }
        }
    }

    long entriesWithoutModId() {
        return entries.stream().filter(entry -> entry.modId().isEmpty()).count();
    }

    void verifySnapshot(Path modsDirectory) throws IOException {
        Path normalized = modsDirectory.toAbsolutePath().normalize();
        verifyManagedFiles(normalized);
        Set<String> expected = new HashSet<>();
        for (ManifestEntry entry : entries) {
            expected.add(entry.fileName().toLowerCase(Locale.ROOT));
        }

        try (var stream = Files.list(normalized)) {
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(item -> item.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .toList()) {
                if (!expected.contains(path.getFileName().toString().toLowerCase(Locale.ROOT))) {
                    throw new IOException("本地存在未记录在 mods.txt 中的 Mod: " + path.getFileName());
                }
            }
        }
    }

    void verifyManagedFiles(Path modsDirectory) throws IOException {
        Path normalized = modsDirectory.toAbsolutePath().normalize();
        for (ManifestEntry entry : entries) {
            Path file = normalized.resolve(entry.fileName()).normalize();
            if (!normalized.equals(file.getParent()) || !Files.isRegularFile(file)) {
                throw new IOException("本地清单所列文件不存在: " + entry.fileName());
            }
            String actual = Hashing.md5(file);
            if (!actual.equals(entry.md5())) {
                throw new IOException("本地清单 MD5 不符: " + entry.fileName()
                        + "，期望 " + entry.md5() + "，实际 " + actual);
            }
        }
    }

    private static void validateFileName(String fileName) {
        if (fileName == null || fileName.isEmpty() || !fileName.equals(fileName.strip())) {
            throw new IllegalArgumentException("文件名不能为空，也不能以空白开头或结尾: " + fileName);
        }
        if (fileName.equals(".") || fileName.equals("..") || fileName.length() > 240) {
            throw new IllegalArgumentException("不安全的文件名: " + fileName);
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("清单只允许 .jar 文件: " + fileName);
        }
        for (int i = 0; i < fileName.length(); i++) {
            char current = fileName.charAt(i);
            if (current < 32 || current == 127 || current == '/' || current == '\\' || WINDOWS_FORBIDDEN.contains(current)) {
                throw new IllegalArgumentException("文件名包含不允许的字符: " + fileName);
            }
        }
    }

}

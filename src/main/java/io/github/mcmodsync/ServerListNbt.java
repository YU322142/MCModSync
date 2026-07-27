package io.github.mcmodsync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class ServerListNbt {
    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_SHORT = 2;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LONG = 4;
    private static final byte TAG_FLOAT = 5;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_BYTE_ARRAY = 7;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;
    private static final byte TAG_INT_ARRAY = 11;
    private static final byte TAG_LONG_ARRAY = 12;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_COLLECTION_LENGTH = 1_000_000;
    private static final int MAX_TAGS = 2_000_000;

    private ServerListNbt() {
    }

    static Document read(Path path) throws IOException {
        byte[] prefix = new byte[2];
        try (InputStream input = Files.newInputStream(path)) {
            int first = input.read();
            int second = input.read();
            prefix[0] = (byte) first;
            prefix[1] = (byte) second;
        }
        boolean compressed = (prefix[0] & 0xff) == 0x1f && (prefix[1] & 0xff) == 0x8b;
        try (InputStream file = Files.newInputStream(path);
                InputStream buffered = new BufferedInputStream(file);
                InputStream decoded = compressed ? new GZIPInputStream(buffered) : buffered;
                DataInputStream input = new DataInputStream(decoded)) {
            Limits limits = new Limits();
            byte rootType = input.readByte();
            if (rootType != TAG_COMPOUND) {
                throw new IOException("servers.dat 根标签不是 Compound");
            }
            String rootName = input.readUTF();
            Compound root = readCompound(input, 0, limits);
            return new Document(rootName, root, compressed);
        } catch (RuntimeException exception) {
            throw new IOException("servers.dat NBT 内容无效", exception);
        }
    }

    static void write(Path path, Document document) throws IOException {
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        try (OutputStream file = Files.newOutputStream(path);
                OutputStream buffered = new BufferedOutputStream(file);
                OutputStream encoded = document.compressed() ? new GZIPOutputStream(buffered) : buffered;
                DataOutputStream output = new DataOutputStream(encoded)) {
            output.writeByte(TAG_COMPOUND);
            output.writeUTF(document.rootName());
            writeCompound(output, document.root(), 0);
        }
    }

    static Document merge(Document cloud, Document local, Document previousCloud) throws IOException {
        List<Tag> desiredEntries = serverTags(cloud);
        List<Tag> localEntries = local == null ? List.of() : serverTags(local);
        List<Tag> previousEntries = previousCloud == null ? List.of() : serverTags(previousCloud);

        LinkedHashMap<String, Tag> desiredByAddress = indexedByAddress(desiredEntries, "云端");
        Set<String> previousAddresses = indexedByAddress(previousEntries, "上次云端").keySet();
        List<Tag> merged = new ArrayList<>(desiredEntries);
        Set<String> included = new LinkedHashSet<>(desiredByAddress.keySet());
        for (Tag localEntry : localEntries) {
            String address = address(localEntry);
            if (desiredByAddress.containsKey(address) || previousAddresses.contains(address)) {
                continue;
            }
            if (included.add(address)) {
                merged.add(localEntry);
            }
        }

        Compound root = new Compound();
        root.putAll(cloud.root());
        root.put("servers", new Tag(TAG_LIST, new ListValue(TAG_COMPOUND, List.copyOf(merged))));
        boolean compressed = local != null ? local.compressed() : cloud.compressed();
        return new Document(cloud.rootName(), root, compressed);
    }

    static boolean containsCurrentCloudEntries(Document cloud, Document local) throws IOException {
        LinkedHashMap<String, Tag> desiredByAddress = indexedByAddress(serverTags(cloud), "云端");
        LinkedHashMap<String, Tag> localByAddress = indexedByAddress(serverTags(local), "本地");
        for (Map.Entry<String, Tag> desired : desiredByAddress.entrySet()) {
            Tag localEntry = localByAddress.get(desired.getKey());
            if (localEntry == null) {
                return false;
            }
            String desiredName = stringValue(compound(desired.getValue(), "云端服务器条目"), "name");
            String localName = stringValue(compound(localEntry, "本地服务器条目"), "name");
            if (!desiredName.equals(localName)) {
                return false;
            }
        }
        return true;
    }

    static void writeSimple(Path path, List<ServerInfo> servers) throws IOException {
        List<Tag> entries = new ArrayList<>();
        for (ServerInfo server : servers) {
            Compound entry = new Compound();
            entry.put("name", new Tag(TAG_STRING, server.name()));
            entry.put("ip", new Tag(TAG_STRING, server.address()));
            entries.add(new Tag(TAG_COMPOUND, entry));
        }
        Compound root = new Compound();
        root.put("servers", new Tag(TAG_LIST, new ListValue(TAG_COMPOUND, List.copyOf(entries))));
        write(path, new Document("", root, false));
    }

    static List<ServerInfo> readServerInfo(Path path) throws IOException {
        List<ServerInfo> result = new ArrayList<>();
        for (Tag tag : serverTags(read(path))) {
            Compound compound = compound(tag, "服务器条目");
            result.add(new ServerInfo(stringValue(compound, "name"), stringValue(compound, "ip")));
        }
        return List.copyOf(result);
    }

    private static LinkedHashMap<String, Tag> indexedByAddress(List<Tag> entries, String source) throws IOException {
        LinkedHashMap<String, Tag> result = new LinkedHashMap<>();
        for (Tag entry : entries) {
            String address = address(entry);
            if (result.putIfAbsent(address, entry) != null) {
                throw new IOException(source + " servers.dat 包含重复服务器地址: " + address);
            }
        }
        return result;
    }

    private static String address(Tag entry) throws IOException {
        String address = stringValue(compound(entry, "服务器条目"), "ip").strip().toLowerCase(Locale.ROOT);
        if (address.isEmpty()) {
            throw new IOException("servers.dat 包含空服务器地址");
        }
        return address;
    }

    private static List<Tag> serverTags(Document document) throws IOException {
        Tag servers = document.root().get("servers");
        if (servers == null || servers.type() != TAG_LIST) {
            throw new IOException("servers.dat 缺少 servers List");
        }
        ListValue list = (ListValue) servers.value();
        if (list.elementType() != TAG_COMPOUND) {
            throw new IOException("servers.dat 的 servers List 不是 Compound 列表");
        }
        return list.values();
    }

    private static Compound compound(Tag tag, String label) throws IOException {
        if (tag.type() != TAG_COMPOUND) {
            throw new IOException(label + "不是 Compound");
        }
        return (Compound) tag.value();
    }

    private static String stringValue(Compound compound, String key) throws IOException {
        Tag tag = compound.get(key);
        if (tag == null || tag.type() != TAG_STRING) {
            throw new IOException("服务器条目缺少字符串字段 " + key);
        }
        return (String) tag.value();
    }

    private static Compound readCompound(DataInputStream input, int depth, Limits limits) throws IOException {
        checkDepth(depth);
        Compound result = new Compound();
        while (true) {
            byte type = input.readByte();
            if (type == TAG_END) {
                return result;
            }
            validateType(type);
            limits.tag();
            String name = input.readUTF();
            if (result.putIfAbsent(name, readPayload(input, type, depth + 1, limits)) != null) {
                throw new IOException("NBT Compound 包含重复字段: " + name);
            }
        }
    }

    private static Tag readPayload(DataInputStream input, byte type, int depth, Limits limits) throws IOException {
        checkDepth(depth);
        Object value = switch (type) {
            case TAG_BYTE -> input.readByte();
            case TAG_SHORT -> input.readShort();
            case TAG_INT -> input.readInt();
            case TAG_LONG -> input.readLong();
            case TAG_FLOAT -> input.readFloat();
            case TAG_DOUBLE -> input.readDouble();
            case TAG_BYTE_ARRAY -> {
                int length = checkedLength(input.readInt(), "ByteArray");
                byte[] bytes = new byte[length];
                input.readFully(bytes);
                yield bytes;
            }
            case TAG_STRING -> input.readUTF();
            case TAG_LIST -> {
                byte elementType = input.readByte();
                validateListType(elementType);
                int length = checkedLength(input.readInt(), "List");
                List<Tag> values = new ArrayList<>(length);
                for (int index = 0; index < length; index++) {
                    limits.tag();
                    values.add(readPayload(input, elementType, depth + 1, limits));
                }
                yield new ListValue(elementType, List.copyOf(values));
            }
            case TAG_COMPOUND -> readCompound(input, depth + 1, limits);
            case TAG_INT_ARRAY -> {
                int length = checkedLength(input.readInt(), "IntArray");
                int[] values = new int[length];
                for (int index = 0; index < length; index++) {
                    values[index] = input.readInt();
                }
                yield values;
            }
            case TAG_LONG_ARRAY -> {
                int length = checkedLength(input.readInt(), "LongArray");
                long[] values = new long[length];
                for (int index = 0; index < length; index++) {
                    values[index] = input.readLong();
                }
                yield values;
            }
            default -> throw new IOException("不支持的 NBT 标签类型: " + type);
        };
        return new Tag(type, value);
    }

    private static void writeCompound(DataOutputStream output, Compound compound, int depth) throws IOException {
        checkDepth(depth);
        for (Map.Entry<String, Tag> entry : compound.entrySet()) {
            output.writeByte(entry.getValue().type());
            output.writeUTF(entry.getKey());
            writePayload(output, entry.getValue(), depth + 1);
        }
        output.writeByte(TAG_END);
    }

    private static void writePayload(DataOutputStream output, Tag tag, int depth) throws IOException {
        checkDepth(depth);
        switch (tag.type()) {
            case TAG_BYTE -> output.writeByte((Byte) tag.value());
            case TAG_SHORT -> output.writeShort((Short) tag.value());
            case TAG_INT -> output.writeInt((Integer) tag.value());
            case TAG_LONG -> output.writeLong((Long) tag.value());
            case TAG_FLOAT -> output.writeFloat((Float) tag.value());
            case TAG_DOUBLE -> output.writeDouble((Double) tag.value());
            case TAG_BYTE_ARRAY -> {
                byte[] values = (byte[]) tag.value();
                output.writeInt(values.length);
                output.write(values);
            }
            case TAG_STRING -> output.writeUTF((String) tag.value());
            case TAG_LIST -> {
                ListValue list = (ListValue) tag.value();
                output.writeByte(list.elementType());
                output.writeInt(list.values().size());
                for (Tag value : list.values()) {
                    if (value.type() != list.elementType()) {
                        throw new IOException("NBT List 内元素类型不一致");
                    }
                    writePayload(output, value, depth + 1);
                }
            }
            case TAG_COMPOUND -> writeCompound(output, (Compound) tag.value(), depth + 1);
            case TAG_INT_ARRAY -> {
                int[] values = (int[]) tag.value();
                output.writeInt(values.length);
                for (int value : values) {
                    output.writeInt(value);
                }
            }
            case TAG_LONG_ARRAY -> {
                long[] values = (long[]) tag.value();
                output.writeInt(values.length);
                for (long value : values) {
                    output.writeLong(value);
                }
            }
            default -> throw new IOException("不能写入的 NBT 标签类型: " + tag.type());
        }
    }

    private static int checkedLength(int length, String type) throws IOException {
        if (length < 0 || length > MAX_COLLECTION_LENGTH) {
            throw new IOException("NBT " + type + " 长度超出限制: " + length);
        }
        return length;
    }

    private static void checkDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT 嵌套深度超过限制 " + MAX_DEPTH);
        }
    }

    private static void validateType(byte type) throws IOException {
        if (type < TAG_BYTE || type > TAG_LONG_ARRAY) {
            throw new IOException("无效的 NBT 标签类型: " + type);
        }
    }

    private static void validateListType(byte type) throws IOException {
        if (type == TAG_END) {
            return;
        }
        validateType(type);
    }

    record Document(String rootName, Compound root, boolean compressed) {
    }

    record ServerInfo(String name, String address) {
    }

    private record Tag(byte type, Object value) {
        private Tag {
            if (type == TAG_BYTE_ARRAY) {
                value = Arrays.copyOf((byte[]) value, ((byte[]) value).length);
            } else if (type == TAG_INT_ARRAY) {
                value = Arrays.copyOf((int[]) value, ((int[]) value).length);
            } else if (type == TAG_LONG_ARRAY) {
                value = Arrays.copyOf((long[]) value, ((long[]) value).length);
            }
        }
    }

    private record ListValue(byte elementType, List<Tag> values) {
    }

    private static final class Compound extends LinkedHashMap<String, Tag> {
    }

    private static final class Limits {
        private int tags;

        void tag() throws IOException {
            tags++;
            if (tags > MAX_TAGS) {
                throw new IOException("NBT 标签数量超过限制 " + MAX_TAGS);
            }
        }
    }
}

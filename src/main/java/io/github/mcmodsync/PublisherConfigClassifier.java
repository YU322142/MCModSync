package io.github.mcmodsync;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies full configuration files without assuming that every config file is publisher-authoritative. */
final class PublisherConfigClassifier {
    enum Action {
        ADDITIVE,
        FIRST_INSTALL,
        KEY_LEVEL_ONLY
    }

    record Decision(Action action, String reason) {
    }

    private static final Pattern KEY_PATTERN = Pattern.compile(
            "(?im)^\\s*[\\\"']?([a-z0-9_.-]{2,96})[\\\"']?\\s*[:=]");
    private static final Set<String> PERSONAL_PATH_WORDS = Set.of(
            "client", "ui", "hud", "overlay", "render", "rendering", "graphics", "video",
            "audio", "sound", "controls", "keybinds", "minimap", "waypoints", "tooltip");
    private static final Set<String> PERSONAL_KEY_WORDS = Set.of(
            "volume", "music", "sound", "audio", "keybind", "keybinding", "keymapping", "control",
            "fullscreen", "resolution", "window", "gui", "hud", "overlay", "tooltip", "font", "fov",
            "sensitivity", "renderdistance", "render_distance", "particle", "shader", "language", "locale",
            "minimap", "waypoint", "opacity", "uiscale", "ui_scale");
    private static final Set<String> GAMEPLAY_KEY_WORDS = Set.of(
            "recipe", "balance", "damage", "speed", "cooldown", "spawn", "generation", "worldgen",
            "difficulty", "capacity", "limit", "permission", "command", "server", "common", "chance",
            "rate", "multiplier", "cost", "duration", "mob", "entity", "block", "item", "fluid");
    private static final Set<String> SELF_REWRITING_PERSONAL_FILES = Set.of(
            "config/fabric/indigo-renderer.properties",
            "config/iris.properties");
    private static final Set<String> SELF_REWRITING_MIXED_FILES = Set.of(
            "config/packetfixer.properties");

    private PublisherConfigClassifier() {
    }

    static Decision classify(String relative, byte[] bytes) {
        String normalized = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.startsWith("defaultconfigs/")) {
            return new Decision(Action.ADDITIVE, "defaultconfigs 作为玩法默认配置追加/更新");
        }
        if (normalized.startsWith("configureddefaults/")) {
            return new Decision(Action.FIRST_INSTALL, "configureddefaults 仅首装写入，保留玩家后续修改");
        }
        if (!normalized.startsWith("config/")) {
            return new Decision(Action.ADDITIVE, "普通受管内容");
        }

        if (SELF_REWRITING_PERSONAL_FILES.contains(normalized)) {
            return new Decision(Action.FIRST_INSTALL,
                    "该客户端配置会在每次启动时重写时间戳/序列化内容，仅首装写入以避免更新重启循环");
        }
        if (SELF_REWRITING_MIXED_FILES.contains(normalized)) {
            return new Decision(Action.KEY_LEVEL_ONLY,
                    "该配置会在每次启动时重写整文件；需要统一的值必须改用配置项级 OTA");
        }

        if (normalized.startsWith("config/fancymenu/")) {
            String fancyMenuPath = normalized.substring("config/fancymenu/".length());
            if (fancyMenuPath.startsWith("assets/") || fancyMenuPath.startsWith("customization/")
                    || fancyMenuPath.startsWith("ui_themes/")
                    || fancyMenuPath.equals("custom_gui_screens.txt")
                    || fancyMenuPath.equals("customizablemenus.txt")
                    || fancyMenuPath.equals("legacy_checklist.txt")) {
                return new Decision(Action.ADDITIVE, "FancyMenu 整合包布局、素材或主题，作为 UI 内容同步");
            }
            if (fancyMenuPath.equals("options.txt")) {
                return new Decision(Action.KEY_LEVEL_ONLY,
                        "FancyMenu options.txt 同时包含整合包行为和玩家界面偏好，请使用配置项级 OTA");
            }
            if (fancyMenuPath.equals("user_variables.db") || fancyMenuPath.startsWith("layout_editor/")) {
                return new Decision(Action.FIRST_INSTALL, "FancyMenu 用户变量或编辑器状态，仅首装写入");
            }
        }

        String tail = normalized.substring("config/".length());
        if (pathLooksPersonal(tail)) {
            return new Decision(Action.FIRST_INSTALL, "识别为客户端画面/声音/按键/UI 配置，仅首装写入");
        }

        KeySignals signals = inspectKeys(bytes);
        if (signals.personal() && signals.gameplay()) {
            return new Decision(Action.KEY_LEVEL_ONLY,
                    "同一文件同时包含玩法一致性键与玩家个人设置，禁止整文件覆盖；请使用配置项级 OTA");
        }
        if (signals.personal()) {
            return new Decision(Action.FIRST_INSTALL, "内容仅识别到玩家个人设置，仅首装写入");
        }
        return new Decision(Action.ADDITIVE, "未识别到玩家个人设置，作为玩法配置追加/更新");
    }

    private static boolean pathLooksPersonal(String tail) {
        String[] words = tail.replace('/', '.').split("[._-]");
        for (String word : words) {
            if (PERSONAL_PATH_WORDS.contains(word)) return true;
        }
        return tail.startsWith("xaero") || tail.startsWith("journeymap")
                || tail.startsWith("iris") || tail.startsWith("oculus")
                || tail.startsWith("sodium") || tail.startsWith("embeddium");
    }

    private static KeySignals inspectKeys(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new KeySignals(false, false);
        boolean personal = false;
        boolean gameplay = false;
        int start = 0;
        final int window = 1024 * 1024;
        final int overlap = 512;
        while (start < bytes.length && !(personal && gameplay)) {
            int end = Math.min(bytes.length, start + window);
            String text = new String(bytes, start, end - start, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            Matcher matcher = KEY_PATTERN.matcher(text);
            while (matcher.find()) {
                String key = matcher.group(1);
                personal |= containsWord(key, PERSONAL_KEY_WORDS);
                gameplay |= containsWord(key, GAMEPLAY_KEY_WORDS);
                if (personal && gameplay) break;
            }
            if (end == bytes.length) break;
            start = end - overlap;
        }
        return new KeySignals(personal, gameplay);
    }

    private static boolean containsWord(String key, Set<String> words) {
        String compact = key.replace("-", "_");
        for (String word : words) {
            if (compact.equals(word) || compact.startsWith(word + "_") || compact.endsWith("_" + word)
                    || compact.contains("_" + word + "_") || compact.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private record KeySignals(boolean personal, boolean gameplay) {
    }
}

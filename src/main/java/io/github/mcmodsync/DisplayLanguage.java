package io.github.mcmodsync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

enum DisplayLanguage {
    ZH_CN,
    EN_US;

    static DisplayLanguage detect(Path gameDirectory) {
        String configured = System.getProperty("modsync.language", "").strip();
        if (configured.isBlank() && gameDirectory != null) {
            configured = readConfiguredLanguage(gameDirectory);
        }
        if (!configured.isBlank() && !configured.equalsIgnoreCase("auto")) {
            return parse(configured);
        }
        if (gameDirectory != null) {
            String minecraft = readMinecraftLanguage(gameDirectory.resolve("options.txt"));
            if (!minecraft.isBlank()) {
                return parse(minecraft);
            }
        }
        return fromLocale(Locale.getDefault());
    }

    static DisplayLanguage fromLocale(Locale locale) {
        return locale != null && locale.getLanguage().equalsIgnoreCase("zh") ? ZH_CN : EN_US;
    }

    static DisplayLanguage parse(String value) {
        if (value == null) {
            return EN_US;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "zh", "zh_cn", "zh_hans", "chinese", "中文" -> ZH_CN;
            case "en", "en_us", "en_gb", "english" -> EN_US;
            default -> fromLocale(Locale.getDefault());
        };
    }

    boolean chinese() {
        return this == ZH_CN;
    }

    String text(String chinese, String english) {
        return this == ZH_CN ? chinese : english;
    }

    private static String readConfiguredLanguage(Path gameDirectory) {
        Path propertiesPath = gameDirectory.resolve("modsync.properties");
        if (!Files.isRegularFile(propertiesPath)) {
            return "";
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesPath)) {
            properties.load(input);
            return properties.getProperty("language", "").strip();
        } catch (IOException exception) {
            return "";
        }
    }

    private static String readMinecraftLanguage(Path optionsPath) {
        if (!Files.isRegularFile(optionsPath)) {
            return "";
        }
        try {
            for (String line : Files.readAllLines(optionsPath, StandardCharsets.UTF_8)) {
                if (line.startsWith("lang:")) {
                    return line.substring("lang:".length()).strip();
                }
            }
        } catch (IOException exception) {
            return "";
        }
        return "";
    }
}

package io.github.mcmodsync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects the operating-system UI locale without trusting launcher-overridden JVM language flags. */
final class MachineLocale {
    private static final String OVERRIDE_PROPERTY = "mcsync.machineLocale";
    private static final Pattern WINDOWS_LOCALE_NAME = Pattern.compile(
            "(?im)^\\s*LocaleName\\s+REG_\\w+\\s+([^\\s]+)\\s*$");

    private MachineLocale() {
    }

    static Locale detect() {
        Locale explicit = parse(System.getProperty(OVERRIDE_PROPERTY, ""));
        if (explicit != null) return explicit;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Locale windows = detectWindowsUserLocale();
            if (windows != null) return windows;
        }
        Locale environment = parse(firstNonBlank(
                System.getenv("LC_ALL"), System.getenv("LC_MESSAGES"), System.getenv("LANG")));
        return environment != null ? environment : Locale.getDefault(Locale.Category.DISPLAY);
    }

    static Locale parseWindowsLocaleName(String output) {
        if (output == null) return null;
        Matcher matcher = WINDOWS_LOCALE_NAME.matcher(output);
        return matcher.find() ? parse(matcher.group(1)) : null;
    }

    private static Locale detectWindowsUserLocale() {
        String systemRoot = System.getenv("SystemRoot");
        String executable = systemRoot == null || systemRoot.isBlank()
                ? "reg.exe" : systemRoot + "\\System32\\reg.exe";
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "query",
                    "HKCU\\Control Panel\\International", "/v", "LocaleName")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) return null;
            return parseWindowsLocaleName(new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static Locale parse(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        int encoding = normalized.indexOf('.');
        if (encoding >= 0) normalized = normalized.substring(0, encoding);
        int modifier = normalized.indexOf('@');
        if (modifier >= 0) normalized = normalized.substring(0, modifier);
        normalized = normalized.replace('_', '-');
        if (normalized.isBlank() || normalized.equalsIgnoreCase("C")
                || normalized.equalsIgnoreCase("POSIX")) return null;
        Locale locale = Locale.forLanguageTag(normalized);
        return locale.getLanguage().isBlank() ? null : locale;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
}

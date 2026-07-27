package io.github.mcmodsync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-graphical progress channel for mobile launchers and headless helpers.
 * Always mirrors progress into stdout logs and {@code .modsync/ui-status.txt}.
 */
final class SyncStatusReporter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path statusFile;
    private final Path progressLogFile;
    private final AtomicLong lastProgressLogMs = new AtomicLong();
    private final AtomicInteger lastLoggedTotalPermille = new AtomicInteger(-1);
    private final AtomicReference<String> lastLoggedFileKey = new AtomicReference<>("");
    private final AtomicLong lastLoggedFileBytes = new AtomicLong(-1);
    private volatile String phase = "准备中";
    private volatile String detail = "";
    private volatile String plan = "";
    private volatile int totalPermille = -1;
    private volatile String mode = "unknown";
    private volatile String environmentSummary = "";

    SyncStatusReporter(Path gameDirectory) {
        Path directory = gameDirectory == null
                ? null
                : gameDirectory.toAbsolutePath().normalize().resolve(".modsync");
        this.statusFile = directory == null ? null : directory.resolve("ui-status.txt");
        this.progressLogFile = directory == null ? null : directory.resolve("progress.log");
    }

    void setMode(String mode) {
        this.mode = mode == null || mode.isBlank() ? "unknown" : mode;
        flushStatus();
    }

    void setEnvironment(RuntimeEnvironment environment) {
        if (environment == null) {
            return;
        }
        this.environmentSummary = environment.summaryLine();
        logLine("环境识别: " + environment.summaryLine());
        for (String line : environment.detailedReport().split("\\R")) {
            if (!line.isBlank()) {
                appendProgressLog("ENV " + line);
            }
        }
        flushStatus();
    }

    void phase(String phase, String detail) {
        this.phase = Objects.requireNonNullElse(phase, "");
        this.detail = Objects.requireNonNullElse(detail, "");
        flushStatus();
        logLine(this.phase + (this.detail.isBlank() ? "" : " | " + this.detail));
    }

    void plan(String planText) {
        this.plan = Objects.requireNonNullElse(planText, "");
        flushStatus();
    }

    void progress(SyncObserver.DownloadProgress progress) {
        if (progress == null) {
            return;
        }
        this.totalPermille = Math.max(0, Math.min(1000, progress.totalPermille()));
        String filePart = progress.fileTotalBytes() > 0
                ? formatBytes(progress.fileDownloadedBytes()) + " / " + formatBytes(progress.fileTotalBytes())
                        + " (" + percent(progress.fileDownloadedBytes(), progress.fileTotalBytes()) + ")"
                : "已下载 " + formatBytes(progress.fileDownloadedBytes());
        String totalPart = progress.totalBytes() > 0 && progress.totalDownloadedBytes() >= 0
                ? formatBytes(progress.totalDownloadedBytes()) + " / " + formatBytes(progress.totalBytes())
                        + " (" + percent(progress.totalDownloadedBytes(), progress.totalBytes()) + ")"
                : String.format(Locale.ROOT, "%.1f%%", this.totalPermille / 10.0);
        this.detail = "下载 [" + progress.fileIndex() + "/" + progress.fileCount() + "] "
                + progress.fileName() + " " + filePart + " | 总进度 " + totalPart;
        this.phase = "正在下载";
        flushStatus();

        String fileKey = progress.fileIndex() + ":" + progress.fileName();
        String previousFileKey = lastLoggedFileKey.get();
        boolean fileChanged = !fileKey.equals(previousFileKey);
        long previousBytes = lastLoggedFileBytes.get();
        long now = System.currentTimeMillis();
        long previousLogMs = lastProgressLogMs.get();
        int previousPermille = lastLoggedTotalPermille.get();

        boolean byteMilestone = progress.fileTotalBytes() > 0
                && (previousBytes < 0
                        || progress.fileDownloadedBytes() - previousBytes >= Math.max(256 * 1024L, progress.fileTotalBytes() / 10)
                        || progress.fileDownloadedBytes() >= progress.fileTotalBytes());
        boolean timeMilestone = now - previousLogMs >= 1500L;
        boolean percentMilestone = previousPermille < 0
                || this.totalPermille - previousPermille >= 50
                || this.totalPermille >= 1000
                || progress.fileDownloadedBytes() == 0;

        if (fileChanged || byteMilestone || timeMilestone || percentMilestone) {
            if (lastProgressLogMs.compareAndSet(previousLogMs, now)
                    || fileChanged
                    || this.totalPermille >= 1000) {
                lastProgressLogMs.set(now);
                lastLoggedTotalPermille.set(this.totalPermille);
                lastLoggedFileKey.set(fileKey);
                lastLoggedFileBytes.set(progress.fileDownloadedBytes());
                // Stable token makes launcher log scrapers easy to write.
                logLine("PROGRESS file=" + progress.fileIndex() + "/" + progress.fileCount()
                        + " name=" + progress.fileName()
                        + " fileBytes=" + progress.fileDownloadedBytes()
                        + (progress.fileTotalBytes() > 0 ? "/" + progress.fileTotalBytes() : "")
                        + " total=" + String.format(Locale.ROOT, "%.1f%%", this.totalPermille / 10.0)
                        + " detail=" + filePart);
            }
        }
    }

    void completed(int downloaded, int quarantined, int unchanged, boolean restartRequired) {
        this.totalPermille = 1000;
        this.phase = restartRequired ? "更新完成，请再次启动" : "更新完成";
        this.detail = "下载/替换 " + downloaded + "，移入备份 " + quarantined + "，无需更改 " + unchanged;
        if (restartRequired) {
            this.plan = (this.plan.isBlank() ? "" : this.plan + "\n\n")
                    + "同步已在无弹窗模式下完成。请关闭任何残留的游戏/启动器错误窗口后，再点击一次启动。";
        }
        flushStatus();
        logLine("PROGRESS complete downloaded=" + downloaded
                + " quarantined=" + quarantined
                + " unchanged=" + unchanged
                + " restartRequired=" + restartRequired);
        logLine(this.phase + " | " + this.detail);
    }

    void failed(String message) {
        this.phase = "同步失败";
        this.detail = Objects.requireNonNullElse(message, "未知错误");
        flushStatus();
        logLine("PROGRESS failed message=" + this.detail);
        logLine("失败: " + this.detail);
    }

    private void flushStatus() {
        if (statusFile == null) {
            return;
        }
        try {
            Files.createDirectories(statusFile.getParent());
            String body = "time=" + TIME.format(LocalDateTime.now()) + "\n"
                    + "mode=" + mode + "\n"
                    + "environment=" + escape(environmentSummary) + "\n"
                    + "phase=" + escape(phase) + "\n"
                    + "detail=" + escape(detail) + "\n"
                    + "progressPermille=" + totalPermille + "\n"
                    + "plan<<EOF\n"
                    + plan + "\n"
                    + "EOF\n";
            Files.writeString(
                    statusFile,
                    body,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException exception) {
            System.err.println("[MCModSync] 无法写入 ui-status.txt: " + exception.getMessage());
        }
    }

    private void appendProgressLog(String message) {
        if (progressLogFile == null) {
            return;
        }
        try {
            Files.createDirectories(progressLogFile.getParent());
            Files.writeString(
                    progressLogFile,
                    TIME.format(LocalDateTime.now()) + " " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best-effort secondary log; stdout remains authoritative.
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private void logLine(String message) {
        String line = "[MCModSync UI " + TIME.format(LocalDateTime.now()) + "] " + message;
        System.out.println(line);
        appendProgressLog(message);
    }

    private static String percent(long current, long total) {
        if (total <= 0) {
            return "?%";
        }
        return String.format(Locale.ROOT, "%.1f%%", Math.min(100.0, current * 100.0 / total));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}

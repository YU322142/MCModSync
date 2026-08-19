package io.github.mcmodsync;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Shared startup policy for Fabric's PreLaunch callback and NeoForge's
 * client-side @Mod constructor.
 */
final class PortablePreLaunchEntrypoint {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static InstanceGuard instanceGuard;
    private static volatile DisplayLanguage language = DisplayLanguage.detect(null);

    private PortablePreLaunchEntrypoint() {
    }

    static void run(String loaderName, GameDirectoryLocator locator) {
        if (Boolean.getBoolean("modsync.agent.active")) {
            log(language, "已由 -javaagent 完成启动前校验，" + loaderName + " 入口不再重复执行",
                    "Pre-launch verification was completed by -javaagent; the "
                            + loaderName + " entrypoint will not repeat it");
            return;
        }

        log(language, "MCSync " + BuildInfo.VERSION + " " + loaderName + " 便携模式校验开始",
                "MCSync " + BuildInfo.VERSION + " " + loaderName + " portable-mode verification started");
        try {
            Path gameDirectory = locator.locate().toAbsolutePath().normalize();
            // Pin this property before loading configuration so a stale test or
            // launcher property cannot redirect a mobile in-process update.
            System.setProperty("modsync.gameDir", gameDirectory.toString());
            language = DisplayLanguage.detect(gameDirectory);
            log(language, "游戏目录: " + gameDirectory, "Game directory: " + gameDirectory);
            ManagedClientConfig.installFromBootstrapJar(gameDirectory,
                    message -> log(language, message));
            ModSyncConfig config = ModSyncConfig.fromEnvironment(null, gameDirectory);
            language = DisplayLanguage.detect(config.gameDirectory());
            System.clearProperty("modsync.managedConfigChanged");
            RuntimeEnvironment environment = RuntimeEnvironment.detect();
            if (environment.mobile()) {
                log(language, "手机端 Mod 清单: " + config.manifestUri(),
                        "Mobile mod catalog: " + config.manifestUri());
                log(language, "手机端资源包清单: " + config.resourcePackManifestUri(),
                        "Mobile resource-pack catalog: " + config.resourcePackManifestUri());
            }
            if (environment.mobile() || !environment.dialogsUsable()) {
                log(language, "运行环境: " + environment.summaryLine(),
                        "Runtime environment: " + environment.summaryLine());
            }
            instanceGuard = InstanceGuard.acquire(config.gameDirectory());
            Runtime.getRuntime().addShutdownHook(
                    new Thread(PortablePreLaunchEntrypoint::releaseGuard, "MCSync-lock-release"));

            if (shouldUpdateInProcess(environment)) {
                runMobileInProcessUpdate(loaderName, config);
                return;
            }

            UserNotifier notifier = new UserNotifier(true, config.gameDirectory());
            SyncProbeResult result = ModSyncCoordinator.probe(
                    config,
                    message -> log(language, message),
                    notifier);
            System.setProperty("modsync.status", result.status().name());
            log(language, loaderName + " 便携模式只读校验结束: " + result.status(),
                    loaderName + " portable read-only verification finished: " + result.status());

            if (result.status() == SyncProbeResult.Status.CHANGES_REQUIRED) {
                showHiddenCommitNotice(notifier, result);
                boolean helperStarted = PortableUpdateHelper.schedule(
                        config, message -> log(language, message), loaderName);
                System.err.println("[MCSync] RESTART_REQUIRED");
                if (helperStarted) {
                    log(language,
                            "游戏内校验阶段已完成；Minecraft 将正常退出，隐藏助手只负责原子提交，完成后请重新启动",
                            "The in-game verification phase completed; Minecraft will exit normally. The hidden helper only performs the atomic commit; launch again afterward");
                    exitProcess(0);
                }
                throw new RestartRequiredException(language.text(
                        "MCSync 检测到同步内容变化。本次 " + loaderName + " 启动已停止；"
                                + "辅助进程会在当前 Java 完全退出后自动下载并替换。"
                                + "请等待“更新完成”窗口，再回到启动器启动一次。",
                        "MCSync detected synchronized-content changes. This " + loaderName
                                + " launch was stopped; the helper will download and replace files after Java exits."
                                + " Wait for the update-complete window, then launch the instance again."));
            }
        } catch (InstanceGuard.AlreadyRunningException busy) {
            releaseGuard();
            System.err.println("[MCSync] STARTUP_CANCELLED_UPDATE_BUSY");
            log(language, "本次启动已安全取消：同步辅助进程仍在工作，或该实例已有 Minecraft 正在运行",
                    "This launch was cancelled safely: the sync helper is still working or Minecraft is already "
                            + "running for this instance");
            UserNotifier.showInstanceBusy();
            exitProcess(0);
        } catch (RestartRequiredException expected) {
            releaseGuard();
            throw expected;
        } catch (Throwable failure) {
            releaseGuard();
            System.err.println("[MCSync] STARTUP_BLOCKED");
            System.err.println("[MCSync] " + language.text(
                    "致命错误：无法保证同步内容完整，Minecraft 启动已中止。",
                    "Fatal error: synchronized content integrity cannot be guaranteed; Minecraft startup stopped."));
            failure.printStackTrace(System.err);
            UserNotifier.showFatalError(failure);
            exitProcess(0);
        }
    }

    private static void runMobileInProcessUpdate(String loaderName, ModSyncConfig config) throws Exception {
        log(language, "手机端模式：先在当前进程下载并禁用旧模组，完成后再退出并要求重新启动",
                "Mobile mode: downloading and disabling old mods in this process, then exiting for a restart");
        UserNotifier notifier = new UserNotifier(true, config.gameDirectory());
        SyncResult result = ModSyncCoordinator.synchronize(
                config, message -> log(language, message), notifier);
        System.setProperty("modsync.status", result.status().name());
        log(language, "手机端同步结束: " + result.status()
                        + " (下载/替换 " + result.downloaded()
                        + "，移入备份/禁用 " + result.quarantined()
                        + "，无需更改 " + result.unchanged() + ")",
                "Mobile synchronization finished: " + result.status()
                        + " (downloaded/replaced " + result.downloaded()
                        + ", moved to backup/disabled " + result.quarantined()
                        + ", unchanged " + result.unchanged() + ")");

        if (result.status() == SyncResult.Status.UPDATED) {
            System.err.println("[MCSync] RESTART_REQUIRED");
            log(language, "旧模组已禁用并移入备份，新文件已就绪。请重新启动游戏以加载更新后的 Mod。",
                    "Old mods were disabled and moved to backup; new files are ready. Restart to load updated mods.");
            releaseGuard();
            if (Boolean.getBoolean("modsync.disableProcessExit")) {
                throw new RestartRequiredException(language.text(
                        "MCSync 手机端已在当前进程完成下载并禁用旧模组。本次启动已停止，请重新启动游戏。",
                        "MCSync mobile synchronization completed in-process; restart is required."));
            }
            exitProcess(0);
        }

        log(language, "手机端无需退出重进: " + result.status(),
                "Mobile restart is not required: " + result.status());
    }

    private static boolean shouldUpdateInProcess(RuntimeEnvironment environment) {
        if (Boolean.getBoolean("modsync.forceDesktopHelper")) {
            return false;
        }
        if (Boolean.getBoolean("modsync.forceMobileInProcessUpdate")) {
            return true;
        }
        return environment.mobile();
    }

    private static void showHiddenCommitNotice(UserNotifier notifier, SyncProbeResult result) {
        int seconds = hiddenCommitNoticeSeconds();
        CommitDurationEstimate estimate = estimateHiddenCommitDuration(
                result.estimatedCommitFiles(), result.estimatedCommitBytes());
        String workload = language.text(
                "预计提交 " + result.estimatedCommitFiles() + " 个文件（"
                        + humanBytes(result.estimatedCommitBytes()) + "），通常耗时 "
                        + estimate.localizedChinese() + "；大量小文件或较慢磁盘可能更久。",
                "Estimated commit: " + result.estimatedCommitFiles() + " files ("
                        + humanBytes(result.estimatedCommitBytes()) + "), usually "
                        + estimate.localizedEnglish()
                        + "; many small files or a slower disk may take longer.");
        String detail = language.text(
                "下载和哈希校验已完成。" + workload
                        + " Minecraft 即将退出；请不要立刻再次启动，等待隐藏助手完成原子提交。",
                "Download and hash verification completed. " + workload
                        + " Minecraft will exit; do not relaunch until the hidden helper finishes its atomic commit.");
        notifier.phaseChanged(detail);
        MinecraftEarlyProgress.handoffToHiddenCommitHelper();
        for (int remaining = seconds; remaining > 0; remaining--) {
            String countdown = language.text(
                    "更新已校验，" + remaining + " 秒后退出；预计提交耗时 "
                            + estimate.localizedChinese() + "，完成前请勿再次启动",
                    "Update verified; exiting in " + remaining
                            + "s. Estimated commit " + estimate.localizedEnglish()
                            + "; do not relaunch until it finishes");
            MinecraftWindowStatus.update(countdown);
            MinecraftEarlyProgress.hiddenCommitCountdown(remaining, estimate.asciiRange());
            log(language, countdown);
            if (Boolean.getBoolean("modsync.disableHandoffNoticeDelay")) continue;
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    static int hiddenCommitNoticeSeconds() {
        int configured = Integer.getInteger("modsync.handoffNoticeSeconds", 3);
        return Math.max(1, Math.min(configured, 10));
    }

    static CommitDurationEstimate estimateHiddenCommitDuration(int files, long bytes) {
        int safeFiles = Math.max(files, 0);
        long safeBytes = Math.max(bytes, 0L);
        if (safeFiles == 0 && safeBytes == 0L) return new CommitDurationEstimate(5, 30);
        long minimum = 2L + ceilDiv(safeFiles, 600L) + ceilDiv(safeBytes, 512L * 1024L * 1024L);
        long maximum = 8L + ceilDiv(safeFiles, 150L) + ceilDiv(safeBytes, 64L * 1024L * 1024L);
        int roundedMinimum = roundDuration(Math.max(3L, minimum));
        int roundedMaximum = roundDuration(Math.max(maximum, roundedMinimum + 5L));
        return new CommitDurationEstimate(roundedMinimum, Math.min(roundedMaximum, 1800));
    }

    private static long ceilDiv(long value, long divisor) {
        if (value <= 0L) return 0L;
        return 1L + (value - 1L) / divisor;
    }

    private static int roundDuration(long seconds) {
        long quantum = seconds <= 60L ? 5L : seconds <= 300L ? 10L : 30L;
        long rounded = ceilDiv(seconds, quantum) * quantum;
        return (int) Math.min(rounded, 1800L);
    }

    private static String humanBytes(long bytes) {
        long safe = Math.max(bytes, 0L);
        if (safe < 1024L) return safe + " B";
        if (safe < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KiB", safe / 1024.0);
        if (safe < 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f MiB", safe / (1024.0 * 1024.0));
        }
        return String.format(java.util.Locale.ROOT, "%.2f GiB", safe / (1024.0 * 1024.0 * 1024.0));
    }

    record CommitDurationEstimate(int minimumSeconds, int maximumSeconds) {
        CommitDurationEstimate {
            minimumSeconds = Math.max(minimumSeconds, 1);
            maximumSeconds = Math.max(maximumSeconds, minimumSeconds);
        }

        String localizedChinese() {
            return "约 " + localizedSeconds(minimumSeconds, true) + "–"
                    + localizedSeconds(maximumSeconds, true);
        }

        String localizedEnglish() {
            return "about " + localizedSeconds(minimumSeconds, false) + "-"
                    + localizedSeconds(maximumSeconds, false);
        }

        String asciiRange() {
            return localizedSeconds(minimumSeconds, false) + "-" + localizedSeconds(maximumSeconds, false);
        }

        private static String localizedSeconds(int seconds, boolean chinese) {
            if (seconds < 60) return seconds + (chinese ? " 秒" : "s");
            int minutes = seconds / 60;
            int remainder = seconds % 60;
            if (remainder == 0) return minutes + (chinese ? " 分钟" : "m");
            return minutes + (chinese ? " 分 " : "m ") + remainder + (chinese ? " 秒" : "s");
        }
    }

    private static void exitProcess(int code) {
        if (Boolean.getBoolean("modsync.disableProcessExit")) {
            throw new RestartRequiredException(language.text(
                    "MCSync 请求退出进程 (code=" + code + ")，但测试模式禁用了 System.exit。",
                    "MCSync requested process exit (code=" + code + "), but test mode disabled System.exit."));
        }
        System.exit(code);
    }

    private static void log(DisplayLanguage currentLanguage, String chinese, String english) {
        log(currentLanguage.text(chinese, english));
    }

    private static void log(DisplayLanguage currentLanguage, String localizedMessage) {
        log(localizedMessage);
    }

    private static void log(String message) {
        System.out.println("[MCSync " + TIME.format(LocalDateTime.now()) + "] " + message);
    }

    static synchronized void releaseGuard() {
        if (instanceGuard == null) {
            return;
        }
        try {
            instanceGuard.close();
        } catch (Exception ignored) {
        } finally {
            instanceGuard = null;
        }
    }

    @FunctionalInterface
    interface GameDirectoryLocator {
        Path locate() throws Exception;
    }

    static final class RestartRequiredException extends RuntimeException {
        private RestartRequiredException(String message) {
            super(message);
        }
    }
}

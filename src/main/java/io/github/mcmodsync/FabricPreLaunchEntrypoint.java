package io.github.mcmodsync;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Portable fallback used when the JAR is installed as a normal Fabric mod.
 *
 * Desktop (Windows/BakaXL): Fabric may lock mod JARs, so this entrypoint only
 * probes and starts a helper that commits updates after the game JVM exits.
 *
 * Mobile (Zalith/Pojav/Android): separate helper windows are unusable and the
 * launcher experience prefers update-first-then-restart. Linux/Android file
 * locks usually allow in-process rename/replace, so mobile applies the sync
 * transaction immediately (old mods disabled/backed up), then exits and asks
 * the player to launch again.
 */
public final class FabricPreLaunchEntrypoint implements PreLaunchEntrypoint {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static InstanceGuard instanceGuard;

    @Override
    public void onPreLaunch() {
        if (Boolean.getBoolean("modsync.agent.active")) {
            log("已由 -javaagent 完成启动前校验，Fabric 入口不再重复执行");
            return;
        }

        log("MCModSync 1.8.0 Fabric 便携模式校验开始");
        try {
            Path gameDirectory = locateFabricGameDirectory();
            // Always pin the system property first so a leftover modsync.gameDir
            // from a previous launch/test cannot redirect mobile in-process sync.
            System.setProperty("modsync.gameDir", gameDirectory.toString());
            log("游戏目录: " + gameDirectory);
            ModSyncConfig config = ModSyncConfig.fromEnvironment(null, gameDirectory);
            RuntimeEnvironment environment = RuntimeEnvironment.detect();
            if (environment.mobile()) {
                log("手机端 Mod 清单: " + config.manifestUri());
                log("手机端资源包清单: " + config.resourcePackManifestUri());
            }
            if (environment.mobile() || !environment.dialogsUsable()) {
                log("运行环境: " + environment.summaryLine());
            }
            instanceGuard = InstanceGuard.acquire(config.gameDirectory());
            Runtime.getRuntime().addShutdownHook(
                    new Thread(FabricPreLaunchEntrypoint::releaseGuard, "MCModSync-lock-release"));

            if (shouldUpdateInProcess(environment)) {
                runMobileInProcessUpdate(config);
                return;
            }

            SyncProbeResult result = ModSyncCoordinator.probe(
                    config,
                    FabricPreLaunchEntrypoint::log,
                    new UserNotifier(true, config.gameDirectory()));
            System.setProperty("modsync.status", result.status().name());
            log("Fabric 便携模式只读校验结束: " + result.status());

            if (result.status() == SyncProbeResult.Status.CHANGES_REQUIRED) {
                boolean helperStarted = PortableUpdateHelper.schedule(config, FabricPreLaunchEntrypoint::log);
                System.err.println("[MCModSync] RESTART_REQUIRED");
                if (helperStarted) {
                    log("更新窗口已经启动；Minecraft 将正常退出，更新完成后请重新启动");
                    exitProcess(0);
                }
                throw new RestartRequiredException(
                        "MCModSync 检测到同步内容变化。本次 Fabric 启动已停止；辅助进程会在当前 Java 完全退出后"
                                + "自动下载并替换。请等待“更新完成”窗口，再回到 BakaXL 启动一次。");
            }
        } catch (InstanceGuard.AlreadyRunningException busy) {
            releaseGuard();
            System.err.println("[MCModSync] STARTUP_CANCELLED_UPDATE_BUSY");
            log("本次启动已安全取消：同步辅助进程仍在工作，或该实例已有 Minecraft 正在运行");
            UserNotifier.showInstanceBusy();
            exitProcess(0);
            return;
        } catch (RestartRequiredException expected) {
            throw expected;
        } catch (Throwable failure) {
            releaseGuard();
            System.err.println("[MCModSync] STARTUP_BLOCKED");
            System.err.println("[MCModSync] 致命错误：无法保证同步内容完整，Minecraft 启动已中止。");
            failure.printStackTrace(System.err);
            UserNotifier.showFatalError(failure);
            exitProcess(0);
            return;
        }
    }

    /**
     * Mobile path: download/replace/disable old mods in this process, then stop
     * the current launch so the next start loads the new set.
     */
    private static void runMobileInProcessUpdate(ModSyncConfig config) throws Exception {
        log("手机端模式：先在当前进程下载并禁用旧模组，完成后再退出并要求重新启动");
        UserNotifier notifier = new UserNotifier(true, config.gameDirectory());
        SyncResult result = ModSyncCoordinator.synchronize(config, FabricPreLaunchEntrypoint::log, notifier);
        System.setProperty("modsync.status", result.status().name());
        log("手机端同步结束: " + result.status()
                + " (下载/替换 " + result.downloaded()
                + "，移入备份/禁用 " + result.quarantined()
                + "，无需更改 " + result.unchanged() + ")");

        if (result.status() == SyncResult.Status.UPDATED) {
            System.err.println("[MCModSync] RESTART_REQUIRED");
            log("旧模组已禁用并移入备份，新文件已就绪。请重新启动游戏以加载更新后的 Mod。");
            releaseGuard();
            if (Boolean.getBoolean("modsync.disableProcessExit")) {
                throw new RestartRequiredException(
                        "MCModSync 手机端已在当前进程完成下载并禁用旧模组。本次启动已停止，请重新启动游戏。");
            }
            exitProcess(0);
            return;
        }

        // Offline or unchanged: allow normal launch.
        log("手机端无需退出重进: " + result.status());
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

    private static void exitProcess(int code) {
        if (Boolean.getBoolean("modsync.disableProcessExit")) {
            throw new RestartRequiredException(
                    "MCModSync 请求退出进程 (code=" + code + ")，但测试模式禁用了 System.exit。");
        }
        System.exit(code);
    }

    private static Path locateFabricGameDirectory() {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = loaderClass.getMethod("getInstance").invoke(null);
            Object gameDirectory = loaderClass.getMethod("getGameDir").invoke(loader);
            if (gameDirectory instanceof Path path) {
                return path.toAbsolutePath().normalize();
            }
            throw new IllegalStateException("FabricLoader.getGameDir() 未返回 Path");
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | IllegalAccessException
                | InvocationTargetException exception) {
            throw new IllegalStateException("无法从 Fabric Loader 取得游戏目录", exception);
        }
    }

    private static void log(String message) {
        System.out.println("[MCModSync " + TIME.format(LocalDateTime.now()) + "] " + message);
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

    private static final class RestartRequiredException extends RuntimeException {
        private RestartRequiredException(String message) {
            super(message);
        }
    }
}

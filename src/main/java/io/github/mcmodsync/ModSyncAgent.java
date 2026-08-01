package io.github.mcmodsync;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ModSyncAgent {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static InstanceGuard instanceGuard;

    private ModSyncAgent() {
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) {
        System.setProperty("modsync.agent.active", "true");
        log("MCModSync 1.8.3 启动前校验开始");
        try {
            Path bootstrapDirectory = ModSyncConfig.determineGameDirectory(agentArguments, null);
            ManagedClientConfig.installFromBootstrapJar(bootstrapDirectory, ModSyncAgent::log);
            ModSyncConfig config = ModSyncConfig.fromEnvironment(agentArguments);
            System.clearProperty("modsync.managedConfigChanged");
            System.setProperty("modsync.gameDir", config.gameDirectory().toString());
            log("游戏目录: " + config.gameDirectory());
            RuntimeEnvironment environment = RuntimeEnvironment.detect();
            if (environment.mobile() || !environment.dialogsUsable()) {
                log("运行环境: " + environment.summaryLine());
            }
            if (environment.mobile()) {
                log("手机端 Mod 清单: " + config.manifestUri());
            }
            instanceGuard = InstanceGuard.acquire(config.gameDirectory());
            Runtime.getRuntime().addShutdownHook(new Thread(ModSyncAgent::releaseGuard, "MCModSync-lock-release"));
            SyncResult result = ModSyncCoordinator.synchronize(
                    config, ModSyncAgent::log, new UserNotifier(false, config.gameDirectory()));
            System.setProperty("modsync.status", result.status().name());
            log("启动前校验结束: " + result.status());
            if (Boolean.getBoolean("modsync.managedConfigChanged")) {
                System.err.println("[MCModSync] RESTART_REQUIRED");
                log("服务器管理的客户端配置已更新；本次启动正常结束，请重新启动以使用新配置");
                releaseGuard();
                if (Boolean.getBoolean("modsync.disableProcessExit")) {
                    throw new RuntimeException("MCModSync 客户端配置已更新；测试模式禁用了正常退出");
                }
                System.exit(0);
            }
        } catch (Throwable failure) {
            System.err.println("[MCModSync] STARTUP_BLOCKED");
            System.err.println("[MCModSync] 致命错误：无法保证同步内容完整，Minecraft 启动已中止。");
            failure.printStackTrace(System.err);
            UserNotifier.showFatalError(failure);
            releaseGuard();
            if (Boolean.getBoolean("modsync.disableProcessExit")) {
                throw new RuntimeException("MCModSync 已阻止启动；测试模式禁用了正常退出", failure);
            }
            System.exit(0);
        }
    }

    private static void log(String message) {
        System.out.println("[MCModSync " + TIME.format(LocalDateTime.now()) + "] " + message);
    }

    private static synchronized void releaseGuard() {
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
}

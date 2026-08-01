package io.github.mcmodsync;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Runs the mutating transaction after the Fabric JVM has fully exited. This
 * avoids Windows locks held by Fabric's cached JAR file systems.
 */
public final class PortableUpdateHelper {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private PortableUpdateHelper() {
    }

    public static void main(String[] arguments) {
        System.setProperty("modsync.helperProcess", "true");
        try {
            HelperArguments parsed = HelperArguments.parse(arguments);
            System.setProperty("modsync.gameDir", parsed.config().gameDirectory().toString());
            RuntimeEnvironment environment = RuntimeEnvironment.detect();
            if (environment.mobile() || !environment.dialogsUsable()) {
                log("运行环境: " + environment.summaryLine());
                log("图形更新窗口: 不可用，改用日志与 .modsync/ui-status.txt / progress.log");
                if (environment.mobile()) {
                    log("已识别为手机端/移动启动器环境，下载进度将写入启动器日志");
                }
            } else {
                log("图形更新窗口: 可用，已请求显示并置顶");
            }
            UserNotifier notifier = new UserNotifier(true, parsed.config().gameDirectory());
            notifier.showWaitingForGameExit(parsed.parentPid());
            waitForParent(parsed.parentPid());
            notifier.phaseChanged("游戏进程已退出，正在读取云端清单……");
            runNow(parsed.config(), PortableUpdateHelper::log, notifier);
            if (!notifier.helperExitScheduled()) {
                System.exit(0);
            }
        } catch (Throwable failure) {
            System.err.println("[MCModSync Helper] UPDATE_FAILED");
            failure.printStackTrace(System.err);
            UserNotifier.showFatalError(failure);
            System.exit(1);
        }
    }

    static boolean schedule(ModSyncConfig config, Consumer<String> logger) throws IOException {
        if (Boolean.getBoolean("modsync.disableHelperLaunch")) {
            logger.accept("测试模式：已跳过外部更新辅助进程启动");
            return false;
        }

        Path selfJar = locateSelfJar();
        Path javaExecutable = locateJavaExecutable();
        Path stateDirectory = config.gameDirectory().resolve(".modsync");
        Path logPath = stateDirectory.resolve("helper.log");
        Files.createDirectories(logPath.getParent());
        Path helperJar = prepareHelperRuntimeCopy(selfJar, stateDirectory, logger);

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            command.add("-Djava.awt.headless=false");
        }
        if (Boolean.getBoolean("modsync.disableDialogs")) {
            command.add("-Dmodsync.disableDialogs=true");
        }
        if (Boolean.getBoolean("modsync.forceHeadless")) {
            command.add("-Dmodsync.forceHeadless=true");
        }
        if (Boolean.getBoolean("modsync.forceMobile")) {
            command.add("-Dmodsync.forceMobile=true");
        }
        RuntimeEnvironment parentEnvironment = RuntimeEnvironment.detect();
        if (parentEnvironment.mobile() && !Boolean.getBoolean("modsync.forceMobile")) {
            command.add("-Dmodsync.forceMobile=true");
        }
        if (!parentEnvironment.dialogsUsable() && !Boolean.getBoolean("modsync.disableDialogs")) {
            command.add("-Dmodsync.disableDialogs=true");
        }
        command.add("-cp");
        command.add(helperJar.toString());
        command.add(PortableUpdateHelper.class.getName());
        command.addAll(HelperArguments.forCurrentProcess(config).serialize());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
        Process process = builder.start();
        logger.accept("已启动退出后更新辅助进程，PID=" + process.pid() + "，日志: " + logPath);
        return true;
    }

    private static Path prepareHelperRuntimeCopy(
            Path selfJar,
            Path stateDirectory,
            Consumer<String> logger) throws IOException {
        Path helperDirectory = stateDirectory.resolve("helper-runtime");
        Files.createDirectories(helperDirectory);
        cleanupOldHelperCopies(helperDirectory, logger);

        Path helperJar = helperDirectory.resolve(
                "MCModSync-helper-" + ProcessHandle.current().pid() + "-" + System.nanoTime() + ".jar");
        Files.copy(selfJar, helperJar, StandardCopyOption.COPY_ATTRIBUTES);
        String sourceMd5 = Hashing.md5(selfJar);
        String copiedMd5 = Hashing.md5(helperJar);
        if (!sourceMd5.equals(copiedMd5)) {
            Files.deleteIfExists(helperJar);
            throw new IOException("更新辅助副本 MD5 校验失败");
        }
        logger.accept("已创建独立更新辅助副本；MCModSync 本体可在退出后安全替换");
        return helperJar;
    }

    private static void cleanupOldHelperCopies(Path helperDirectory, Consumer<String> logger) {
        try (var paths = Files.list(helperDirectory)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(item -> item.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    logger.accept("旧辅助副本暂时仍被占用，将在下次更新时重试清理: " + path.getFileName());
                }
            }
        } catch (IOException exception) {
            logger.accept("无法清理旧辅助副本，将继续创建本次副本: " + exception.getMessage());
        }
    }

    static SyncResult runNow(ModSyncConfig config, Consumer<String> logger)
            throws IOException, InterruptedException {
        return runNow(config, logger, new UserNotifier(true, config.gameDirectory()));
    }

    static SyncResult runNow(ModSyncConfig config, Consumer<String> logger, SyncObserver observer)
            throws IOException, InterruptedException {
        InstanceGuard guard = acquireGuardAfterParentExit(config.gameDirectory());
        try (guard) {
            logger.accept("Fabric 进程已退出，开始执行无占用更新");
            SyncResult result = ModSyncCoordinator.synchronize(config, logger, observer);
            logger.accept("退出后更新完成: " + result.status());
            return result;
        }
    }

    private static void waitForParent(long parentPid) throws IOException, InterruptedException {
        Optional<ProcessHandle> parent = ProcessHandle.of(parentPid);
        if (parent.isEmpty() || !parent.get().isAlive()) {
            return;
        }
        log("等待 Fabric 进程退出，PID=" + parentPid);
        try {
            parent.get().onExit().get();
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IOException("等待 Fabric 进程退出失败", exception.getCause());
        }
    }

    private static InstanceGuard acquireGuardAfterParentExit(Path gameDirectory)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= 40; attempt++) {
            try {
                return InstanceGuard.acquire(gameDirectory);
            } catch (IOException exception) {
                last = exception;
                Thread.sleep(250L);
            }
        }
        throw new IOException("Fabric 退出后仍无法取得客户端更新锁", last);
    }

    private static Path locateSelfJar() throws IOException {
        try {
            var codeSource = PortableUpdateHelper.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                Path location = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
                if (isJar(location)) {
                    return location;
                }
            }
        } catch (URISyntaxException exception) {
            throw new IOException("MCModSync JAR 路径格式无效", exception);
        }

        // Some custom class loaders omit CodeSource. Fall back to Fabric's
        // public mod-origin API without introducing another compile dependency.
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = loaderClass.getMethod("getInstance").invoke(null);
            Object optional = loaderClass.getMethod("getModContainer", String.class).invoke(loader, "mcmodsync");
            Object container = optional instanceof Optional<?> found ? found.orElse(null) : null;
            if (container != null) {
                Class<?> containerApi = Class.forName("net.fabricmc.loader.api.ModContainer");
                Object origin = containerApi.getMethod("getOrigin").invoke(container);
                Class<?> originApi = Class.forName("net.fabricmc.loader.api.metadata.ModOrigin");
                Object paths = originApi.getMethod("getPaths").invoke(origin);
                if (paths instanceof Iterable<?> iterable) {
                    for (Object item : iterable) {
                        if (item instanceof Path path) {
                            Path normalized = path.toAbsolutePath().normalize();
                            if (isJar(normalized)) {
                                return normalized;
                            }
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new IOException("无法通过 Fabric Loader 定位 MCModSync JAR", exception);
        }
        throw new IOException("无法定位正在运行的 MCModSync JAR");
    }

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase().endsWith(".jar");
    }

    private static Path locateJavaExecutable() throws IOException {
        String currentCommand = ProcessHandle.current().info().command().orElse("");
        if (!currentCommand.isBlank()) {
            Path current = Path.of(currentCommand).toAbsolutePath().normalize();
            if (Files.isRegularFile(current)) {
                return current;
            }
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        Path fallback = Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        windows ? "javaw.exe" : "java")
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(fallback)) {
            throw new IOException("找不到用于退出后更新的 Java: " + fallback);
        }
        return fallback;
    }

    private static void log(String message) {
        System.out.println("[MCModSync Helper " + TIME.format(LocalDateTime.now()) + "] " + message);
    }

    private record HelperArguments(long parentPid, ModSyncConfig config) {
        private static final int ARGUMENT_COUNT = 14;

        static HelperArguments forCurrentProcess(ModSyncConfig config) {
            return new HelperArguments(ProcessHandle.current().pid(), config);
        }

        List<String> serialize() {
            return List.of(
                    Long.toString(parentPid),
                    config.gameDirectory().toString(),
                    config.manifestUri().toASCIIString(),
                    config.resourcePackManifestUri().toASCIIString(),
                    config.serverListManifestUri().toASCIIString(),
                    Boolean.toString(config.syncResourcePacks()),
                    Boolean.toString(config.syncServerList()),
                    Boolean.toString(config.strict()),
                    Boolean.toString(config.requireManifest()),
                    Long.toString(config.connectTimeout().toMillis()),
                    Long.toString(config.requestTimeout().toMillis()),
                    Long.toString(config.maxManifestBytes()),
                    Long.toString(config.maxFileBytes()),
                    Integer.toString(config.fileOperationRetries()));
        }

        static HelperArguments parse(String[] arguments) {
            if (arguments.length != ARGUMENT_COUNT) {
                throw new IllegalArgumentException("退出后更新参数数量错误: " + arguments.length);
            }
            long parentPid = positiveLong(arguments[0], "parentPid");
            Path gameDirectory = Path.of(arguments[1]).toAbsolutePath().normalize();
            URI manifest = URI.create(arguments[2]);
            URI resourcePackManifest = URI.create(arguments[3]);
            URI serverListManifest = URI.create(arguments[4]);
            boolean syncResourcePacks = strictBoolean(arguments[5], "syncResourcePacks");
            boolean syncServerList = strictBoolean(arguments[6], "syncServerList");
            boolean strict = strictBoolean(arguments[7], "strict");
            boolean requireManifest = strictBoolean(arguments[8], "requireManifest");
            Duration connectTimeout = Duration.ofMillis(positiveLong(arguments[9], "connectTimeout"));
            Duration requestTimeout = Duration.ofMillis(positiveLong(arguments[10], "requestTimeout"));
            long maxManifestBytes = positiveLong(arguments[11], "maxManifestBytes");
            long maxFileBytes = positiveLong(arguments[12], "maxFileBytes");
            long retries = positiveLong(arguments[13], "fileOperationRetries");
            if (retries > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("fileOperationRetries 超出范围");
            }
            return new HelperArguments(
                    parentPid,
                    new ModSyncConfig(
                            manifest,
                            resourcePackManifest,
                            serverListManifest,
                            gameDirectory,
                            gameDirectory,
                            syncResourcePacks,
                            syncServerList,
                            strict,
                            requireManifest,
                            connectTimeout,
                            requestTimeout,
                            maxManifestBytes,
                            maxFileBytes,
                            (int) retries));
        }

        private static long positiveLong(String value, String name) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed <= 0) {
                    throw new IllegalArgumentException(name + " 必须为正整数");
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + " 必须为整数", exception);
            }
        }

        private static boolean strictBoolean(String value, String name) {
            if (value.equalsIgnoreCase("true")) {
                return true;
            }
            if (value.equalsIgnoreCase("false")) {
                return false;
            }
            throw new IllegalArgumentException(name + " 必须为 true 或 false");
        }
    }
}

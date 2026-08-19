package io.github.mcmodsync;

import java.lang.reflect.Method;

/** Publishes MCSync progress into NeoForge's existing early-loading window. */
final class MinecraftEarlyProgress {
    private static final Object LOCK = new Object();
    private static Object meter;
    private static Method setAbsolute;
    private static Method label;
    private static boolean unavailable;

    private MinecraftEarlyProgress() {
    }

    static void phase(String message) {
        if (!enabled()) return;
        invoke(() -> {
            ensureMeter();
            addMessage(message);
            label.invoke(meter, message);
        });
    }

    static void progress(SyncObserver.DownloadProgress progress) {
        if (!enabled()) return;
        invoke(() -> {
            ensureMeter();
            int value = Math.max(0, Math.min(1000, progress.totalPermille()));
            setAbsolute.invoke(meter, value);
            label.invoke(meter, progress.fileName() + "  " + (value / 10.0) + "%");
        });
    }

    static void completed(int downloaded, int quarantined, int unchanged, String message) {
        if (!enabled()) return;
        invoke(() -> {
            ensureMeter();
            setAbsolute.invoke(meter, 1000);
            label.invoke(meter, message);
            addMessage("MCSync: " + downloaded + " downloaded, " + quarantined
                    + " backed up, " + unchanged + " unchanged; restart required");
        });
    }

    static void failed(String message) {
        if (!enabled()) return;
        invoke(() -> addMessage("MCSync: " + message));
    }

    private static boolean enabled() {
        return !Boolean.getBoolean("modsync.helperProcess")
                && Boolean.getBoolean("modsync.inGameWindow")
                && !unavailable;
    }

    private static void ensureMeter() throws ReflectiveOperationException {
        if (meter != null) return;
        Class<?> manager = Class.forName("net.neoforged.fml.loading.progress.StartupNotificationManager");
        Class<?> meterType = Class.forName("net.neoforged.fml.loading.progress.ProgressMeter");
        meter = manager.getMethod("addProgressBar", String.class, int.class)
                .invoke(null, "MCSync", 1000);
        setAbsolute = meterType.getMethod("setAbsolute", int.class);
        label = meterType.getMethod("label", String.class);
    }

    private static void addMessage(String message) throws ReflectiveOperationException {
        Class<?> manager = Class.forName("net.neoforged.fml.loading.progress.StartupNotificationManager");
        manager.getMethod("addModMessage", String.class).invoke(null, message);
    }

    private static void invoke(ThrowingAction action) {
        synchronized (LOCK) {
            if (unavailable) return;
            try {
                action.run();
            } catch (Throwable ignored) {
                unavailable = true;
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}

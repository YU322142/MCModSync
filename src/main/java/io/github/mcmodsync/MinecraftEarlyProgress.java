package io.github.mcmodsync;

import java.lang.reflect.Method;

/** Publishes MCSync progress into NeoForge's existing early-loading window. */
final class MinecraftEarlyProgress {
    private static final Object LOCK = new Object();
    private static Object meter;
    private static Method setAbsolute;
    private static Method label;
    private static boolean unavailable;
    private static boolean availabilityReported;

    private MinecraftEarlyProgress() {
    }

    static void phase(String message) {
        if (!enabled()) return;
        invoke(() -> {
            ensureMeter();
            String visible = earlyWindowLabel(message, "Checking synchronized content");
            addMessage(visible);
            label.invoke(meter, visible);
        });
    }

    static void progress(SyncObserver.DownloadProgress progress) {
        if (!enabled()) return;
        invoke(() -> {
            ensureMeter();
            int value = Math.max(0, Math.min(1000, progress.totalPermille()));
            setAbsolute.invoke(meter, value);
            String file = earlyWindowLabel(progress.fileName(), "file");
            label.invoke(meter, "MCSync " + progress.fileIndex() + "/" + progress.fileCount()
                    + "  " + file + "  " + (value / 10.0) + "%");
        });
    }

    static void completed(int downloaded, int quarantined, int unchanged, String message) {
        if (!enabled()) return;
        invoke(() -> {
            ensureMeter();
            setAbsolute.invoke(meter, 1000);
            label.invoke(meter, "MCSync update prepared - restarting to apply");
            addMessage("MCSync: " + downloaded + " downloaded, " + quarantined
                    + " backed up, " + unchanged + " unchanged; restart required");
        });
    }

    static void handoffToHiddenCommitHelper() {
        if (!enabled()) return;
        invoke(() -> {
            ensureMeter();
            setAbsolute.invoke(meter, 1000);
            label.invoke(meter, "MCSync verified - restarting to apply safely");
            addMessage("MCSync: verification complete; restarting for atomic file replacement");
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
        // The early window only has room for a small number of bars. Put MCSync
        // first so a large modpack cannot push it below the visible area.
        meter = manager.getMethod("prependProgressBar", String.class, int.class)
                .invoke(null, "MCSync", 1000);
        setAbsolute = meterType.getMethod("setAbsolute", int.class);
        label = meterType.getMethod("label", String.class);
        if (!availabilityReported) {
            availabilityReported = true;
            System.out.println("[MCSync UI] NeoForge early-window progress attached");
        }
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
            } catch (Throwable failure) {
                unavailable = true;
                if (!availabilityReported) {
                    availabilityReported = true;
                    System.err.println("[MCSync UI] NeoForge early-window progress unavailable: "
                            + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                }
            }
        }
    }

    static String earlyWindowLabel(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        StringBuilder ascii = new StringBuilder(Math.min(value.length(), 96));
        boolean previousSpace = false;
        for (int index = 0; index < value.length() && ascii.length() < 96; index++) {
            char current = value.charAt(index);
            if (current >= 0x21 && current <= 0x7e) {
                ascii.append(current);
                previousSpace = false;
            } else if (Character.isWhitespace(current) && !previousSpace && !ascii.isEmpty()) {
                ascii.append(' ');
                previousSpace = true;
            }
        }
        String result = ascii.toString().strip();
        return result.isBlank() ? fallback : result;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}

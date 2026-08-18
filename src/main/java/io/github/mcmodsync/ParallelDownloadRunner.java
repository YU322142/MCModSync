package io.github.mcmodsync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ParallelDownloadRunner {
    private static final int MAX_THREADS = 8;
    private static final int TASK_RETRY_ATTEMPTS = 3;
    private static final long TASK_RETRY_DELAY_MILLIS = 400L;

    private ParallelDownloadRunner() {
    }

    static int threadCount(int taskCount) {
        return Math.max(1, Math.min(MAX_THREADS, taskCount));
    }

    static void run(int taskCount, IndexedTask task) throws IOException, InterruptedException {
        if (taskCount <= 0) {
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                threadCount(taskCount),
                runnable -> {
                    Thread thread = new Thread(runnable, "MCModSync-download");
                    thread.setDaemon(true);
                    return thread;
                });
        ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(executor);
        List<Future<Void>> futures = new ArrayList<>(taskCount);
        boolean completed = false;
        try {
            for (int index = 0; index < taskCount; index++) {
                int taskIndex = index;
                futures.add(completion.submit(() -> {
                    runWithRetries(task, taskIndex);
                    return null;
                }));
            }
            for (int finished = 0; finished < taskCount; finished++) {
                try {
                    completion.take().get();
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    if (cause instanceof InterruptedException interrupted) {
                        throw new IOException("并行下载任务被中断", interrupted);
                    }
                    throw new IOException("并行下载任务失败", cause);
                }
            }
            completed = true;
        } finally {
            if (!completed) {
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
            } else {
                executor.shutdown();
            }
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
    }

    private static void runWithRetries(IndexedTask task, int taskIndex) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= TASK_RETRY_ATTEMPTS; attempt++) {
            try {
                task.run(taskIndex);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception failure) {
                lastFailure = failure;
                if (attempt == TASK_RETRY_ATTEMPTS) {
                    break;
                }
                try {
                    Thread.sleep(TASK_RETRY_DELAY_MILLIS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw lastFailure == null
                ? new IOException("并行下载任务失败")
                : lastFailure;
    }

    @FunctionalInterface
    interface IndexedTask {
        void run(int index) throws Exception;
    }
}

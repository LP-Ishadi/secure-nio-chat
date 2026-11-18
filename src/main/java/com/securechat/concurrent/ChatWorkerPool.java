package com.securechat.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-size worker pool used for application-level message processing. */
public final class ChatWorkerPool implements Executor, AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private final ExecutorService executor;

    public ChatWorkerPool(int threadCount) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be positive");
        }

        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(
                    task,
                    "chat-worker-" + threadNumber.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newFixedThreadPool(threadCount, threadFactory);
    }

    @Override
    public void execute(Runnable task) {
        executor.execute(Objects.requireNonNull(task, "task must not be null"));
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(
                    SHUTDOWN_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
            )) {
                executor.shutdownNow();
                executor.awaitTermination(
                        SHUTDOWN_TIMEOUT.toMillis(),
                        TimeUnit.MILLISECONDS
                );
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

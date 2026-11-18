package com.securechat.concurrent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Runs one client's tasks sequentially on a shared multi-threaded executor. */
public final class SerialExecutor implements Executor, AutoCloseable {
    private final Executor backend;
    private final Deque<Runnable> tasks = new ArrayDeque<>();
    private Runnable activeTask;
    private boolean closed;

    public SerialExecutor(Executor backend) {
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
    }

    @Override
    public synchronized void execute(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        if (closed) {
            throw new RejectedExecutionException("serial executor is closed");
        }

        tasks.addLast(() -> {
            try {
                task.run();
            } finally {
                scheduleNext();
            }
        });

        if (activeTask == null) {
            scheduleNext();
        }
    }

    private synchronized void scheduleNext() {
        if (closed) {
            activeTask = null;
            tasks.clear();
            return;
        }

        activeTask = tasks.pollFirst();
        if (activeTask != null) {
            backend.execute(activeTask);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        tasks.clear();
    }
}

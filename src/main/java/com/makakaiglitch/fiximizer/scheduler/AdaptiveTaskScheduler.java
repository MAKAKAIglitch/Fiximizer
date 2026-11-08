package com.makakaiglitch.fiximizer.scheduler;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.profiler.SectionProfiler;

import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Priority scheduler for independent background tasks submitted by Fiximizer
 * and its compatibility modules. It never steals or moves Minecraft's main-thread work.
 */
public final class AdaptiveTaskScheduler {
    private static final int HARD_MAX_WORKERS = 4;
    private static final AtomicInteger QUEUED_TASKS = new AtomicInteger();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1,
            HARD_MAX_WORKERS,
            30L,
            TimeUnit.SECONDS,
            new PriorityBlockingQueue<>(32, Comparator
                    .comparingInt((Runnable task) -> ((PrioritizedTask<?>) task).queueOrder)
                    .thenComparingLong(task -> ((PrioritizedTask<?>) task).sequence)),
            new FiximizerThreadFactory()
    );

    private static volatile int targetWorkers = 1;

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private AdaptiveTaskScheduler() {
    }

    public static <T> CompletableFuture<T> submit(
            String taskName,
            TaskPriority priority,
            Callable<T> callable
    ) {
        if (taskName == null || taskName.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("A task name is required.")
            );
        }
        if (priority == null || callable == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Task priority and callable are required.")
            );
        }

        OptimizationProfile profile = OptimizationProfileManager.current();
        int pending = QUEUED_TASKS.incrementAndGet();
        if (pending > profile.queueCapacity()) {
            QUEUED_TASKS.decrementAndGet();
            return CompletableFuture.failedFuture(
                    new RejectedExecutionException(
                            "Fiximizer " + profile.id() + " queue is full (" + profile.queueCapacity() + ")."
                    )
            );
        }

        PrioritizedTask<T> task = new PrioritizedTask<>(
                taskName,
                profile.queueOrder(priority),
                SEQUENCE.getAndIncrement(),
                callable
        );

        try {
            EXECUTOR.execute(task);
        } catch (RuntimeException exception) {
            QUEUED_TASKS.updateAndGet(value -> Math.max(0, value - 1));
            task.completion.completeExceptionally(exception);
        }

        return task.completion;
    }

    /**
     * Adapt only Fiximizer's worker count to the current system CPU sample.
     * Running tasks are never interrupted when the pool is reduced.
     *
     * @param cpuLoad system CPU load in the range 0..1, or a negative value if unavailable
     */
    public static void adaptToCpuLoad(double cpuLoad) {
        OptimizationProfile profile = OptimizationProfileManager.current();
        int configuredMaximum = Math.min(
                HARD_MAX_WORKERS,
                Math.min(profile.maximumWorkers(), Math.max(1, FiximizerConfig.MAX_BACKGROUND_WORKERS.get()))
        );

        int desired = configuredMaximum;
        if (FiximizerConfig.ADAPTIVE_SCHEDULER_ENABLED.get()
                && Double.isFinite(cpuLoad)
                && cpuLoad >= 0.0) {
            if (cpuLoad >= profile.singleWorkerAtCpuLoad()) {
                desired = 1;
            } else if (cpuLoad >= profile.reduceWorkersAtCpuLoad()) {
                desired = Math.max(1, configuredMaximum - 1);
            }
        }

        desired = Math.min(HARD_MAX_WORKERS, Math.max(1, desired));
        if (desired != EXECUTOR.getCorePoolSize()) {
            EXECUTOR.setCorePoolSize(desired);
        }

        targetWorkers = desired;
    }

    public static SchedulerSnapshot snapshot() {
        OptimizationProfile profile = OptimizationProfileManager.current();
        int configuredMaximum = Math.min(
                HARD_MAX_WORKERS,
                Math.min(profile.maximumWorkers(), Math.max(1, FiximizerConfig.MAX_BACKGROUND_WORKERS.get()))
        );

        return new SchedulerSnapshot(
                EXECUTOR.getActiveCount(),
                QUEUED_TASKS.get(),
                EXECUTOR.getPoolSize(),
                targetWorkers,
                configuredMaximum
        );
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    private static final class PrioritizedTask<T> implements Runnable {
        private final String name;
        private final int queueOrder;
        private final long sequence;
        private final Callable<T> callable;
        private final CompletableFuture<T> completion = new CompletableFuture<>();

        private PrioritizedTask(
                String name,
                int queueOrder,
                long sequence,
                Callable<T> callable
        ) {
            this.name = name.length() > 72 ? name.substring(0, 72) : name;
            this.queueOrder = queueOrder;
            this.sequence = sequence;
            this.callable = callable;
        }

        @Override
        public void run() {
            QUEUED_TASKS.updateAndGet(value -> Math.max(0, value - 1));
            if (completion.isCancelled()) {
                return;
            }

            try (SectionProfiler.Scope ignored = SectionProfiler.begin("scheduler/" + name)) {
                completion.complete(callable.call());
            } catch (Exception exception) {
                completion.completeExceptionally(exception);
            } catch (LinkageError error) {
                completion.completeExceptionally(error);
            }
        }
    }

    private static final class FiximizerThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                    runnable,
                    "Fiximizer-Worker-" + threadNumber.incrementAndGet()
            );
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }
}

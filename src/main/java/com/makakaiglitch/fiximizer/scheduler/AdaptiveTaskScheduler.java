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
    private static volatile String scalingReason = "starting";

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
            SchedulerTelemetry.recordRejected();
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
            SchedulerTelemetry.recordRejected();
            task.completion.completeExceptionally(exception);
        }

        return task.completion;
    }

    /**
     * Adapt Fiximizer's worker count from system CPU load and observed queue pressure.
     * Only independent Fiximizer-owned background tasks are affected.
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
        SchedulerTelemetry.Snapshot telemetry = SchedulerTelemetry.snapshot();
        SchedulerLoadController.Decision decision = SchedulerLoadController.decide(
                FiximizerConfig.ADAPTIVE_SCHEDULER_ENABLED.get(),
                cpuLoad,
                EXECUTOR.getActiveCount(),
                QUEUED_TASKS.get(),
                EXECUTOR.getCorePoolSize(),
                configuredMaximum,
                telemetry.queueWaitP95Millis(),
                oldestQueuedAgeMillis(),
                profile
        );

        int desired = Math.min(HARD_MAX_WORKERS,
                Math.max(1, Math.min(configuredMaximum, decision.targetWorkers())));
        if (desired != EXECUTOR.getCorePoolSize()) {
            // Lowering the core size never interrupts a running task.
            EXECUTOR.setCorePoolSize(desired);
        }

        targetWorkers = desired;
        scalingReason = decision.reasonKey();
    }

    private static double oldestQueuedAgeMillis() {
        long now = System.nanoTime();
        long oldestWaitNanos = 0L;
        for (Runnable queued : EXECUTOR.getQueue()) {
            if (queued instanceof PrioritizedTask<?> task) {
                oldestWaitNanos = Math.max(
                        oldestWaitNanos,
                        Math.max(0L, now - task.submittedAtNanos)
                );
            }
        }
        return oldestWaitNanos / 1_000_000.0;
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
                configuredMaximum,
                SchedulerTelemetry.snapshot(),
                scalingReason
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
        private final long submittedAtNanos = System.nanoTime();
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
            long startedAtNanos = System.nanoTime();
            long queueWaitNanos = Math.max(0L, startedAtNanos - submittedAtNanos);
            if (completion.isCancelled()) {
                SchedulerTelemetry.recordTask(queueWaitNanos, 0L, false, true);
                return;
            }

            long executionStartedAtNanos = System.nanoTime();
            T result = null;
            Throwable failure = null;
            try (SectionProfiler.Scope ignored = SectionProfiler.begin("scheduler/" + name)) {
                result = callable.call();
            } catch (Exception exception) {
                failure = exception;
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } catch (LinkageError error) {
                failure = error;
            } finally {
                SchedulerTelemetry.recordTask(
                        queueWaitNanos,
                        Math.max(0L, System.nanoTime() - executionStartedAtNanos),
                        failure != null,
                        false
                );
            }

            if (failure == null) {
                completion.complete(result);
            } else {
                completion.completeExceptionally(failure);
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

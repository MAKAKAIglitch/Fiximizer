package com.makakaiglitch.fiximizer.scheduler;

import java.util.Arrays;

/**
 * Bounded aggregate timing for Fiximizer-managed background tasks.
 * The rolling window stores only the latest 128 completed/cancelled tasks.
 */
public final class SchedulerTelemetry {
    private static final int WINDOW_SIZE = 128;
    private static final long[] QUEUE_WAIT_NANOS = new long[WINDOW_SIZE];
    private static final long[] EXECUTION_NANOS = new long[WINDOW_SIZE];

    private static int nextIndex;
    private static int sampleCount;
    private static long finishedTasks;
    private static long failedTasks;
    private static long cancelledTasks;
    private static long rejectedTasks;
    private static final long SNAPSHOT_CACHE_NANOS = 500_000_000L;
    private static Snapshot cachedSnapshot;
    private static long lastSnapshotAtNanos;

    private SchedulerTelemetry() {
    }

    static synchronized void recordTask(
            long queueWaitNanos,
            long executionNanos,
            boolean failed,
            boolean cancelled
    ) {
        QUEUE_WAIT_NANOS[nextIndex] = Math.max(0L, queueWaitNanos);
        EXECUTION_NANOS[nextIndex] = Math.max(0L, executionNanos);
        nextIndex = (nextIndex + 1) % WINDOW_SIZE;
        sampleCount = Math.min(WINDOW_SIZE, sampleCount + 1);

        if (cancelled) {
            cancelledTasks++;
        } else {
            finishedTasks++;
            if (failed) {
                failedTasks++;
            }
        }
    }

    static synchronized void recordRejected() {
        rejectedTasks++;
    }

    public static synchronized Snapshot snapshot() {
        long now = System.nanoTime();
        if (cachedSnapshot != null && now - lastSnapshotAtNanos < SNAPSHOT_CACHE_NANOS) {
            return cachedSnapshot;
        }

        long[] queueWaits = Arrays.copyOf(QUEUE_WAIT_NANOS, sampleCount);
        long[] executionTimes = Arrays.copyOf(EXECUTION_NANOS, sampleCount);
        Arrays.sort(queueWaits);
        Arrays.sort(executionTimes);

        cachedSnapshot = new Snapshot(
                sampleCount,
                percentileMillis(queueWaits, 0.95),
                percentileMillis(executionTimes, 0.95),
                percentileMillis(queueWaits, 0.50),
                percentileMillis(executionTimes, 0.50),
                finishedTasks,
                failedTasks,
                cancelledTasks,
                rejectedTasks
        );
        lastSnapshotAtNanos = now;
        return cachedSnapshot;
    }

    static synchronized void resetForTests() {
        Arrays.fill(QUEUE_WAIT_NANOS, 0L);
        Arrays.fill(EXECUTION_NANOS, 0L);
        nextIndex = 0;
        sampleCount = 0;
        finishedTasks = 0L;
        failedTasks = 0L;
        cancelledTasks = 0L;
        rejectedTasks = 0L;
        cachedSnapshot = null;
        lastSnapshotAtNanos = 0L;
    }

    private static double percentileMillis(long[] sortedValues, double percentile) {
        if (sortedValues.length == 0) {
            return 0.0;
        }
        int index = Math.max(0, (int) Math.ceil(sortedValues.length * percentile) - 1);
        return sortedValues[index] / 1_000_000.0;
    }

    public record Snapshot(
            int sampleCount,
            double queueWaitP95Millis,
            double executionP95Millis,
            double queueWaitP50Millis,
            double executionP50Millis,
            long finishedTasks,
            long failedTasks,
            long cancelledTasks,
            long rejectedTasks
    ) {
    }
}

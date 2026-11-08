package com.makakaiglitch.fiximizer.diagnostics;

import java.util.Arrays;

/**
 * Bounded rolling window for client-side wall-clock durations.
 * Percentiles are recalculated in batches to avoid sorting on every frame.
 */
public final class RollingDurationWindow {
    public static final int DEFAULT_CAPACITY = 600;
    private static final int SNAPSHOT_INTERVAL = 15;

    private static final long OVER_16_67_MS = 16_666_667L;
    private static final long OVER_33_33_MS = 33_333_334L;
    private static final long OVER_50_MS = 50_000_000L;
    private static final long OVER_100_MS = 100_000_000L;

    private final long[] durationsNanos;
    private int cursor;
    private int size;
    private int samplesSinceSnapshot;
    private long totalNanos;
    private int over16_67Ms;
    private int over33_33Ms;
    private int over50Ms;
    private int over100Ms;
    private volatile Snapshot latest = Snapshot.empty();

    public RollingDurationWindow(int capacity) {
        if (capacity < 1 || capacity > 100_000) {
            throw new IllegalArgumentException("capacity must be between 1 and 100000");
        }
        durationsNanos = new long[capacity];
    }

    public void record(long durationNanos) {
        long safeDuration = Math.max(0L, durationNanos);

        if (size == durationsNanos.length) {
            long removed = durationsNanos[cursor];
            totalNanos -= removed;
            decrementThresholdCounters(removed);
        } else {
            size++;
        }

        durationsNanos[cursor] = safeDuration;
        cursor = (cursor + 1) % durationsNanos.length;
        totalNanos += safeDuration;
        incrementThresholdCounters(safeDuration);

        samplesSinceSnapshot++;
        if (size <= SNAPSHOT_INTERVAL || samplesSinceSnapshot >= SNAPSHOT_INTERVAL) {
            samplesSinceSnapshot = 0;
            latest = buildSnapshot();
        }
    }

    public Snapshot snapshot() {
        return latest;
    }

    public void reset() {
        Arrays.fill(durationsNanos, 0L);
        cursor = 0;
        size = 0;
        samplesSinceSnapshot = 0;
        totalNanos = 0L;
        over16_67Ms = 0;
        over33_33Ms = 0;
        over50Ms = 0;
        over100Ms = 0;
        latest = Snapshot.empty();
    }

    private void incrementThresholdCounters(long duration) {
        if (duration > OVER_16_67_MS) over16_67Ms++;
        if (duration > OVER_33_33_MS) over33_33Ms++;
        if (duration > OVER_50_MS) over50Ms++;
        if (duration > OVER_100_MS) over100Ms++;
    }

    private void decrementThresholdCounters(long duration) {
        if (duration > OVER_16_67_MS) over16_67Ms--;
        if (duration > OVER_33_33_MS) over33_33Ms--;
        if (duration > OVER_50_MS) over50Ms--;
        if (duration > OVER_100_MS) over100Ms--;
    }

    private Snapshot buildSnapshot() {
        if (size == 0) return Snapshot.empty();

        long[] sorted = Arrays.copyOf(durationsNanos, size);
        Arrays.sort(sorted);
        return new Snapshot(
                size,
                totalNanos / (double) size / 1_000_000.0,
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99),
                sorted[sorted.length - 1] / 1_000_000.0,
                over16_67Ms,
                over33_33Ms,
                over50Ms,
                over100Ms
        );
    }

    private static double percentileMillis(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index] / 1_000_000.0;
    }

    public record Snapshot(
            int sampleCount,
            double averageMillis,
            double p50Millis,
            double p95Millis,
            double p99Millis,
            double peakMillis,
            int over16_67Millis,
            int over33_33Millis,
            int over50Millis,
            int over100Millis
    ) {
        private static Snapshot empty() {
            return new Snapshot(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0);
        }

        public boolean hasSamples() {
            return sampleCount > 0;
        }
    }
}

package com.makakaiglitch.fiximizer.diagnostics;

import java.util.Arrays;

/**
 * Bounded timing history for one entity type. Each sample represents one
 * selected server tick, including ticks in which this entity type was absent.
 */
public final class RollingEntityTypeWindow {
    public static final int DEFAULT_CAPACITY = 100;

    private final long[] entityCounts;
    private final long[] tickNanos;
    private final long[] peakEntityNanos;
    private int cursor;
    private int size;
    private long totalEntityTicks;
    private long totalNanos;
    private long peakEntityNanosInWindow;
    private volatile Snapshot latest = Snapshot.empty();

    public RollingEntityTypeWindow() {
        this(DEFAULT_CAPACITY);
    }

    public RollingEntityTypeWindow(int capacity) {
        if (capacity < 1 || capacity > 10_000) {
            throw new IllegalArgumentException("capacity must be between 1 and 10000");
        }
        entityCounts = new long[capacity];
        tickNanos = new long[capacity];
        peakEntityNanos = new long[capacity];
    }

    public void record(long entityCount, long elapsedNanos, long peakEntityNanosInTick) {
        long safeCount = Math.max(0L, entityCount);
        long safeElapsed = Math.max(0L, elapsedNanos);
        long safePeak = Math.max(0L, peakEntityNanosInTick);

        if (size == entityCounts.length) {
            totalEntityTicks -= entityCounts[cursor];
            totalNanos -= tickNanos[cursor];
        } else {
            size++;
        }

        entityCounts[cursor] = safeCount;
        tickNanos[cursor] = safeElapsed;
        peakEntityNanos[cursor] = safePeak;
        totalEntityTicks += safeCount;
        totalNanos += safeElapsed;
        cursor = (cursor + 1) % entityCounts.length;

        peakEntityNanosInWindow = 0L;
        for (long sample : peakEntityNanos) {
            peakEntityNanosInWindow = Math.max(peakEntityNanosInWindow, sample);
        }

        latest = buildSnapshot();
    }

    public Snapshot snapshot() {
        return latest;
    }

    public void reset() {
        Arrays.fill(entityCounts, 0L);
        Arrays.fill(tickNanos, 0L);
        Arrays.fill(peakEntityNanos, 0L);
        cursor = 0;
        size = 0;
        totalEntityTicks = 0L;
        totalNanos = 0L;
        peakEntityNanosInWindow = 0L;
        latest = Snapshot.empty();
    }

    private Snapshot buildSnapshot() {
        if (size == 0) {
            return Snapshot.empty();
        }

        long[] sortedTickNanos = Arrays.copyOf(tickNanos, size);
        Arrays.sort(sortedTickNanos);
        long p95Nanos = sortedTickNanos[Math.max(
                0,
                Math.min(sortedTickNanos.length - 1,
                        (int) Math.ceil(sortedTickNanos.length * 0.95) - 1)
        )];

        return new Snapshot(
                size,
                totalEntityTicks,
                totalNanos / 1_000_000.0,
                totalNanos / (double) size / 1_000_000.0,
                p95Nanos / 1_000_000.0,
                peakEntityNanosInWindow / 1_000_000.0,
                totalEntityTicks == 0L ? 0.0 : totalNanos / (double) totalEntityTicks / 1_000_000.0
        );
    }

    public record Snapshot(
            int sampleCount,
            long entityTicks,
            double totalMillis,
            double averageMillisPerSampledTick,
            double p95MillisPerSampledTick,
            double peakEntityMillis,
            double averageMillisPerEntityTick
    ) {
        private static Snapshot empty() {
            return new Snapshot(0, 0L, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        public boolean hasSamples() {
            return sampleCount > 0;
        }
    }
}

package com.makakaiglitch.fiximizer.compat.create;

/**
 * Bounded rolling window for server-tick timing samples.
 *
 * This class is independent of Minecraft/Create so its arithmetic can be
 * tested without launching the game.
 */
public final class RollingTickWindow {
    private final long[] entityTicks;
    private final long[] totalNanos;
    private final long[] peakEntityNanos;
    private int cursor;
    private int size;
    private long summedEntityTicks;
    private long summedNanos;

    public RollingTickWindow(int capacity) {
        if (capacity < 1 || capacity > 10_000) {
            throw new IllegalArgumentException("capacity must be between 1 and 10000");
        }
        entityTicks = new long[capacity];
        totalNanos = new long[capacity];
        peakEntityNanos = new long[capacity];
    }

    public synchronized void record(long tickEntityCount, long tickNanos, long tickPeakEntityNanos) {
        long safeEntityCount = Math.max(0L, tickEntityCount);
        long safeTickNanos = Math.max(0L, tickNanos);
        long safePeakNanos = Math.max(0L, tickPeakEntityNanos);

        if (size == entityTicks.length) {
            summedEntityTicks -= entityTicks[cursor];
            summedNanos -= totalNanos[cursor];
        } else {
            size++;
        }

        entityTicks[cursor] = safeEntityCount;
        totalNanos[cursor] = safeTickNanos;
        peakEntityNanos[cursor] = safePeakNanos;
        summedEntityTicks += safeEntityCount;
        summedNanos += safeTickNanos;
        cursor = (cursor + 1) % entityTicks.length;
    }

    public synchronized Snapshot snapshot() {
        long peak = 0L;
        for (int i = 0; i < size; i++) {
            peak = Math.max(peak, peakEntityNanos[i]);
        }
        return new Snapshot(size, summedEntityTicks, summedNanos, peak);
    }

    public synchronized void reset() {
        java.util.Arrays.fill(entityTicks, 0L);
        java.util.Arrays.fill(totalNanos, 0L);
        java.util.Arrays.fill(peakEntityNanos, 0L);
        cursor = 0;
        size = 0;
        summedEntityTicks = 0L;
        summedNanos = 0L;
    }

    public record Snapshot(
            int sampledServerTicks,
            long contraptionEntityTicks,
            long totalContraptionNanos,
            long peakEntityTickNanos
    ) {
        public double averageMillisPerServerTick() {
            return sampledServerTicks == 0
                    ? 0.0
                    : totalContraptionNanos / (double) sampledServerTicks / 1_000_000.0;
        }

        public double averageMillisPerEntityTick() {
            return contraptionEntityTicks == 0L
                    ? 0.0
                    : totalContraptionNanos / (double) contraptionEntityTicks / 1_000_000.0;
        }

        public double peakEntityTickMillis() {
            return peakEntityTickNanos / 1_000_000.0;
        }
    }
}

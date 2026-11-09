package com.makakaiglitch.fiximizer.benchmark;

import java.util.Arrays;

/** Bounded duration samples with deterministic nearest-rank percentiles. */
public final class BenchmarkSampleWindow {
    public static final int DEFAULT_CAPACITY = 8192;

    private final long[] samplesNanos;
    private int cursor;
    private int size;
    private long totalRecorded;

    public BenchmarkSampleWindow() {
        this(DEFAULT_CAPACITY);
    }

    public BenchmarkSampleWindow(int capacity) {
        if (capacity < 1 || capacity > 100_000) {
            throw new IllegalArgumentException("capacity must be between 1 and 100000");
        }
        samplesNanos = new long[capacity];
    }

    /** One producer per metric (render, client tick or server tick). */
    public synchronized void record(long durationNanos) {
        samplesNanos[cursor] = Math.max(0L, durationNanos);
        cursor = (cursor + 1) % samplesNanos.length;
        size = Math.min(samplesNanos.length, size + 1);
        totalRecorded++;
    }

    public synchronized int sampleCount() {
        return size;
    }

    public synchronized Statistics snapshot() {
        if (size == 0) {
            return Statistics.empty(totalRecorded);
        }

        long[] sorted = new long[size];
        int oldest = (cursor - size + samplesNanos.length) % samplesNanos.length;
        for (int index = 0; index < size; index++) {
            sorted[index] = samplesNanos[(oldest + index) % samplesNanos.length];
        }
        Arrays.sort(sorted);

        double sum = 0.0;
        double sumSquares = 0.0;
        for (long value : sorted) {
            double sample = value;
            sum += sample;
            sumSquares += sample * sample;
        }

        double mean = sum / size;
        double variance = Math.max(0.0, sumSquares / size - mean * mean);
        long p50 = percentile(sorted, 0.50);
        long p95 = percentile(sorted, 0.95);
        long p99 = percentile(sorted, 0.99);

        return new Statistics(
                size,
                totalRecorded,
                Math.max(0L, totalRecorded - size),
                nanosToMillis(mean),
                nanosToMillis(Math.sqrt(variance)),
                nanosToMillis(sorted[0]),
                nanosToMillis(p50),
                nanosToMillis(p95),
                nanosToMillis(p99),
                nanosToMillis(sorted[sorted.length - 1])
        );
    }

    private static long percentile(long[] sorted, double quantile) {
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index];
    }

    private static double nanosToMillis(double nanos) {
        return nanos / 1_000_000.0;
    }

    public record Statistics(
            int sampleCount,
            long totalRecordedSamples,
            long overwrittenSamples,
            double meanMillis,
            double standardDeviationMillis,
            double minMillis,
            double p50Millis,
            double p95Millis,
            double p99Millis,
            double maxMillis
    ) {
        public boolean hasSamples() {
            return sampleCount > 0;
        }

        private static Statistics empty(long totalRecorded) {
            return new Statistics(0, totalRecorded, 0L, 0.0, 0.0,
                    0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }
}

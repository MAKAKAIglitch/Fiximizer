package com.makakaiglitch.fiximizer.compat.sable;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounded window of recent Sable physics substep timings.
 * Independent of Sable classes so the aggregation can be unit-tested.
 */
public final class RollingPhysicsWindow {
    public static final int DEFAULT_CAPACITY = 1_000;

    private final long[] elapsedNanos;
    private final double[] timeSteps;
    private final String[] dimensions;
    private int cursor;
    private int size;

    public RollingPhysicsWindow(int capacity) {
        if (capacity < 1 || capacity > 100_000) {
            throw new IllegalArgumentException("capacity must be between 1 and 100000");
        }
        elapsedNanos = new long[capacity];
        timeSteps = new double[capacity];
        dimensions = new String[capacity];
    }

    public synchronized void record(String dimension, long elapsed, double timeStep) {
        elapsedNanos[cursor] = Math.max(0L, elapsed);
        timeSteps[cursor] = Double.isFinite(timeStep) && timeStep > 0.0 ? timeStep : 0.0;
        dimensions[cursor] = normalizeDimension(dimension);

        if (size < elapsedNanos.length) {
            size++;
        }
        cursor = (cursor + 1) % elapsedNanos.length;
    }

    public synchronized Snapshot snapshot() {
        if (size == 0) {
            return Snapshot.empty();
        }

        long totalNanos = 0L;
        long peakNanos = 0L;
        double totalTimeStep = 0.0;
        Map<String, Long> nanosByDimension = new HashMap<>();

        for (int i = 0; i < size; i++) {
            long elapsed = elapsedNanos[i];
            totalNanos = saturatedAdd(totalNanos, elapsed);
            peakNanos = Math.max(peakNanos, elapsed);
            totalTimeStep += timeSteps[i];
            nanosByDimension.merge(dimensions[i], elapsed, RollingPhysicsWindow::saturatedAdd);
        }

        Map.Entry<String, Long> topDimension = nanosByDimension.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(Map.entry("n/a", 0L));

        return new Snapshot(
                size,
                totalNanos,
                totalNanos / (double) size / 1_000_000.0,
                peakNanos / 1_000_000.0,
                totalNanos / 1_000_000.0,
                totalTimeStep / size * 1_000.0,
                nanosByDimension.size(),
                topDimension.getKey(),
                topDimension.getValue() / 1_000_000.0
        );
    }

    public synchronized void reset() {
        java.util.Arrays.fill(elapsedNanos, 0L);
        java.util.Arrays.fill(timeSteps, 0.0);
        java.util.Arrays.fill(dimensions, null);
        cursor = 0;
        size = 0;
    }

    private static String normalizeDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return "unknown";
        }
        return dimension.length() > 96 ? dimension.substring(0, 96) : dimension;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public record Snapshot(
            int physicsSteps,
            long totalNanos,
            double averageSubstepMillis,
            double peakSubstepMillis,
            double totalWindowMillis,
            double averageSimulationStepMillis,
            int dimensionCount,
            String topDimension,
            double topDimensionMillis
    ) {
        private static Snapshot empty() {
            return new Snapshot(0, 0L, 0.0, 0.0, 0.0, 0.0, 0, "n/a", 0.0);
        }

        public boolean hasSamples() {
            return physicsSteps > 0;
        }
    }
}

package com.makakaiglitch.fiximizer.compat.sable;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bounded window of recent Sable physics substep timings.
 * Statistics are maintained incrementally; reading a snapshot does no work.
 */
public final class RollingPhysicsWindow {
    public static final int DEFAULT_CAPACITY = 1_000;

    private final long[] elapsedNanos;
    private final double[] timeSteps;
    private final String[] dimensions;
    private final Map<String, Long> nanosByDimension = new HashMap<>();
    private final TreeMap<Long, Integer> durationCounts = new TreeMap<>();

    private int cursor;
    private int size;
    private long totalNanos;
    private double totalTimeStep;
    private volatile Snapshot latest = Snapshot.empty();

    public RollingPhysicsWindow(int capacity) {
        if (capacity < 1 || capacity > 100_000) {
            throw new IllegalArgumentException("capacity must be between 1 and 100000");
        }
        elapsedNanos = new long[capacity];
        timeSteps = new double[capacity];
        dimensions = new String[capacity];
    }

    public synchronized void record(String dimension, long elapsed, double timeStep) {
        long safeElapsed = Math.max(0L, elapsed);
        double safeTimeStep = Double.isFinite(timeStep) && timeStep > 0.0 ? timeStep : 0.0;
        String safeDimension = normalizeDimension(dimension);

        if (size == elapsedNanos.length) {
            long removedNanos = elapsedNanos[cursor];
            double removedTimeStep = timeSteps[cursor];
            String removedDimension = dimensions[cursor];

            totalNanos -= removedNanos;
            totalTimeStep -= removedTimeStep;
            decrementDuration(removedNanos);
            decrementDimension(removedDimension, removedNanos);
        } else {
            size++;
        }

        elapsedNanos[cursor] = safeElapsed;
        timeSteps[cursor] = safeTimeStep;
        dimensions[cursor] = safeDimension;
        cursor = (cursor + 1) % elapsedNanos.length;

        totalNanos += safeElapsed;
        totalTimeStep += safeTimeStep;
        durationCounts.merge(safeElapsed, 1, Integer::sum);
        nanosByDimension.merge(safeDimension, safeElapsed, Long::sum);

        latest = buildSnapshot();
    }

    public Snapshot snapshot() {
        return latest;
    }

    public synchronized void reset() {
        java.util.Arrays.fill(elapsedNanos, 0L);
        java.util.Arrays.fill(timeSteps, 0.0);
        java.util.Arrays.fill(dimensions, null);
        nanosByDimension.clear();
        durationCounts.clear();
        cursor = 0;
        size = 0;
        totalNanos = 0L;
        totalTimeStep = 0.0;
        latest = Snapshot.empty();
    }

    private Snapshot buildSnapshot() {
        if (size == 0) {
            return Snapshot.empty();
        }

        String topDimension = "n/a";
        long topDimensionNanos = 0L;
        for (Map.Entry<String, Long> entry : nanosByDimension.entrySet()) {
            if (entry.getValue() > topDimensionNanos) {
                topDimension = entry.getKey();
                topDimensionNanos = entry.getValue();
            }
        }

        long peakNanos = durationCounts.isEmpty() ? 0L : durationCounts.lastKey();
        return new Snapshot(
                size,
                totalNanos,
                totalNanos / (double) size / 1_000_000.0,
                peakNanos / 1_000_000.0,
                totalNanos / 1_000_000.0,
                totalTimeStep / size * 1_000.0,
                nanosByDimension.size(),
                topDimension,
                topDimensionNanos / 1_000_000.0
        );
    }

    private void decrementDuration(long duration) {
        Integer count = durationCounts.get(duration);
        if (count == null || count <= 1) {
            durationCounts.remove(duration);
        } else {
            durationCounts.put(duration, count - 1);
        }
    }

    private void decrementDimension(String dimension, long duration) {
        Long current = nanosByDimension.get(dimension);
        if (current == null || current <= duration) {
            nanosByDimension.remove(dimension);
        } else {
            nanosByDimension.put(dimension, current - duration);
        }
    }

    private static String normalizeDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return "unknown";
        }
        return dimension.length() > 96 ? dimension.substring(0, 96) : dimension;
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

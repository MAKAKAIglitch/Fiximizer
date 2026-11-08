package com.makakaiglitch.fiximizer.diagnostics;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Bounded history of repeated captures belonging to one continuous stall.
 * It is mutated only by the watchdog worker; snapshots are immutable.
 */
public final class IncidentTimeline {
    private final String incidentId;
    private final Instant startedAt;
    private final long startedAtNanos;
    private final int capacity;
    private final ArrayDeque<Capture> captures = new ArrayDeque<>();
    private int totalCaptures;
    private long firstDurationMillis;

    public IncidentTimeline(
            String incidentId,
            Instant startedAt,
            long startedAtNanos,
            int capacity
    ) {
        if (incidentId == null || incidentId.isBlank()) {
            throw new IllegalArgumentException("incidentId must not be blank");
        }
        if (capacity < 1 || capacity > 64) {
            throw new IllegalArgumentException("capacity must be between 1 and 64");
        }
        this.incidentId = incidentId;
        this.startedAt = startedAt;
        this.startedAtNanos = startedAtNanos;
        this.capacity = capacity;
    }

    public synchronized Snapshot recordCapture(
            Instant capturedAt,
            long capturedAtNanos,
            long durationNanos
    ) {
        totalCaptures++;
        long elapsedMillis = Math.max(0L, capturedAtNanos - startedAtNanos) / 1_000_000L;
        long durationMillis = Math.max(0L, durationNanos) / 1_000_000L;
        if (totalCaptures == 1) {
            firstDurationMillis = durationMillis;
        }
        captures.addLast(new Capture(
                totalCaptures,
                capturedAt,
                elapsedMillis,
                durationMillis
        ));
        while (captures.size() > capacity) {
            captures.removeFirst();
        }
        return snapshot();
    }

    public synchronized Snapshot snapshot() {
        List<Capture> points = List.copyOf(captures);
        long peak = 0L;
        for (Capture point : points) {
            peak = Math.max(peak, point.durationMillis());
        }
        long first = firstDurationMillis;
        long latest = points.isEmpty() ? 0L : points.getLast().durationMillis();
        return new Snapshot(
                incidentId,
                startedAt,
                totalCaptures,
                points,
                first,
                latest,
                peak
        );
    }

    public record Capture(
            int number,
            Instant capturedAt,
            long elapsedMillis,
            long durationMillis
    ) {
    }

    public record Snapshot(
            String incidentId,
            Instant startedAt,
            int totalCaptures,
            List<Capture> captures,
            long firstDurationMillis,
            long latestDurationMillis,
            long peakDurationMillis
    ) {
        public Snapshot {
            captures = List.copyOf(captures);
        }

        public long durationChangeMillis() {
            return latestDurationMillis - firstDurationMillis;
        }
    }
}

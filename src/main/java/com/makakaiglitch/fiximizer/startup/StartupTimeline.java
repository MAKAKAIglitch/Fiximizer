package com.makakaiglitch.fiximizer.startup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Small, bounded, thread-safe timeline for one JVM launch.
 *
 * Each milestone is recorded at most once. Timestamps use System.nanoTime()
 * so phase durations are not affected by wall-clock corrections.
 */
public final class StartupTimeline {
    private static final long NOT_RECORDED = Long.MIN_VALUE;

    public enum Stage {
        FIXIMIZER_MOD_CONSTRUCTED,
        FIXIMIZER_CLIENT_CONSTRUCTED,
        CLIENT_SETUP_STARTED,
        CLIENT_SETUP_COMPLETED,
        FIRST_TITLE_SCREEN_TICK,
        STABLE_CLIENT_READY,
        OPTIONAL_MOD_SCAN_STARTED,
        OPTIONAL_MOD_SCAN_COMPLETED,
        OPTIONAL_SERVICES_SCHEDULED
    }

    private final AtomicLongArray timestamps = new AtomicLongArray(Stage.values().length);

    public StartupTimeline() {
        for (int index = 0; index < timestamps.length(); index++) {
            timestamps.set(index, NOT_RECORDED);
        }
    }

    /**
     * Records the first timestamp for a stage. Repeated calls are harmless.
     *
     * @return true only when this call recorded the milestone
     */
    public boolean mark(Stage stage, long timestampNanos) {
        if (stage == null) {
            throw new NullPointerException("stage");
        }
        return timestamps.compareAndSet(stage.ordinal(), NOT_RECORDED, timestampNanos);
    }

    public boolean wasReached(Stage stage) {
        return timestampNanos(stage) != NOT_RECORDED;
    }

    public long timestampNanos(Stage stage) {
        if (stage == null) {
            throw new NullPointerException("stage");
        }
        return timestamps.get(stage.ordinal());
    }

    /**
     * Returns -1 when a stage is missing or the supplied order is invalid.
     */
    public long elapsedNanos(Stage start, Stage end) {
        long startedAt = timestampNanos(start);
        long endedAt = timestampNanos(end);
        if (startedAt == NOT_RECORDED || endedAt == NOT_RECORDED || endedAt < startedAt) {
            return -1L;
        }
        return endedAt - startedAt;
    }

    public long elapsedMillis(Stage start, Stage end) {
        long elapsed = elapsedNanos(start, end);
        return elapsed < 0L ? -1L : TimeUnit.NANOSECONDS.toMillis(elapsed);
    }
}

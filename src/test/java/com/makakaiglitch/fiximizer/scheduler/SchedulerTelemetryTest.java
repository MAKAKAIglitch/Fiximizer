package com.makakaiglitch.fiximizer.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulerTelemetryTest {
    @AfterEach
    void resetTelemetry() {
        SchedulerTelemetry.resetForTests();
    }

    @Test
    void calculatesBoundedRollingPercentilesAndCounters() {
        for (int sample = 1; sample <= 160; sample++) {
            SchedulerTelemetry.recordTask(
                    sample * 1_000_000L,
                    sample * 2_000_000L,
                    sample == 160,
                    false
            );
        }
        SchedulerTelemetry.recordTask(500_000_000L, 0L, false, true);
        SchedulerTelemetry.recordRejected();

        SchedulerTelemetry.Snapshot snapshot = SchedulerTelemetry.snapshot();

        assertEquals(128, snapshot.sampleCount());
        assertEquals(160L, snapshot.finishedTasks());
        assertEquals(1L, snapshot.failedTasks());
        assertEquals(1L, snapshot.cancelledTasks());
        assertEquals(1L, snapshot.rejectedTasks());
        assertEquals(155.0, snapshot.queueWaitP95Millis(), 0.001);
        assertEquals(308.0, snapshot.executionP95Millis(), 0.001);
    }

    @Test
    void emptyWindowHasZeroPercentiles() {
        SchedulerTelemetry.Snapshot snapshot = SchedulerTelemetry.snapshot();
        assertEquals(0, snapshot.sampleCount());
        assertEquals(0.0, snapshot.queueWaitP95Millis());
        assertEquals(0.0, snapshot.executionP95Millis());
    }
}

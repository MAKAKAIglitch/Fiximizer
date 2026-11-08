package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RollingDurationWindowTest {
    @Test
    void calculatesAverageAndNearestRankPercentiles() {
        RollingDurationWindow window = new RollingDurationWindow(4);
        window.record(5_000_000L);
        window.record(10_000_000L);
        window.record(20_000_000L);
        window.record(40_000_000L);

        RollingDurationWindow.Snapshot snapshot = window.snapshot();
        assertEquals(4, snapshot.sampleCount());
        assertEquals(18.75, snapshot.averageMillis(), 0.0001);
        assertEquals(10.0, snapshot.p50Millis(), 0.0001);
        assertEquals(40.0, snapshot.p95Millis(), 0.0001);
        assertEquals(40.0, snapshot.p99Millis(), 0.0001);
        assertEquals(40.0, snapshot.peakMillis(), 0.0001);
        assertEquals(2, snapshot.over16_67Millis());
        assertEquals(1, snapshot.over33_33Millis());
    }

    @Test
    void evictsOldestSamplesAndTheirThresholdCounts() {
        RollingDurationWindow window = new RollingDurationWindow(3);
        window.record(10_000_000L);
        window.record(20_000_000L);
        window.record(30_000_000L);
        window.record(40_000_000L);

        RollingDurationWindow.Snapshot snapshot = window.snapshot();
        assertEquals(3, snapshot.sampleCount());
        assertEquals(30.0, snapshot.averageMillis(), 0.0001);
        assertEquals(30.0, snapshot.p50Millis(), 0.0001);
        assertEquals(40.0, snapshot.peakMillis(), 0.0001);
        assertEquals(3, snapshot.over16_67Millis());
        assertEquals(1, snapshot.over33_33Millis());
        assertEquals(0, snapshot.over50Millis());
    }

    @Test
    void sanitizesNegativeDurationsAndCountsLongStalls() {
        RollingDurationWindow window = new RollingDurationWindow(4);
        window.record(-1L);
        window.record(16_666_667L);
        window.record(50_000_001L);
        window.record(100_000_001L);

        RollingDurationWindow.Snapshot snapshot = window.snapshot();
        assertEquals(4, snapshot.sampleCount());
        assertEquals(41.666667, snapshot.averageMillis(), 0.0001);
        assertEquals(2, snapshot.over16_67Millis());
        assertEquals(2, snapshot.over33_33Millis());
        assertEquals(2, snapshot.over50Millis());
        assertEquals(1, snapshot.over100Millis());
    }

    @Test
    void refreshesSnapshotAfterBatchAndClearsOnReset() {
        RollingDurationWindow window = new RollingDurationWindow(32);
        for (int i = 1; i <= 30; i++) {
            window.record(i * 1_000_000L);
        }

        assertEquals(30, window.snapshot().sampleCount());
        assertEquals(30.0, window.snapshot().peakMillis(), 0.0001);

        window.reset();
        assertFalse(window.snapshot().hasSamples());
        assertEquals(0, window.snapshot().sampleCount());
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new RollingDurationWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new RollingDurationWindow(100_001));
    }
}

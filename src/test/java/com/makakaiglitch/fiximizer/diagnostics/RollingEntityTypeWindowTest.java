package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RollingEntityTypeWindowTest {
    @Test
    void calculatesWindowTotalsAndNearestRankP95() {
        RollingEntityTypeWindow window = new RollingEntityTypeWindow(4);
        window.record(2, 10_000_000L, 7_000_000L);
        window.record(1, 20_000_000L, 20_000_000L);
        window.record(3, 30_000_000L, 15_000_000L);
        window.record(0, 0L, 0L);

        RollingEntityTypeWindow.Snapshot snapshot = window.snapshot();
        assertEquals(4, snapshot.sampleCount());
        assertEquals(6L, snapshot.entityTicks());
        assertEquals(60.0, snapshot.totalMillis(), 0.0001);
        assertEquals(15.0, snapshot.averageMillisPerSampledTick(), 0.0001);
        assertEquals(30.0, snapshot.p95MillisPerSampledTick(), 0.0001);
        assertEquals(20.0, snapshot.peakEntityMillis(), 0.0001);
        assertEquals(10.0, snapshot.averageMillisPerEntityTick(), 0.0001);
    }

    @Test
    void evictsOldSamplesFromTheBoundedWindow() {
        RollingEntityTypeWindow window = new RollingEntityTypeWindow(2);
        window.record(1, 10_000_000L, 10_000_000L);
        window.record(2, 20_000_000L, 12_000_000L);
        window.record(1, 30_000_000L, 30_000_000L);

        RollingEntityTypeWindow.Snapshot snapshot = window.snapshot();
        assertEquals(2, snapshot.sampleCount());
        assertEquals(3L, snapshot.entityTicks());
        assertEquals(50.0, snapshot.totalMillis(), 0.0001);
        assertEquals(30.0, snapshot.p95MillisPerSampledTick(), 0.0001);
        assertEquals(30.0, snapshot.peakEntityMillis(), 0.0001);
    }

    @Test
    void rejectsInvalidWindowCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new RollingEntityTypeWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new RollingEntityTypeWindow(10_001));
    }
}

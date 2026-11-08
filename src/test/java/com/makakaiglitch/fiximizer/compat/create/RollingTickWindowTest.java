package com.makakaiglitch.fiximizer.compat.create;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RollingTickWindowTest {
    @Test
    void calculatesAverageAcrossSamples() {
        RollingTickWindow window = new RollingTickWindow(3);
        window.record(2, 4_000_000L, 3_000_000L);
        window.record(1, 2_000_000L, 2_000_000L);

        RollingTickWindow.Snapshot snapshot = window.snapshot();
        assertEquals(2, snapshot.sampledServerTicks());
        assertEquals(3L, snapshot.contraptionEntityTicks());
        assertEquals(3.0, snapshot.averageMillisPerServerTick(), 0.0001);
        assertEquals(2.0, snapshot.averageMillisPerEntityTick(), 0.0001);
        assertEquals(3.0, snapshot.peakEntityTickMillis(), 0.0001);
    }

    @Test
    void evictsOldestSamplesWhenWindowIsFull() {
        RollingTickWindow window = new RollingTickWindow(2);
        window.record(3, 3_000_000L, 2_000_000L);
        window.record(2, 8_000_000L, 5_000_000L);
        window.record(1, 5_000_000L, 4_000_000L);

        RollingTickWindow.Snapshot snapshot = window.snapshot();
        assertEquals(2, snapshot.sampledServerTicks());
        assertEquals(3L, snapshot.contraptionEntityTicks());
        assertEquals(6.5, snapshot.averageMillisPerServerTick(), 0.0001);
        assertEquals(5.0, snapshot.peakEntityTickMillis(), 0.0001);
    }

    @Test
    void retainsServerTicksWithNoContraptionEntities() {
        RollingTickWindow window = new RollingTickWindow(4);
        window.record(0, 0L, 0L);
        window.record(0, 0L, 0L);

        RollingTickWindow.Snapshot snapshot = window.snapshot();
        assertEquals(2, snapshot.sampledServerTicks());
        assertEquals(0L, snapshot.contraptionEntityTicks());
        assertEquals(0.0, snapshot.averageMillisPerEntityTick(), 0.0001);
    }

    @Test
    void clampsNegativeSamplesToZero() {
        RollingTickWindow window = new RollingTickWindow(2);
        window.record(-1, -10L, -20L);

        RollingTickWindow.Snapshot snapshot = window.snapshot();
        assertEquals(0L, snapshot.contraptionEntityTicks());
        assertEquals(0.0, snapshot.averageMillisPerServerTick(), 0.0001);
    }

    @Test
    void rejectsInvalidWindowSize() {
        assertThrows(IllegalArgumentException.class, () -> new RollingTickWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new RollingTickWindow(10_001));
    }
}

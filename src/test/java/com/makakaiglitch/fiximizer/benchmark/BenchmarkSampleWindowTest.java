package com.makakaiglitch.fiximizer.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkSampleWindowTest {
    @Test
    void calculatesNearestRankPercentilesAndSpread() {
        BenchmarkSampleWindow window = new BenchmarkSampleWindow(8);
        window.record(10_000_000L);
        window.record(20_000_000L);
        window.record(30_000_000L);
        window.record(40_000_000L);

        BenchmarkSampleWindow.Statistics result = window.snapshot();

        assertEquals(4, result.sampleCount());
        assertEquals(4L, result.totalRecordedSamples());
        assertEquals(0L, result.overwrittenSamples());
        assertEquals(25.0, result.meanMillis(), 0.0001);
        assertEquals(10.0, result.minMillis(), 0.0001);
        assertEquals(20.0, result.p50Millis(), 0.0001);
        assertEquals(40.0, result.p95Millis(), 0.0001);
        assertEquals(40.0, result.p99Millis(), 0.0001);
        assertEquals(40.0, result.maxMillis(), 0.0001);
    }

    @Test
    void retainsNewestSamplesAndCountsOverwrites() {
        BenchmarkSampleWindow window = new BenchmarkSampleWindow(3);
        window.record(1_000_000L);
        window.record(2_000_000L);
        window.record(3_000_000L);
        window.record(4_000_000L);

        BenchmarkSampleWindow.Statistics result = window.snapshot();

        assertEquals(3, result.sampleCount());
        assertEquals(4L, result.totalRecordedSamples());
        assertEquals(1L, result.overwrittenSamples());
        assertEquals(2.0, result.minMillis(), 0.0001);
        assertEquals(4.0, result.maxMillis(), 0.0001);
    }

    @Test
    void clampsNegativeDurationsAndHandlesEmptyWindows() {
        BenchmarkSampleWindow window = new BenchmarkSampleWindow(2);
        assertEquals(false, window.snapshot().hasSamples());
        window.record(-100L);

        BenchmarkSampleWindow.Statistics result = window.snapshot();
        assertEquals(true, result.hasSamples());
        assertEquals(0.0, result.minMillis(), 0.0);
        assertEquals(0.0, result.p99Millis(), 0.0);
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkSampleWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkSampleWindow(100_001));
    }
}

package com.makakaiglitch.fiximizer.compat.sable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RollingPhysicsWindowTest {
    @Test
    void aggregatesStepTimeAndDimensions() {
        RollingPhysicsWindow window = new RollingPhysicsWindow(4);
        window.record("minecraft:overworld", 4_000_000L, 0.0025);
        window.record("minecraft:overworld", 6_000_000L, 0.0025);
        window.record("minecraft:the_nether", 8_000_000L, 0.005);

        RollingPhysicsWindow.Snapshot snapshot = window.snapshot();
        assertEquals(3, snapshot.physicsSteps());
        assertEquals(18.0, snapshot.totalWindowMillis(), 0.0001);
        assertEquals(6.0, snapshot.averageSubstepMillis(), 0.0001);
        assertEquals(8.0, snapshot.peakSubstepMillis(), 0.0001);
        assertEquals(10.0 / 3.0, snapshot.averageSimulationStepMillis(), 0.0001);
        assertEquals(2, snapshot.dimensionCount());
        assertEquals("minecraft:overworld", snapshot.topDimension());
        assertEquals(10.0, snapshot.topDimensionMillis(), 0.0001);
    }

    @Test
    void evictsOldestPhysicsSubsteps() {
        RollingPhysicsWindow window = new RollingPhysicsWindow(2);
        window.record("minecraft:overworld", 3_000_000L, 0.0025);
        window.record("minecraft:the_nether", 5_000_000L, 0.0025);
        window.record("minecraft:the_end", 7_000_000L, 0.005);

        RollingPhysicsWindow.Snapshot snapshot = window.snapshot();
        assertEquals(2, snapshot.physicsSteps());
        assertEquals(12.0, snapshot.totalWindowMillis(), 0.0001);
        assertEquals(6.0, snapshot.averageSubstepMillis(), 0.0001);
        assertEquals(2, snapshot.dimensionCount());
        assertEquals("minecraft:the_end", snapshot.topDimension());
        assertEquals(7.0, snapshot.topDimensionMillis(), 0.0001);
    }

    @Test
    void sanitizesInvalidMeasurements() {
        RollingPhysicsWindow window = new RollingPhysicsWindow(2);
        window.record(null, -50L, Double.NaN);
        window.record("minecraft:overworld", 2_000_000L, -1.0);

        RollingPhysicsWindow.Snapshot snapshot = window.snapshot();
        assertEquals(2, snapshot.physicsSteps());
        assertEquals(1.0, snapshot.averageSubstepMillis(), 0.0001);
        assertEquals(2.0, snapshot.peakSubstepMillis(), 0.0001);
        assertEquals(0.0, snapshot.averageSimulationStepMillis(), 0.0001);
        assertEquals(2, snapshot.dimensionCount());
        assertEquals("minecraft:overworld", snapshot.topDimension());
    }

    @Test
    void reportsEmptyAfterReset() {
        RollingPhysicsWindow window = new RollingPhysicsWindow(2);
        window.record("minecraft:overworld", 4_000_000L, 0.0025);
        window.reset();

        assertFalse(window.snapshot().hasSamples());
        assertEquals(0, window.snapshot().physicsSteps());
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new RollingPhysicsWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new RollingPhysicsWindow(100_001));
    }
}

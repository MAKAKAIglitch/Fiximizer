package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StallCapturePolicyTest {
    @Test
    void waitsUntilTheThresholdIsReached() {
        assertFalse(StallCapturePolicy.shouldCapture(
                1_999_000_000L, 10L, 2_000_000_000L, 0L, 2_000_000_000L, 0, 8));
        assertTrue(StallCapturePolicy.shouldCapture(
                2_000_000_000L, 10L, 2_000_000_000L, 0L, 2_000_000_000L, 0, 8));
    }

    @Test
    void enforcesTheRepeatInterval() {
        assertFalse(StallCapturePolicy.shouldCapture(
                4_000_000_000L, 4_000_000_000L, 2_000_000_000L,
                3_000_000_000L, 2_000_000_000L, 1, 8));
        assertTrue(StallCapturePolicy.shouldCapture(
                5_000_000_000L, 5_000_000_000L, 2_000_000_000L,
                3_000_000_000L, 2_000_000_000L, 1, 8));
    }

    @Test
    void stopsAfterTheConfiguredCaptureLimit() {
        assertFalse(StallCapturePolicy.shouldCapture(
                30_000_000_000L, 30_000_000_000L, 2_000_000_000L,
                0L, 2_000_000_000L, 8, 8));
    }

    @Test
    void rejectsAnInvalidCaptureLimit() {
        assertFalse(StallCapturePolicy.shouldCapture(
                5_000_000_000L, 5_000_000_000L, 2_000_000_000L,
                0L, 2_000_000_000L, 0, 0));
    }
}

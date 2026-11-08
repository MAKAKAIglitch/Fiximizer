package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModCorrelationScorerTest {
    @Test
    void weightsTriggerStackAboveBackgroundSamples() {
        double direct = ModCorrelationScorer.score(2, 4, 0, 0, 100, 0, 0);
        double background = ModCorrelationScorer.score(0, 0, 0, 100, 100, 0, 0);
        assertTrue(direct > background);
    }

    @Test
    void highConfidenceRequiresSeveralIndependentSignals() {
        assertEquals("HIGH", ModCorrelationScorer.confidence(3, 5, 5, 8, 0));
        assertEquals("LOW", ModCorrelationScorer.confidence(0, 0, 0, 30, 0));
    }

    @Test
    void allocationAndThreadSignalsCanRaiseConfidence() {
        assertEquals("MEDIUM", ModCorrelationScorer.confidence(0, 0, 5, 5, 4096));
        assertEquals("MEDIUM", ModCorrelationScorer.confidence(1, 2, 0, 0, 0));
    }

    @Test
    void percentageIsBoundedAndHandlesMissingDenominators() {
        assertEquals(50.0, ModCorrelationScorer.percentage(5, 10), 0.0001);
        assertEquals(100.0, ModCorrelationScorer.percentage(20, 10), 0.0001);
        assertEquals(0.0, ModCorrelationScorer.percentage(10, 0), 0.0001);
    }
}

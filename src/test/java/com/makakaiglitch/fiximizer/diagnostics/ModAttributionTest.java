package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModAttributionTest {
    @Test
    void requiresSeveralHighStackFramesForHighConfidence() {
        assertEquals("HIGH", ModAttribution.confidence(3, 5));
        assertEquals("HIGH", ModAttribution.confidence(4, 8));
    }

    @Test
    void marksSingleOrSparseMatchesAsLowConfidence() {
        assertEquals("LOW", ModAttribution.confidence(1, 1));
        assertEquals("LOW", ModAttribution.confidence(0, 4));
    }

    @Test
    void marksPartialTopStackEvidenceAsMediumConfidence() {
        assertEquals("MEDIUM", ModAttribution.confidence(1, 2));
        assertEquals("MEDIUM", ModAttribution.confidence(2, 4));
    }
}

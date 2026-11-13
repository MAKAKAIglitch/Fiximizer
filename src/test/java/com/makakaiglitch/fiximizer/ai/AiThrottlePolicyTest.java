package com.makakaiglitch.fiximizer.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiThrottlePolicyTest {
    @Test
    void vanillaIntervalAlwaysRunsFullChecksAtVanillaCallSites() {
        for (long tick = 0; tick < 20; tick++) {
            assertTrue(AiThrottlePolicy.shouldRunFullCheck(tick, 17, 2));
        }
    }

    @Test
    void fourTickIntervalIsStaggeredByEntityId() {
        assertTrue(AiThrottlePolicy.shouldRunFullCheck(5, 3, 4));
        assertFalse(AiThrottlePolicy.shouldRunFullCheck(3, 3, 4));
        assertFalse(AiThrottlePolicy.shouldRunFullCheck(4, 3, 4));
    }

    @Test
    void eightTickIntervalIsStaggeredByEntityId() {
        assertTrue(AiThrottlePolicy.shouldRunFullCheck(5, 3, 8));
        assertFalse(AiThrottlePolicy.shouldRunFullCheck(9, 3, 8));
        assertFalse(AiThrottlePolicy.shouldRunFullCheck(11, 3, 8));
    }

    @Test
    void preservesVanillaStartupChecks() {
        assertTrue(AiThrottlePolicy.shouldRunFullCheck(0, 9, 8));
        assertTrue(AiThrottlePolicy.shouldRunFullCheck(1, 9, 8));
    }

    @Test
    void unsupportedIntervalsFallBackToVanilla() {
        assertEquals(2, AiThrottlePolicy.normalizeInterval(3));
        assertEquals(2, AiThrottlePolicy.normalizeInterval(7));
        assertTrue(AiThrottlePolicy.shouldRunFullCheck(5, 3, 7));
    }
}

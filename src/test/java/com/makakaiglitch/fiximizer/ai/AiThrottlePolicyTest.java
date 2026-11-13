package com.makakaiglitch.fiximizer.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiThrottlePolicyTest {
    @Test
    void vanillaIntervalAlwaysRunsFullChecksAtVanillaCallSites() {
        for (long tick = 0; tick < 20; tick++) {
            assertTrue(AiThrottlePolicy.shouldRunFullCheck(tick, 17, 2));
        }
    }

    @Test
    void fourTickIntervalIsStaggeredWithinVanillaFullCheckSlots() {
        assertTrue(AiThrottlePolicy.shouldRunFullCheck(2, 3, 4));
        assertFalse(AiThrottlePolicy.shouldRunFullCheck(4, 3, 4));
        assertFalse(AiThrottlePolicy.shouldRunFullCheck(5, 3, 4));
    }

    @Test
    void eightTickIntervalIsStaggeredWithinVanillaFullCheckSlots() {
        assertTrue(AiThrottlePolicy.shouldRunFullCheck(2, 3, 8));
        assertFalse(AiThrottlePolicy.shouldRunFullCheck(6, 3, 8));
        assertFalse(AiThrottlePolicy.shouldRunFullCheck(8, 3, 8));
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

    @Test
    void fullChecksKeepTheRequestedIntervalAtVanillaFullCheckSlots() {
        for (int entityId = 1; entityId <= 32; entityId++) {
            for (int interval : new int[]{4, 8}) {
                List<Long> fullCheckTicks = new ArrayList<>();
                for (long tick = 2; tick < 256; tick++) {
                    boolean vanillaFullCheckSlot = tick % 2L == 0L;
                    if (vanillaFullCheckSlot
                            && AiThrottlePolicy.shouldRunFullCheck(tick, entityId, interval)) {
                        fullCheckTicks.add(tick);
                    }
                }

                for (int index = 1; index < fullCheckTicks.size(); index++) {
                    assertEquals(interval,
                            fullCheckTicks.get(index) - fullCheckTicks.get(index - 1),
                            "Unexpected interval for entity " + entityId);
                }
            }
        }
    }

    @Test
    void fullChecksAreEvenlyDistributedAcrossEntityIds() {
        int entityCount = 64;
        for (int interval : new int[]{4, 8}) {
            for (long tick = 2; tick < 32; tick += 2) {
                int checks = 0;
                for (int entityId = 1; entityId <= entityCount; entityId++) {
                    if (AiThrottlePolicy.shouldRunFullCheck(tick, entityId, interval)) {
                        checks++;
                    }
                }
                assertEquals(entityCount / interval, checks,
                        "Checks should be staggered for interval " + interval + " at tick " + tick);
            }
        }
    }
}

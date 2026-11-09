package com.makakaiglitch.fiximizer.startup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StableClientStartupGateTest {
    @Test
    void triggersExactlyOnTheRequiredStableTick() {
        StableClientStartupGate gate = new StableClientStartupGate(40);

        for (int tick = 1; tick < 40; tick++) {
            assertFalse(gate.onClientTick(true), "must not trigger on tick " + tick);
        }

        assertTrue(gate.onClientTick(true));
        assertEquals(40, gate.stableTicks());
        assertTrue(gate.completed());
    }

    @Test
    void resetsProgressWhenTheClientBecomesUnready() {
        StableClientStartupGate gate = new StableClientStartupGate(3);

        assertFalse(gate.onClientTick(true));
        assertFalse(gate.onClientTick(true));
        assertFalse(gate.onClientTick(false));
        assertEquals(0, gate.stableTicks());

        assertFalse(gate.onClientTick(true));
        assertFalse(gate.onClientTick(true));
        assertTrue(gate.onClientTick(true));
    }

    @Test
    void completionIsLatchedAndNeverRunsTwice() {
        StableClientStartupGate gate = new StableClientStartupGate(1);

        assertTrue(gate.onClientTick(true));
        assertFalse(gate.onClientTick(false));
        assertFalse(gate.onClientTick(true));
        assertTrue(gate.completed());
        assertEquals(1, gate.stableTicks());
    }

    @Test
    void rejectsNonPositiveThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new StableClientStartupGate(0));
        assertThrows(IllegalArgumentException.class, () -> new StableClientStartupGate(-1));
    }
}

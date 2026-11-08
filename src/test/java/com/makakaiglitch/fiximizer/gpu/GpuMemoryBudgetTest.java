package com.makakaiglitch.fiximizer.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GpuMemoryBudgetTest {
    private static final long GIB = GpuMemoryBudget.GIB;
    private static final long MIB = 1024L * 1024L;

    @Test
    void keepsAtLeastGraphicsReserveFree() {
        long budget = GpuMemoryBudget.safeSpillBudget(
                8L * GIB,
                1L * GIB,
                256L * MIB
        );

        assertEquals(256L * MIB, budget);
        assertTrue(budget <= 8L * GIB - 1L * GIB - 1536L * MIB);
    }

    @Test
    void returnsZeroWhenTheGraphicsReserveWouldBeConsumed() {
        assertEquals(
                0L,
                GpuMemoryBudget.safeSpillBudget(8L * GIB, 7L * GIB, GIB)
        );
    }

    @Test
    void rejectsUnknownVramTelemetry() {
        assertEquals(0L, GpuMemoryBudget.safeSpillBudget(-1L, 0L, GIB));
        assertEquals(0L, GpuMemoryBudget.safeSpillBudget(8L * GIB, -1L, GIB));
    }

    @Test
    void clampsToConfiguredLimit() {
        assertEquals(
                192L * MIB,
                GpuMemoryBudget.safeSpillBudget(
                        8L * GIB,
                        0L,
                        192L * MIB
                )
        );
    }

    @Test
    void avoidsUnderflowWhenUsedMemoryExceedsTotal() {
        assertEquals(
                0L,
                GpuMemoryBudget.safeSpillBudget(4L * GIB, 6L * GIB, GIB)
        );
    }
}

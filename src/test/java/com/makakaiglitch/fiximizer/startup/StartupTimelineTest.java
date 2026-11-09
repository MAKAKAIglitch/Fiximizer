package com.makakaiglitch.fiximizer.startup;

import org.junit.jupiter.api.Test;

import static com.makakaiglitch.fiximizer.startup.StartupTimeline.Stage.*;
import static org.junit.jupiter.api.Assertions.*;

class StartupTimelineTest {
    @Test
    void recordsEachMilestoneOnlyOnce() {
        StartupTimeline timeline = new StartupTimeline();

        assertTrue(timeline.mark(CLIENT_SETUP_STARTED, 1_000_000L));
        assertFalse(timeline.mark(CLIENT_SETUP_STARTED, 9_000_000L));

        assertEquals(1_000_000L, timeline.timestampNanos(CLIENT_SETUP_STARTED));
    }

    @Test
    void calculatesMonotonicPhaseDuration() {
        StartupTimeline timeline = new StartupTimeline();
        timeline.mark(CLIENT_SETUP_STARTED, 10_000_000L);
        timeline.mark(CLIENT_SETUP_COMPLETED, 13_500_000L);

        assertEquals(3_500_000L, timeline.elapsedNanos(CLIENT_SETUP_STARTED, CLIENT_SETUP_COMPLETED));
        assertEquals(3L, timeline.elapsedMillis(CLIENT_SETUP_STARTED, CLIENT_SETUP_COMPLETED));
    }

    @Test
    void reportsMissingOrOutOfOrderMilestonesAsNotMeasured() {
        StartupTimeline timeline = new StartupTimeline();

        assertEquals(-1L, timeline.elapsedMillis(CLIENT_SETUP_STARTED, CLIENT_SETUP_COMPLETED));
        timeline.mark(CLIENT_SETUP_STARTED, 20_000_000L);
        timeline.mark(CLIENT_SETUP_COMPLETED, 10_000_000L);

        assertEquals(-1L, timeline.elapsedMillis(CLIENT_SETUP_STARTED, CLIENT_SETUP_COMPLETED));
    }

    @Test
    void acceptsNegativeNanoTimeOrigins() {
        StartupTimeline timeline = new StartupTimeline();
        timeline.mark(FIRST_TITLE_SCREEN_TICK, -5_000_000L);
        timeline.mark(STABLE_CLIENT_READY, -2_000_000L);

        assertEquals(3L, timeline.elapsedMillis(FIRST_TITLE_SCREEN_TICK, STABLE_CLIENT_READY));
    }

    @Test
    void rejectsNullStages() {
        StartupTimeline timeline = new StartupTimeline();

        assertThrows(NullPointerException.class, () -> timeline.mark(null, 1L));
        assertThrows(NullPointerException.class, () -> timeline.wasReached(null));
    }
}

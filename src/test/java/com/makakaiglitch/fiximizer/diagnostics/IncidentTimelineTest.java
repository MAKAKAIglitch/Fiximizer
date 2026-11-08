package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IncidentTimelineTest {
    @Test
    void recordsProgressionAndPeakDuration() {
        long startNanos = 10_000L;
        Instant start = Instant.parse("2026-11-08T10:00:00Z");
        IncidentTimeline timeline = new IncidentTimeline("incident-1", start, startNanos, 8);

        IncidentTimeline.Snapshot first = timeline.recordCapture(
                start.plusSeconds(2), startNanos + 2_000_000_000L, 2_500_000_000L);
        IncidentTimeline.Snapshot second = timeline.recordCapture(
                start.plusSeconds(4), startNanos + 4_000_000_000L, 4_000_000_000L);

        assertEquals("incident-1", second.incidentId());
        assertEquals(2, second.totalCaptures());
        assertEquals(2, second.captures().size());
        assertEquals(2_000L, second.captures().getFirst().elapsedMillis());
        assertEquals(4_000L, second.latestDurationMillis());
        assertEquals(4_000L, second.peakDurationMillis());
        assertEquals(1_500L, second.durationChangeMillis());
        assertEquals(1, first.totalCaptures());
    }

    @Test
    void retainsOnlyTheNewestBoundedCaptures() {
        long startNanos = 1_000_000_000L;
        Instant start = Instant.parse("2026-11-08T10:00:00Z");
        IncidentTimeline timeline = new IncidentTimeline("incident-2", start, startNanos, 3);

        for (int index = 1; index <= 5; index++) {
            timeline.recordCapture(
                    start.plusSeconds(index),
                    startNanos + index * 1_000_000_000L,
                    index * 1_000_000_000L
            );
        }

        IncidentTimeline.Snapshot snapshot = timeline.snapshot();
        assertEquals(5, snapshot.totalCaptures());
        assertEquals(3, snapshot.captures().size());
        assertEquals(3, snapshot.captures().getFirst().number());
        assertEquals(5, snapshot.captures().getLast().number());
        assertEquals(5_000L, snapshot.peakDurationMillis());
        assertEquals(1_000L, snapshot.firstDurationMillis());
        assertEquals(4_000L, snapshot.durationChangeMillis());
    }

    @Test
    void rejectsInvalidCapacityAndBlankId() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class,
                () -> new IncidentTimeline("", now, 1L, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new IncidentTimeline("incident", now, 1L, 0));
    }
}

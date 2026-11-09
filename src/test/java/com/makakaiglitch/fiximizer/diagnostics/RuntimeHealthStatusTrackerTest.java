package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeHealthStatusTrackerTest {
    @Test
    void firstObservationRecordsTransitionTime() {
        Map<String, RuntimeHealthService.SubsystemStatus> previous = new LinkedHashMap<>();

        List<RuntimeHealthService.SubsystemStatus> result = RuntimeHealthService.trackForTests(
                List.of(status("network", RuntimeHealthService.State.IDLE, "Waiting")),
                previous,
                1_000L
        );

        assertEquals(1_000L, result.getFirst().lastTransitionEpochMillis());
    }

    @Test
    void unchangedObservationPreservesTransitionTime() {
        Map<String, RuntimeHealthService.SubsystemStatus> previous = new LinkedHashMap<>();
        previous.put("network", status("network", RuntimeHealthService.State.HEALTHY, "Connected", 1_000L));

        List<RuntimeHealthService.SubsystemStatus> result = RuntimeHealthService.trackForTests(
                List.of(status("network", RuntimeHealthService.State.HEALTHY, "Connected")),
                previous,
                2_000L
        );

        assertEquals(1_000L, result.getFirst().lastTransitionEpochMillis());
    }

    @Test
    void stateOrReasonChangeUpdatesTransitionTime() {
        Map<String, RuntimeHealthService.SubsystemStatus> previous = new LinkedHashMap<>();
        previous.put("network", status("network", RuntimeHealthService.State.HEALTHY, "Connected", 1_000L));

        List<RuntimeHealthService.SubsystemStatus> result = RuntimeHealthService.trackForTests(
                List.of(status("network", RuntimeHealthService.State.DEGRADED, "Java fallback")),
                previous,
                3_000L
        );

        assertEquals(3_000L, result.getFirst().lastTransitionEpochMillis());
    }

    @Test
    void tracksEverySubsystemWithoutSharingMutableState() {
        Map<String, RuntimeHealthService.SubsystemStatus> previous = new LinkedHashMap<>();

        List<RuntimeHealthService.SubsystemStatus> result = RuntimeHealthService.trackForTests(
                List.of(
                        status("jfr", RuntimeHealthService.State.HEALTHY, "Active"),
                        status("opencl", RuntimeHealthService.State.UNSUPPORTED, "No device")
                ),
                previous,
                5_000L
        );

        assertEquals(List.of("jfr", "opencl"), result.stream()
                .map(RuntimeHealthService.SubsystemStatus::id).toList());
        assertEquals(5_000L, result.get(1).lastTransitionEpochMillis());
    }

    private static RuntimeHealthService.SubsystemStatus status(
            String id,
            RuntimeHealthService.State state,
            String reason
    ) {
        return status(id, state, reason, 0L);
    }

    private static RuntimeHealthService.SubsystemStatus status(
            String id,
            RuntimeHealthService.State state,
            String reason,
            long transitionAt
    ) {
        return new RuntimeHealthService.SubsystemStatus(
                id, state, reason, "", false, transitionAt
        );
    }
}

package com.makakaiglitch.fiximizer.diagnostics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maintains the last observed state transition time for each subsystem. */
final class RuntimeHealthStatusTracker {
    private final Map<String, RuntimeHealthService.SubsystemStatus> previous = new LinkedHashMap<>();

    synchronized List<RuntimeHealthService.SubsystemStatus> update(
            List<RuntimeHealthService.SubsystemStatus> observations,
            long now
    ) {
        List<RuntimeHealthService.SubsystemStatus> tracked = apply(observations, previous, now);
        previous.clear();
        for (RuntimeHealthService.SubsystemStatus status : tracked) {
            previous.put(status.id(), status);
        }
        return tracked;
    }

    static List<RuntimeHealthService.SubsystemStatus> apply(
            List<RuntimeHealthService.SubsystemStatus> observations,
            Map<String, RuntimeHealthService.SubsystemStatus> previous,
            long now
    ) {
        return observations.stream().map(current -> {
            RuntimeHealthService.SubsystemStatus old = previous.get(current.id());
            boolean unchanged = old != null
                    && old.state() == current.state()
                    && old.reason().equals(current.reason())
                    && old.fallback().equals(current.fallback())
                    && old.userActionRequired() == current.userActionRequired();
            long transitionAt = unchanged ? old.lastTransitionEpochMillis() : now;
            return new RuntimeHealthService.SubsystemStatus(
                    current.id(),
                    current.state(),
                    current.reason(),
                    current.fallback(),
                    current.userActionRequired(),
                    transitionAt
            );
        }).toList();
    }

    synchronized void clear() {
        previous.clear();
    }
}

package com.makakaiglitch.fiximizer.scheduler;

public record SchedulerSnapshot(
        int activeTasks,
        int queuedTasks,
        int poolSize,
        int targetWorkers,
        int maximumWorkers,
        SchedulerTelemetry.Snapshot telemetry,
        String scalingReason
) {
}

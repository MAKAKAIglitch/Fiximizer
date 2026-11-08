package com.makakaiglitch.fiximizer.scheduler;

/**
 * Conservative worker-count policy for independent Fiximizer-owned tasks.
 * It never changes Minecraft or third-party mod executors.
 */
public final class SchedulerLoadController {
    public static final double QUEUE_WAIT_SCALE_UP_MILLIS = 150.0;

    private SchedulerLoadController() {
    }

    public static Decision decide(
            boolean adaptiveEnabled,
            double systemCpuLoad,
            int activeTasks,
            int queuedTasks,
            int currentWorkers,
            int maximumWorkers,
            double queueWaitP95Millis,
            double oldestQueuedAgeMillis,
            OptimizationProfile profile
    ) {
        int maximum = Math.max(1, Math.min(4, maximumWorkers));
        int current = Math.max(1, Math.min(maximum, currentWorkers));

        if (profile == null) {
            return new Decision(current, "no_profile");
        }
        if (!adaptiveEnabled) {
            return new Decision(maximum, "disabled");
        }
        if (!Double.isFinite(systemCpuLoad) || systemCpuLoad < 0.0) {
            return new Decision(current, "cpu_unavailable");
        }

        double cpuLoad = Math.min(1.0, systemCpuLoad);
        if (cpuLoad >= profile.singleWorkerAtCpuLoad()) {
            return new Decision(1, "cpu_critical");
        }
        if (cpuLoad >= profile.reduceWorkersAtCpuLoad()) {
            return new Decision(
                    Math.max(1, current - 1),
                    "cpu_elevated"
            );
        }

        boolean sustainedQueuePressure = queuedTasks > 0
                && activeTasks >= current
                && Math.max(
                        Double.isFinite(queueWaitP95Millis) ? queueWaitP95Millis : 0.0,
                        Double.isFinite(oldestQueuedAgeMillis) ? oldestQueuedAgeMillis : 0.0
                ) >= QUEUE_WAIT_SCALE_UP_MILLIS;
        if (sustainedQueuePressure && current < maximum) {
            return new Decision(
                    current + 1,
                    "queue_pressure"
            );
        }

        if (activeTasks == 0 && queuedTasks == 0 && current > 1) {
            return new Decision(
                    current - 1,
                    "idle"
            );
        }

        return new Decision(current, "balanced");
    }

    public record Decision(int targetWorkers, String reasonKey) {
    }
}

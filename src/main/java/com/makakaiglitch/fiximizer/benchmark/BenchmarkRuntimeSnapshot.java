package com.makakaiglitch.fiximizer.benchmark;

import com.makakaiglitch.fiximizer.gpu.GpuReading;
import com.makakaiglitch.fiximizer.gpu.GpuTelemetryService;
import com.makakaiglitch.fiximizer.profiler.FlightRecorderService;
import com.makakaiglitch.fiximizer.scheduler.AdaptiveTaskScheduler;
import com.makakaiglitch.fiximizer.scheduler.SchedulerSnapshot;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/** Point-in-time runtime counters captured outside the measured sample window. */
public record BenchmarkRuntimeSnapshot(
        long capturedAtEpochMillis,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        int activeThreadCount,
        double systemCpuLoad,
        double processCpuLoad,
        long gcCollectionCount,
        long gcCollectionTimeMillis,
        int schedulerActiveTasks,
        int schedulerQueuedTasks,
        int schedulerWorkers,
        double schedulerQueueWaitP95Millis,
        double schedulerExecutionP95Millis,
        long schedulerFailedTasks,
        long schedulerRejectedTasks,
        String jfrUserRecordingState,
        boolean jfrRollingBufferActive,
        List<GpuMetrics> gpuMetrics
) {
    public BenchmarkRuntimeSnapshot {
        gpuMetrics = List.copyOf(gpuMetrics);
    }

    public static BenchmarkRuntimeSnapshot capture() {
        Runtime runtime = Runtime.getRuntime();
        long committed = runtime.totalMemory();
        long used = committed - runtime.freeMemory();

        double systemCpu = -1.0;
        double processCpu = -1.0;
        java.lang.management.OperatingSystemMXBean base =
                ManagementFactory.getOperatingSystemMXBean();
        if (base instanceof com.sun.management.OperatingSystemMXBean extended) {
            systemCpu = extended.getCpuLoad();
            processCpu = extended.getProcessCpuLoad();
        }

        long gcCount = 0L;
        long gcTime = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionCount() > 0L) {
                gcCount += bean.getCollectionCount();
            }
            if (bean.getCollectionTime() > 0L) {
                gcTime += bean.getCollectionTime();
            }
        }

        SchedulerSnapshot scheduler = AdaptiveTaskScheduler.snapshot();
        var schedulerTelemetry = scheduler.telemetry();
        List<GpuMetrics> gpu = GpuTelemetryService.latest().stream()
                .filter(GpuReading::hasUtilization)
                .map(reading -> new GpuMetrics(
                        reading.name(),
                        reading.vendor(),
                        reading.utilizationPercent(),
                        reading.vramUsedBytes(),
                        reading.vramTotalBytes(),
                        reading.temperatureCelsius(),
                        reading.powerWatts()
                ))
                .toList();

        return new BenchmarkRuntimeSnapshot(
                System.currentTimeMillis(),
                used,
                committed,
                runtime.maxMemory(),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                systemCpu,
                processCpu,
                gcCount,
                gcTime,
                scheduler.activeTasks(),
                scheduler.queuedTasks(),
                scheduler.poolSize(),
                schedulerTelemetry.queueWaitP95Millis(),
                schedulerTelemetry.executionP95Millis(),
                schedulerTelemetry.failedTasks(),
                schedulerTelemetry.rejectedTasks(),
                FlightRecorderService.state().name(),
                FlightRecorderService.rollingActive(),
                gpu
        );
    }

    public record GpuMetrics(
            String adapterName,
            String vendor,
            double utilizationPercent,
            long vramUsedBytes,
            long vramTotalBytes,
            double temperatureCelsius,
            double powerWatts
    ) {
    }
}

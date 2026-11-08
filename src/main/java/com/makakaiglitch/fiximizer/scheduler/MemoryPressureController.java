package com.makakaiglitch.fiximizer.scheduler;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;

import java.lang.management.ManagementFactory;

/**
 * Hysteresis-based memory guard. It only changes Fiximizer's own strategy;
 * it never invokes System.gc(), changes -Xmx, or unloads objects from other mods.
 */
public final class MemoryPressureController {
    private static final double CRITICAL_HEAP_USAGE = 0.96;
    private static final double CRITICAL_PHYSICAL_FREE = 0.035;
    private static final double RECOVERY_PHYSICAL_FREE = 0.12;
    private static final long MIN_PHYSICAL_FREE_BYTES = 768L * 1024L * 1024L;

    private static int highPressureSamples;
    private static int recoverySamples;
    private static volatile boolean memoryPressure;
    private static volatile double heapUsagePercent;
    private static volatile double physicalFreePercent = -1.0;
    private static volatile String reason = "Memory guard is monitoring";

    private MemoryPressureController() {
    }

    public static synchronized void sample(long usedHeapBytes, long maxHeapBytes) {
        if (!FiximizerConfig.ENABLE_MEMORY_GUARD.get()) {
            clear("Automatic memory guard is disabled");
            return;
        }

        heapUsagePercent = maxHeapBytes <= 0L
                ? 0.0
                : clampPercent(usedHeapBytes * 100.0 / maxHeapBytes);

        long freePhysicalBytes = -1L;
        long totalPhysicalBytes = -1L;
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            freePhysicalBytes = extended.getFreeMemorySize();
            totalPhysicalBytes = extended.getTotalMemorySize();
        }

        physicalFreePercent = totalPhysicalBytes > 0L && freePhysicalBytes >= 0L
                ? clampPercent(freePhysicalBytes * 100.0 / totalPhysicalBytes)
                : -1.0;

        int highThreshold = FiximizerConfig.MEMORY_PRESSURE_THRESHOLD.get();
        int recoveryThreshold = Math.min(
                FiximizerConfig.MEMORY_RECOVERY_THRESHOLD.get(),
                highThreshold - 5
        );

        boolean lowPhysicalMemory = freePhysicalBytes >= 0L
                && (freePhysicalBytes < MIN_PHYSICAL_FREE_BYTES
                || (physicalFreePercent >= 0.0 && physicalFreePercent <= CRITICAL_PHYSICAL_FREE * 100.0));

        boolean critical = heapUsagePercent >= CRITICAL_HEAP_USAGE * 100.0
                || (lowPhysicalMemory && heapUsagePercent >= highThreshold - 8.0);

        boolean high = heapUsagePercent >= highThreshold
                || (lowPhysicalMemory && heapUsagePercent >= highThreshold - 5.0);

        if (!memoryPressure) {
            recoverySamples = 0;
            if (critical) {
                activate(lowPhysicalMemory
                        ? "Critical free system memory and high Java heap usage"
                        : "Java heap usage reached the critical threshold");
            } else if (high) {
                highPressureSamples++;
                if (highPressureSamples >= 3) {
                    activate(lowPhysicalMemory
                            ? "Low free system memory for several samples"
                            : "Java heap remained above the memory threshold");
                }
            } else {
                highPressureSamples = 0;
                reason = "Memory pressure is normal";
            }
            return;
        }

        boolean recoveredHeap = heapUsagePercent <= recoveryThreshold;
        boolean recoveredPhysical = physicalFreePercent < 0.0
                || physicalFreePercent >= RECOVERY_PHYSICAL_FREE * 100.0;

        if (recoveredHeap && recoveredPhysical) {
            recoverySamples++;
            if (recoverySamples >= 3) {
                memoryPressure = false;
                highPressureSamples = 0;
                recoverySamples = 0;
                reason = "Memory recovered; returning to selected profile";
                OptimizationProfileManager.setMemoryPressure(false);
                Fiximizer.LOGGER.info("Memory pressure cleared; restored the selected Fiximizer profile.");
            }
        } else {
            recoverySamples = 0;
        }
    }

    public static synchronized void reset() {
        clear("Memory guard reset");
    }

    private static void activate(String explanation) {
        memoryPressure = true;
        highPressureSamples = 0;
        recoverySamples = 0;
        reason = explanation + "; temporary Memory Saver override is active";
        OptimizationProfileManager.setMemoryPressure(true);
        Fiximizer.LOGGER.warn("Fiximizer automatic Memory Saver activated: {}", explanation);
    }

    private static void clear(String message) {
        memoryPressure = false;
        highPressureSamples = 0;
        recoverySamples = 0;
        reason = message;
        OptimizationProfileManager.setMemoryPressure(false);
    }

    private static double clampPercent(double value) {
        if (!Double.isFinite(value)) {
            return -1.0;
        }
        return Math.max(0.0, Math.min(100.0, value));
    }

    public static boolean active() {
        return memoryPressure;
    }

    public static double heapUsagePercent() {
        return heapUsagePercent;
    }

    public static double physicalFreePercent() {
        return physicalFreePercent;
    }

    public static String reason() {
        return reason;
    }
}

package com.makakaiglitch.fiximizer.diagnostics;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.scheduler.AdaptiveTaskScheduler;
import net.neoforged.fml.ModList;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class DiagnosticsService {
    private static volatile DiagnosticsSnapshot latest = DiagnosticsSnapshot.empty();
    private static long nextSampleAtNanos;

    private DiagnosticsService() {
    }

    public static void tick() {
        if (!FiximizerConfig.ENABLE_DIAGNOSTICS.get()) {
            return;
        }

        long now = System.nanoTime();
        if (now < nextSampleAtNanos) {
            return;
        }

        sampleNow();
        long interval = TimeUnit.SECONDS.toNanos(FiximizerConfig.SAMPLE_INTERVAL_SECONDS.get());
        nextSampleAtNanos = now + interval;
    }

    public static void sampleNow() {
        Runtime runtime = Runtime.getRuntime();
        long committed = runtime.totalMemory();
        long used = committed - runtime.freeMemory();

        double systemCpuLoad = -1.0;
        double processCpuLoad = -1.0;
        java.lang.management.OperatingSystemMXBean baseBean =
                ManagementFactory.getOperatingSystemMXBean();

        // The com.sun management extension is included in the Java 21 runtime used by Minecraft.
        if (baseBean instanceof com.sun.management.OperatingSystemMXBean extendedBean) {
            systemCpuLoad = extendedBean.getCpuLoad();
            processCpuLoad = extendedBean.getProcessCpuLoad();
        }

        int activeThreads = ManagementFactory.getThreadMXBean().getThreadCount();

        latest = new DiagnosticsSnapshot(
                System.currentTimeMillis(),
                used,
                committed,
                runtime.maxMemory(),
                runtime.availableProcessors(),
                ModList.get().getMods().size(),
                activeThreads,
                systemCpuLoad,
                processCpuLoad
        );

        AdaptiveTaskScheduler.adaptToCpuLoad(systemCpuLoad);
    }

    public static DiagnosticsSnapshot latest() {
        return latest;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0L) {
            return "n/a";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }

        double kib = bytes / 1024.0;
        if (kib < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KiB", kib);
        }

        double mib = kib / 1024.0;
        if (mib < 1024.0) {
            return String.format(Locale.ROOT, "%.1f MiB", mib);
        }

        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0);
    }

    public static String formatPercent(double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0.0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1f%%", Math.min(1.0, fraction) * 100.0);
    }
}

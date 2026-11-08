package com.makakaiglitch.fiximizer.diagnostics;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
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
        double loadAverage = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();

        latest = new DiagnosticsSnapshot(
                System.currentTimeMillis(),
                used,
                committed,
                runtime.maxMemory(),
                runtime.availableProcessors(),
                ModList.get().getMods().size(),
                loadAverage
        );
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
}

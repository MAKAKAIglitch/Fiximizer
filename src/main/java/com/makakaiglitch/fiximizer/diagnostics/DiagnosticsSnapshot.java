package com.makakaiglitch.fiximizer.diagnostics;

public record DiagnosticsSnapshot(
        long sampledAtMillis,
        long usedHeapBytes,
        long committedHeapBytes,
        long maxHeapBytes,
        int availableProcessors,
        int loadedModCount,
        double systemLoadAverage
) {
    public static DiagnosticsSnapshot empty() {
        return new DiagnosticsSnapshot(
                0L,
                0L,
                0L,
                Runtime.getRuntime().maxMemory(),
                Runtime.getRuntime().availableProcessors(),
                0,
                -1.0
        );
    }

    public boolean hasSample() {
        return sampledAtMillis > 0L;
    }

    public double maxHeapUsagePercent() {
        if (maxHeapBytes <= 0L) {
            return 0.0;
        }
        return Math.min(100.0, (usedHeapBytes * 100.0) / maxHeapBytes);
    }
}

package com.makakaiglitch.fiximizer.diagnostics;

/**
 * Combines independent signals for investigation prioritization.
 * This is deliberately not a probability or a claim of causation.
 */
public final class ModCorrelationScorer {
    private ModCorrelationScorer() {
    }

    public static double score(
            int triggerTopFrames,
            int triggerFrames,
            long triggerThreadCpuSamples,
            long modCpuSamples,
            long totalAttributedCpuSamples,
            long sampledAllocationBytes,
            long allSampledAllocationBytes
    ) {
        double stackSignal = Math.max(0, triggerTopFrames) * 24.0
                + Math.max(0, triggerFrames) * 4.0;
        double triggerCpuSignal = Math.min(100, Math.max(0L, triggerThreadCpuSamples)) * 1.5;
        double allCpuShare = percentage(modCpuSamples, totalAttributedCpuSamples);
        double allocationShare = percentage(sampledAllocationBytes, allSampledAllocationBytes);
        return stackSignal
                + triggerCpuSignal
                + allCpuShare * 0.20
                + allocationShare * 0.15;
    }

    public static String confidence(
            int triggerTopFrames,
            int triggerFrames,
            long triggerThreadCpuSamples,
            long modCpuSamples,
            long sampledAllocationBytes
    ) {
        int signals = 0;
        if (triggerTopFrames > 0 && triggerFrames >= 2) signals++;
        if (triggerThreadCpuSamples >= 3 && modCpuSamples >= triggerThreadCpuSamples) signals++;
        if (sampledAllocationBytes > 0L) signals++;

        if (triggerTopFrames >= 3
                && triggerFrames >= 5
                && triggerThreadCpuSamples >= 5
                && signals >= 2) {
            return "HIGH";
        }
        if (signals >= 2 || (triggerTopFrames >= 1 && triggerFrames >= 2)
                || triggerThreadCpuSamples >= 5) {
            return "MEDIUM";
        }
        return "LOW";
    }

    public static double percentage(long value, long total) {
        if (value <= 0L || total <= 0L) return 0.0;
        return Math.min(100.0, value * 100.0 / total);
    }
}

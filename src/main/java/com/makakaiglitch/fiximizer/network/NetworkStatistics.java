package com.makakaiglitch.fiximizer.network;

import java.util.Arrays;

/** Allocation-bounded statistics helpers for optional network diagnostics. */
public final class NetworkStatistics {
    public static final int MAX_SAMPLES = 4096;

    private NetworkStatistics() {
    }

    public static boolean summarizeJava(long[] samples, long[] output) {
        if (!valid(samples, output)) return false;
        long[] sorted = Arrays.copyOf(samples, samples.length);
        Arrays.sort(sorted);
        output[0] = percentile(sorted, 50);
        output[1] = percentile(sorted, 95);
        output[2] = percentile(sorted, 99);
        return true;
    }

    static boolean valid(long[] samples, long[] output) {
        return samples != null && output != null && output.length >= 3
                && samples.length > 0 && samples.length <= MAX_SAMPLES;
    }

    private static long percentile(long[] sorted, int percentile) {
        return sorted[(int) (((long) (sorted.length - 1) * percentile) / 100L)];
    }
}

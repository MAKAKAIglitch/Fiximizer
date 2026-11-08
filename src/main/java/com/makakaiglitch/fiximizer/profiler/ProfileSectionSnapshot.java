package com.makakaiglitch.fiximizer.profiler;

/**
 * Aggregate timing for a named Fiximizer-owned section.
 * Times are cumulative for the current game session.
 */
public record ProfileSectionSnapshot(
        String name,
        long calls,
        long totalNanos,
        long maxNanos
) {
    public double averageMillis() {
        return calls == 0L ? 0.0 : (totalNanos / (double) calls) / 1_000_000.0;
    }

    public double totalMillis() {
        return totalNanos / 1_000_000.0;
    }

    public double maxMillis() {
        return maxNanos / 1_000_000.0;
    }
}

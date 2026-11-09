package com.makakaiglitch.fiximizer.benchmark;

/** Comparison of two runs from the same scenario and compatible environment. */
public record BenchmarkComparison(
        boolean compatible,
        String reason,
        MetricDelta renderCall,
        MetricDelta clientTick,
        MetricDelta integratedServerTick
) {
    public static BenchmarkComparison incompatible(String reason) {
        return new BenchmarkComparison(false, reason, null, null, null);
    }

    public static BenchmarkComparison compare(BenchmarkRun baseline, BenchmarkRun candidate) {
        if (baseline == null || candidate == null) {
            return incompatible("Both a baseline and a candidate run are required.");
        }
        if (baseline.schemaVersion() != BenchmarkRun.CURRENT_SCHEMA_VERSION
                || candidate.schemaVersion() != BenchmarkRun.CURRENT_SCHEMA_VERSION) {
            return incompatible("The benchmark schema version is not supported.");
        }
        if (!baseline.valid() || !candidate.valid()) {
            return incompatible("Both runs must meet the minimum sample requirements.");
        }
        if (baseline.scenario() != candidate.scenario()) {
            return incompatible("The selected scenarios differ.");
        }
        if (!baseline.compatibilityFingerprint().equals(candidate.compatibilityFingerprint())) {
            return incompatible("Minecraft, NeoForge, Java, hardware, modpack or settings fingerprints differ.");
        }

        return new BenchmarkComparison(
                true,
                "Runs use the same scenario and compatible environment.",
                delta(baseline.renderCall(), candidate.renderCall()),
                delta(baseline.clientTick(), candidate.clientTick()),
                delta(baseline.integratedServerTick(), candidate.integratedServerTick())
        );
    }

    private static MetricDelta delta(
            BenchmarkSampleWindow.Statistics baseline,
            BenchmarkSampleWindow.Statistics candidate
    ) {
        if (baseline == null || candidate == null
                || !baseline.hasSamples() || !candidate.hasSamples()) {
            return null;
        }
        double p95Change = candidate.p95Millis() - baseline.p95Millis();
        boolean percentAvailable = baseline.p95Millis() > 0.0;
        double percentChange = percentAvailable
                ? p95Change / baseline.p95Millis() * 100.0
                : 0.0;
        return new MetricDelta(
                baseline.sampleCount(),
                candidate.sampleCount(),
                candidate.p50Millis() - baseline.p50Millis(),
                p95Change,
                candidate.p99Millis() - baseline.p99Millis(),
                percentChange,
                percentAvailable,
                p95Change < 0.0
        );
    }

    public record MetricDelta(
            int baselineSamples,
            int candidateSamples,
            double p50ChangeMillis,
            double p95ChangeMillis,
            double p99ChangeMillis,
            double p95ChangePercent,
            boolean p95PercentAvailable,
            boolean p95Improved
    ) {
    }
}

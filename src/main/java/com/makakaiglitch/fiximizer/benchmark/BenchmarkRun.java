package com.makakaiglitch.fiximizer.benchmark;

import java.time.Instant;

/** Immutable result of one manually selected workload run. */
public record BenchmarkRun(
        int schemaVersion,
        String id,
        long startedAtEpochMillis,
        long measurementStartedAtEpochMillis,
        long completedAtEpochMillis,
        BenchmarkScenario scenario,
        BenchmarkEnvironment environment,
        int warmupSeconds,
        int measurementSeconds,
        BenchmarkSampleWindow.Statistics renderCall,
        BenchmarkSampleWindow.Statistics clientTick,
        BenchmarkSampleWindow.Statistics integratedServerTick,
        boolean valid,
        String validationMessage
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public String compatibilityFingerprint() {
        return environment.compatibilityFingerprint(scenario);
    }

    public Instant startedAt() {
        return Instant.ofEpochMilli(startedAtEpochMillis);
    }

    public Instant completedAt() {
        return Instant.ofEpochMilli(completedAtEpochMillis);
    }
}

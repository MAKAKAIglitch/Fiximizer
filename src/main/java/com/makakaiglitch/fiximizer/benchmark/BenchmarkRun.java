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
        BenchmarkRuntimeSnapshot runtimeBefore,
        BenchmarkRuntimeSnapshot runtimeAfter,
        boolean valid,
        String validationMessage
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int WARMUP_SECONDS = 10;
    public static final int MEASUREMENT_SECONDS = 30;
    public static final int MIN_RENDER_SAMPLES = 30;
    public static final int MIN_CLIENT_TICK_SAMPLES = 10;
    public static final int MIN_SERVER_TICK_SAMPLES = 10;

    public boolean usesCurrentProtocol() {
        return warmupSeconds == WARMUP_SECONDS
                && measurementSeconds == MEASUREMENT_SECONDS;
    }

    public boolean hasMinimumCoverage() {
        return scenario != null
                && renderCall != null
                && clientTick != null
                && integratedServerTick != null
                && renderCall.sampleCount() >= MIN_RENDER_SAMPLES
                && clientTick.sampleCount() >= MIN_CLIENT_TICK_SAMPLES
                && (!scenario.expectsIntegratedServer()
                        || integratedServerTick.sampleCount() >= MIN_SERVER_TICK_SAMPLES);
    }

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

package com.makakaiglitch.fiximizer.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkComparisonTest {
    @Test
    void comparesP50P95AndP99ForCompatibleRuns() {
        BenchmarkEnvironment environment = environment("0.14.0-alpha", "settings-a");
        BenchmarkRun baseline = run("baseline", environment, stats(20, 40, 60), true);
        BenchmarkRun candidate = run("candidate", environment("0.15.0-alpha", "settings-a"),
                stats(18, 30, 50), true);

        BenchmarkComparison result = BenchmarkComparison.compare(baseline, candidate);

        assertTrue(result.compatible());
        assertNotNull(result.renderCall());
        assertEquals(-2.0, result.renderCall().p50ChangeMillis(), 0.0001);
        assertEquals(-10.0, result.renderCall().p95ChangeMillis(), 0.0001);
        assertEquals(-10.0, result.renderCall().p99ChangeMillis(), 0.0001);
        assertEquals(-25.0, result.renderCall().p95ChangePercent(), 0.0001);
        assertTrue(result.renderCall().p95Improved());
        assertNull(result.integratedServerTick());
    }

    @Test
    void rejectsDifferentScenarioAndEnvironmentFingerprints() {
        BenchmarkRun baseline = run("baseline", environment("0.14.0-alpha", "settings-a"),
                stats(20, 40, 60), true);
        BenchmarkRun otherSettings = run("candidate",
                environment("0.14.0-alpha", "settings-b"), stats(18, 30, 50), true);

        BenchmarkComparison settingsResult = BenchmarkComparison.compare(baseline, otherSettings);
        assertFalse(settingsResult.compatible());
        assertNull(settingsResult.renderCall());

        BenchmarkRun otherScenario = new BenchmarkRun(
                BenchmarkRun.CURRENT_SCHEMA_VERSION,
                "other-scenario",
                1_000L,
                2_000L,
                3_000L,
                BenchmarkScenario.MENU_IDLE,
                baseline.environment(),
                10,
                30,
                stats(18, 30, 50),
                stats(18, 30, 50),
                emptyStats(),
                runtime(),
                runtime(),
                true,
                "valid"
        );
        assertFalse(BenchmarkComparison.compare(baseline, otherScenario).compatible());
    }

    @Test
    void rejectsDifferentProtocolAndForgedCoverage() {
        BenchmarkRun baseline = run("baseline", environment("0.14.0-alpha", "settings-a"),
                stats(20, 40, 60), true);

        BenchmarkRun differentProtocol = new BenchmarkRun(
                baseline.schemaVersion(), "different-protocol",
                baseline.startedAtEpochMillis(), baseline.measurementStartedAtEpochMillis(),
                baseline.completedAtEpochMillis(), baseline.scenario(), baseline.environment(),
                5, 60, baseline.renderCall(), baseline.clientTick(), baseline.integratedServerTick(),
                baseline.runtimeBefore(), baseline.runtimeAfter(), true, "claimed valid"
        );
        assertFalse(BenchmarkComparison.compare(baseline, differentProtocol).compatible());

        BenchmarkSampleWindow.Statistics tooFew = new BenchmarkSampleWindow.Statistics(
                5, 5L, 0L, 20.0, 1.0, 10.0, 18.0, 24.0, 30.0, 40.0
        );
        BenchmarkRun forgedValid = new BenchmarkRun(
                baseline.schemaVersion(), "forged-valid",
                baseline.startedAtEpochMillis(), baseline.measurementStartedAtEpochMillis(),
                baseline.completedAtEpochMillis(), baseline.scenario(), baseline.environment(),
                baseline.warmupSeconds(), baseline.measurementSeconds(), tooFew,
                baseline.clientTick(), baseline.integratedServerTick(),
                baseline.runtimeBefore(), baseline.runtimeAfter(), true, "claimed valid"
        );
        assertFalse(BenchmarkComparison.compare(baseline, forgedValid).compatible());
    }

    @Test
    void rejectsIncompleteRuns() {
        BenchmarkRun baseline = run("baseline", environment("0.14.0-alpha", "settings-a"),
                stats(20, 40, 60), true);
        BenchmarkRun incomplete = run("incomplete", environment("0.14.0-alpha", "settings-a"),
                stats(18, 30, 50), false);

        BenchmarkComparison result = BenchmarkComparison.compare(baseline, incomplete);

        assertFalse(result.compatible());
        assertTrue(result.reason().contains("minimum sample"));
    }

    private static BenchmarkRun run(
            String id,
            BenchmarkEnvironment environment,
            BenchmarkSampleWindow.Statistics render,
            boolean valid
    ) {
        return new BenchmarkRun(
                BenchmarkRun.CURRENT_SCHEMA_VERSION,
                id,
                1_000L,
                2_000L,
                3_000L,
                BenchmarkScenario.STABLE_SCENE,
                environment,
                10,
                30,
                render,
                stats(10, 20, 30),
                emptyStats(),
                runtime(),
                runtime(),
                valid,
                valid ? "valid" : "not enough samples"
        );
    }

    private static BenchmarkEnvironment environment(String fiximizerVersion, String settingsFingerprint) {
        return new BenchmarkEnvironment(
                "1.21.1", "21.1.265", fiximizerVersion, "21.0.5", "Eclipse Adoptium",
                "Windows 11", "amd64", 12, 8L * 1024 * 1024 * 1024,
                45, "mod-fingerprint", 12, 8, "smoothness", settingsFingerprint, "hardware"
        );
    }

    private static BenchmarkSampleWindow.Statistics stats(double p50, double p95, double p99) {
        return new BenchmarkSampleWindow.Statistics(
                100, 100, 0, (p50 + p95 + p99) / 3.0, 1.0,
                p50, p50, p95, p99, p99
        );
    }

    private static BenchmarkSampleWindow.Statistics emptyStats() {
        return new BenchmarkSampleWindow.Statistics(0, 0L, 0L,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    private static BenchmarkRuntimeSnapshot runtime() {
        return new BenchmarkRuntimeSnapshot(
                1_000L, 100L, 200L, 300L, 12,
                -1.0, -1.0, 0L, 0L,
                0, 0, 1, 0.0, 0.0, 0L, 0L,
                "IDLE", false, List.of()
        );
    }
}

package com.makakaiglitch.fiximizer.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesLoadsExportsAndImportsVersionedRun() throws Exception {
        BenchmarkRun run = sampleRun();

        Path saved = BenchmarkRepository.saveRun(temporaryDirectory, run);
        assertTrue(Files.isRegularFile(saved));
        assertEquals(run, BenchmarkRepository.read(saved));

        Path baseline = BenchmarkRepository.saveBaseline(temporaryDirectory, run);
        assertTrue(Files.isRegularFile(baseline));
        assertEquals(run, BenchmarkRepository.loadBaseline(temporaryDirectory, run).orElseThrow());

        Path exported = temporaryDirectory.resolve("share").resolve("benchmark.json");
        BenchmarkRepository.exportJson(saved, exported);
        assertEquals(run, BenchmarkRepository.read(exported));

        Path importedDirectory = temporaryDirectory.resolve("second-profile");
        Path importedBaseline = BenchmarkRepository.importBaseline(exported, importedDirectory);
        assertTrue(Files.isRegularFile(importedBaseline));
        assertEquals(run, BenchmarkRepository.loadBaseline(importedDirectory, run).orElseThrow());
    }

    @Test
    void refusesForgedValidBaselineWithoutMinimumCoverage() {
        BenchmarkRun sample = sampleRun();
        BenchmarkSampleWindow.Statistics tooFew = new BenchmarkSampleWindow.Statistics(
                2, 2L, 0L, 20.0, 1.0, 10.0, 18.0, 24.0, 30.0, 40.0
        );
        BenchmarkRun forged = new BenchmarkRun(
                sample.schemaVersion(), sample.id(), sample.startedAtEpochMillis(),
                sample.measurementStartedAtEpochMillis(), sample.completedAtEpochMillis(),
                sample.scenario(), sample.environment(), sample.warmupSeconds(),
                sample.measurementSeconds(), tooFew, sample.clientTick(),
                sample.integratedServerTick(), sample.runtimeBefore(), sample.runtimeAfter(),
                true, "claimed valid"
        );

        assertThrows(java.io.IOException.class,
                () -> BenchmarkRepository.saveBaseline(temporaryDirectory, forged));
    }

    @Test
    void runIdCannotEscapeBenchmarkDirectory() throws Exception {
        BenchmarkRun unsafeId = new BenchmarkRun(
                sampleRun().schemaVersion(),
                "../../outside",
                sampleRun().startedAtEpochMillis(),
                sampleRun().measurementStartedAtEpochMillis(),
                sampleRun().completedAtEpochMillis(),
                sampleRun().scenario(),
                sampleRun().environment(),
                sampleRun().warmupSeconds(),
                sampleRun().measurementSeconds(),
                sampleRun().renderCall(),
                sampleRun().clientTick(),
                sampleRun().integratedServerTick(),
                sampleRun().runtimeBefore(),
                sampleRun().runtimeAfter(),
                true,
                "valid"
        );

        Path saved = BenchmarkRepository.saveRun(temporaryDirectory, unsafeId);

        assertEquals(BenchmarkRepository.directory(temporaryDirectory), saved.getParent());
        assertTrue(saved.getFileName().toString().startsWith("run-"));
    }

    private static BenchmarkRun sampleRun() {
        BenchmarkEnvironment environment = new BenchmarkEnvironment(
                "1.21.1", "21.1.265", "0.15.0-alpha", "21.0.5", "Eclipse Adoptium",
                "Windows 11", "amd64", 12, 8L * 1024 * 1024 * 1024,
                40, "mod-fingerprint", 12, 8, "smoothness", "settings-fingerprint", "hardware-fingerprint"
        );
        BenchmarkSampleWindow.Statistics metric = new BenchmarkSampleWindow.Statistics(
                100, 100L, 0L, 20.0, 2.0, 10.0, 18.0, 24.0, 30.0, 40.0
        );
        BenchmarkRuntimeSnapshot runtime = new BenchmarkRuntimeSnapshot(
                10_000L, 400L, 500L, 800L, 24,
                0.25, 0.15, 1L, 20L,
                1, 2, 3, 1.2, 2.3, 0L, 0L,
                "IDLE", true, List.of()
        );
        return new BenchmarkRun(
                BenchmarkRun.CURRENT_SCHEMA_VERSION,
                "run-123",
                1_000L,
                11_000L,
                41_000L,
                BenchmarkScenario.MENU_IDLE,
                environment,
                10,
                30,
                metric,
                metric,
                new BenchmarkSampleWindow.Statistics(0, 0L, 0L,
                        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                runtime,
                runtime,
                true,
                "valid"
        );
    }
}

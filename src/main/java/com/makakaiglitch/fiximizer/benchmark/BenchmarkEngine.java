package com.makakaiglitch.fiximizer.benchmark;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.scheduler.AdaptiveTaskScheduler;
import com.makakaiglitch.fiximizer.scheduler.TaskPriority;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manual, fixed-window benchmark coordinator.
 *
 * Gameplay remains user-driven. Samples are collected only during the
 * measurement phase; the warm-up phase is excluded from all distributions.
 */
public final class BenchmarkEngine {
    public static final int WARMUP_SECONDS = 10;
    public static final int MEASUREMENT_SECONDS = 30;

    private static volatile Session activeSession;
    private static volatile State state = State.IDLE;
    private static volatile BenchmarkRun latestRun;
    private static volatile BenchmarkComparison latestComparison;
    private static volatile String message = "No benchmark run yet";
    private static volatile String lastSavedFile = "";
    private static volatile boolean baselineSaveRunning;

    private BenchmarkEngine() {
    }

    public enum State {
        IDLE,
        WARMING_UP,
        RECORDING,
        SAVING,
        COMPLETE,
        CANCELLED,
        FAILED
    }

    public record Snapshot(
            State state,
            BenchmarkScenario scenario,
            long remainingSeconds,
            int renderSamples,
            int clientTickSamples,
            int serverTickSamples,
            String message,
            String savedFile,
            boolean baselineSaveRunning
    ) {
    }

    public static synchronized boolean start(
            Path gameDirectory,
            BenchmarkScenario scenario,
            BenchmarkEnvironment environment
    ) {
        if (activeSession != null || state == State.SAVING || baselineSaveRunning) {
            return false;
        }
        if (gameDirectory == null || scenario == null || environment == null) {
            message = "A game directory, scenario and environment snapshot are required.";
            state = State.FAILED;
            return false;
        }

        activeSession = new Session(gameDirectory.toAbsolutePath().normalize(), scenario, environment);
        latestComparison = null;
        message = "Warm-up started. Keep the selected scene unchanged.";
        state = State.WARMING_UP;
        return true;
    }

    /** Advances the warm-up/measurement state machine from the client tick. */
    public static void tick() {
        Session session = activeSession;
        if (session == null) {
            return;
        }

        long now = System.nanoTime();
        if (state == State.WARMING_UP && now >= session.measurementStartNanos) {
            session.beginMeasurement(now);
            state = State.RECORDING;
            message = "Measuring. Keep the selected scene and camera unchanged.";
        }

        if (state == State.RECORDING && now >= session.measurementEndNanos) {
            complete(session, now);
        }
    }

    public static boolean cancel() {
        synchronized (BenchmarkEngine.class) {
            Session session = activeSession;
            if (session == null || (state != State.WARMING_UP && state != State.RECORDING)) {
                return false;
            }
            session.stopAcceptingSamples();
            activeSession = null;
            state = State.CANCELLED;
            message = "Benchmark cancelled. No result was saved.";
            return true;
        }
    }

    public static boolean isCollecting() {
        State current = state;
        return activeSession != null && current == State.RECORDING;
    }

    public static void recordRenderSample(long durationNanos) {
        Session session = activeSession;
        if (session != null) {
            session.recordRender(durationNanos);
        }
    }

    public static void recordClientTickSample(long durationNanos) {
        Session session = activeSession;
        if (session != null) {
            session.recordClientTick(durationNanos);
        }
    }

    public static void recordServerTickSample(long durationNanos) {
        Session session = activeSession;
        if (session != null) {
            session.recordServerTick(durationNanos);
        }
    }

    public static Snapshot snapshot() {
        Session session = activeSession;
        long remaining = session == null ? 0L : session.remainingSeconds(System.nanoTime(), state);
        return new Snapshot(
                state,
                session == null ? latestRun == null ? null : latestRun.scenario() : session.scenario,
                remaining,
                session == null ? 0 : session.render.snapshot().sampleCount(),
                session == null ? 0 : session.clientTick.snapshot().sampleCount(),
                session == null ? 0 : session.serverTick.snapshot().sampleCount(),
                message,
                lastSavedFile,
                baselineSaveRunning
        );
    }

    public static BenchmarkRun latestRun() {
        return latestRun;
    }

    public static BenchmarkComparison latestComparison() {
        return latestComparison;
    }

    public static synchronized boolean saveLatestAsBaseline(Path gameDirectory) {
        BenchmarkRun run = latestRun;
        if (run == null || !run.valid() || baselineSaveRunning || activeSession != null) {
            return false;
        }

        baselineSaveRunning = true;
        message = "Saving this run as the scenario baseline...";
        CompletableFuture<Path> future = AdaptiveTaskScheduler.submit(
                "benchmark-save-baseline",
                TaskPriority.NORMAL,
                () -> BenchmarkRepository.saveBaseline(gameDirectory, run)
        );
        future.whenComplete((path, error) -> {
            baselineSaveRunning = false;
            if (error == null) {
                message = "Baseline saved: " + path.getFileName();
                lastSavedFile = path.toString();
            } else {
                message = "Baseline save failed: " + safeMessage(error);
            }
        });
        return true;
    }

    private static void complete(Session session, long completedAtNanos) {
        synchronized (BenchmarkEngine.class) {
            if (activeSession != session || state != State.RECORDING) {
                return;
            }
            session.stopAcceptingSamples();
            activeSession = null;
            BenchmarkRun run = session.finish(completedAtNanos);
            latestRun = run;
            state = State.SAVING;
            message = run.valid()
                    ? "Measurements complete. Saving the result..."
                    : "Measurements complete, but sample coverage is insufficient: " + run.validationMessage();

            CompletableFuture<SaveResult> future = AdaptiveTaskScheduler.submit(
                    "benchmark-save-run",
                    TaskPriority.NORMAL,
                    () -> {
                        Path saved = BenchmarkRepository.saveRun(session.gameDirectory, run);
                        BenchmarkComparison comparison = BenchmarkRepository
                                .loadBaseline(session.gameDirectory, run)
                                .map(baseline -> BenchmarkComparison.compare(baseline, run))
                                .orElse(null);
                        return new SaveResult(saved, comparison);
                    }
            );
            future.whenComplete((result, error) -> {
                if (error == null) {
                    latestComparison = result.comparison;
                    lastSavedFile = result.path.toString();
                    state = State.COMPLETE;
                    message = run.valid()
                            ? (result.comparison == null
                                ? "Benchmark saved. Set it as a baseline or run a candidate later."
                                : "Benchmark saved and compared with the matching baseline.")
                            : "An incomplete benchmark was saved for inspection, but cannot be used as a baseline.";
                } else {
                    state = State.FAILED;
                    message = "Benchmark measurements completed, but saving failed: " + safeMessage(error);
                }
            });
        }
    }

    private static String safeMessage(Throwable throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        String value = cause.getMessage();
        if (value == null || value.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return value.length() > 140 ? value.substring(0, 140) : value;
    }

    private record SaveResult(Path path, BenchmarkComparison comparison) {
    }

    private static final class Session {
        private final Path gameDirectory;
        private final BenchmarkScenario scenario;
        private final BenchmarkEnvironment environment;
        private final long startedAtEpochMillis = System.currentTimeMillis();
        private final long startedAtNanos = System.nanoTime();
        private final long measurementStartNanos = startedAtNanos
                + TimeUnit.SECONDS.toNanos(WARMUP_SECONDS);

        private final BenchmarkSampleWindow render = new BenchmarkSampleWindow();
        private final BenchmarkSampleWindow clientTick = new BenchmarkSampleWindow();
        private final BenchmarkSampleWindow serverTick = new BenchmarkSampleWindow();

        private volatile boolean acceptingSamples;
        private long measurementStartedAtEpochMillis;
        private long measurementStartedAtNanos;
        private long measurementEndNanos;

        private Session(Path gameDirectory, BenchmarkScenario scenario, BenchmarkEnvironment environment) {
            this.gameDirectory = gameDirectory;
            this.scenario = scenario;
            this.environment = environment;
        }

        private void beginMeasurement(long now) {
            measurementStartedAtNanos = now;
            measurementStartedAtEpochMillis = System.currentTimeMillis();
            measurementEndNanos = now + TimeUnit.SECONDS.toNanos(MEASUREMENT_SECONDS);
            acceptingSamples = true;
        }

        private void recordRender(long durationNanos) {
            if (acceptingSamples) {
                render.record(durationNanos);
            }
        }

        private void recordClientTick(long durationNanos) {
            if (acceptingSamples) {
                clientTick.record(durationNanos);
            }
        }

        private void recordServerTick(long durationNanos) {
            if (acceptingSamples) {
                serverTick.record(durationNanos);
            }
        }

        private void stopAcceptingSamples() {
            acceptingSamples = false;
        }

        private long remainingSeconds(long now, State currentState) {
            long remaining = currentState == State.WARMING_UP
                    ? measurementStartNanos - now
                    : currentState == State.RECORDING ? measurementEndNanos - now : 0L;
            return Math.max(0L, (remaining + 999_999_999L) / 1_000_000_000L);
        }

        private BenchmarkRun finish(long completedAtNanos) {
            BenchmarkSampleWindow.Statistics renderStats = render.snapshot();
            BenchmarkSampleWindow.Statistics clientStats = clientTick.snapshot();
            BenchmarkSampleWindow.Statistics serverStats = serverTick.snapshot();

            boolean enoughSamples = renderStats.sampleCount() >= 30
                    && clientStats.sampleCount() >= 10
                    && (!scenario.expectsIntegratedServer() || serverStats.sampleCount() >= 10);
            String validation = enoughSamples
                    ? "Minimum render/client tick coverage was met."
                    : "Need at least 30 render samples and 10 client tick samples"
                            + (scenario.expectsIntegratedServer() ? " plus 10 integrated-server tick samples." : ".");
            return new BenchmarkRun(
                    BenchmarkRun.CURRENT_SCHEMA_VERSION,
                    UUID.randomUUID().toString(),
                    startedAtEpochMillis,
                    measurementStartedAtEpochMillis,
                    System.currentTimeMillis(),
                    scenario,
                    environment,
                    WARMUP_SECONDS,
                    MEASUREMENT_SECONDS,
                    renderStats,
                    clientStats,
                    serverStats,
                    enoughSamples,
                    validation
            );
        }
    }
}

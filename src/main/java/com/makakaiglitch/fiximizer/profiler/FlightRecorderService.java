package com.makakaiglitch.fiximizer.profiler;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class FlightRecorderService {
    public enum State {
        IDLE,
        RECORDING,
        SAVING,
        FAILED
    }

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static volatile State state = State.IDLE;
    private static volatile String message = "Idle";
    private static volatile String lastSavedFile = "";
    private static volatile long startedAtNanos;
    private static Recording activeRecording;

    private FlightRecorderService() {
    }

    public static synchronized boolean start() {
        if (!FiximizerConfig.ENABLE_JFR_PROFILING.get()) {
            message = "JFR profiling is disabled in config";
            return false;
        }
        if (state == State.RECORDING || state == State.SAVING) {
            return false;
        }

        try {
            Recording recording = new Recording(Configuration.getConfiguration("profile"));
            recording.setName("Fiximizer performance profile");
            recording.setToDisk(true);
            recording.setMaxAge(Duration.ofSeconds(FiximizerConfig.MAX_PROFILE_SECONDS.get() + 10L));
            recording.setMaxSize(128L * 1024L * 1024L);

            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1));
            recording.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofSeconds(1));
            recording.enable("jdk.GarbageCollection");
            recording.enable("jdk.ObjectAllocationSample").withStackTrace();

            recording.start();
            activeRecording = recording;
            startedAtNanos = System.nanoTime();
            state = State.RECORDING;
            message = "Recording";
            Fiximizer.LOGGER.info("JFR performance recording started.");
            return true;
        } catch (IOException | RuntimeException | LinkageError exception) {
            state = State.FAILED;
            message = "Unable to start JFR: " + safeMessage(exception);
            Fiximizer.LOGGER.error("Unable to start Fiximizer JFR recording.", exception);
            return false;
        }
    }

    public static synchronized boolean stopAndSave(Path gameDirectory) {
        if (state != State.RECORDING || activeRecording == null) {
            return false;
        }

        Recording recording = activeRecording;
        activeRecording = null;
        state = State.SAVING;
        message = "Saving profile...";

        Thread writer = new Thread(() -> saveRecording(recording, gameDirectory),
                "Fiximizer-JFR-Writer");
        writer.setDaemon(true);
        writer.start();
        return true;
    }

    public static void tick(Path gameDirectory, int maximumSeconds) {
        if (state != State.RECORDING || startedAtNanos == 0L) {
            return;
        }

        long elapsed = System.nanoTime() - startedAtNanos;
        if (elapsed >= Duration.ofSeconds(maximumSeconds).toNanos()) {
            stopAndSave(gameDirectory);
        }
    }

    public static State state() {
        return state;
    }

    public static String message() {
        return message;
    }

    public static String lastSavedFile() {
        return lastSavedFile;
    }

    public static long elapsedSeconds() {
        if (state != State.RECORDING || startedAtNanos == 0L) {
            return 0L;
        }
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000_000L);
    }

    private static void saveRecording(Recording recording, Path gameDirectory) {
        try {
            Path profileDirectory = gameDirectory.resolve("fiximizer").resolve("profiles");
            Files.createDirectories(profileDirectory);

            String timestamp = LocalDateTime.now().format(FILE_TIME);
            Path output = profileDirectory.resolve("fiximizer-" + timestamp + ".jfr");

            recording.stop();
            recording.dump(output);

            lastSavedFile = output.getFileName().toString();
            message = "Saved: " + lastSavedFile;
            state = State.IDLE;
            Fiximizer.LOGGER.info("JFR profile saved to {}", output.toAbsolutePath());
        } catch (IOException | RuntimeException | LinkageError exception) {
            state = State.FAILED;
            message = "Failed to save JFR profile: " + safeMessage(exception);
            Fiximizer.LOGGER.error("Unable to save Fiximizer JFR recording.", exception);
        } finally {
            try {
                recording.close();
            } catch (RuntimeException ignored) {
                // Preserve the original save result.
            }
        }
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }
}

package com.makakaiglitch.fiximizer.profiler;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Owns both user-requested profiles and the bounded rolling JFR buffer used by
 * the stall watchdog. Exporting a rolling snapshot copies the live recording;
 * it does not stop or restart the underlying ring.
 */
public final class FlightRecorderService {
    public enum State {
        IDLE,
        RECORDING,
        SAVING,
        FAILED
    }

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
    private static final AtomicLong FILE_SEQUENCE = new AtomicLong();

    private static volatile State state = State.IDLE;
    private static volatile String message = "Idle";
    private static volatile String lastSavedFile = "";
    private static volatile String lastReportFile = "";
    private static volatile long startedAtNanos;
    private static Recording activeRecording;

    private static Recording rollingRecording;
    private static volatile String rollingStatus = "Not started";
    private static volatile boolean rollingActive;
    private static long lastRollingStartAttemptNanos;
    private static long lastRollingDumpNanos;

    private FlightRecorderService() {
    }

    /**
     * Synchronizes the ring with config. This method is called by the watchdog
     * worker, not from a Minecraft tick or render callback.
     */
    public static synchronized void syncRollingBuffer() {
        boolean shouldRun = FiximizerConfig.ENABLE_STALL_WATCHDOG.get()
                && FiximizerConfig.ENABLE_STALL_JFR.get()
                && FiximizerConfig.ENABLE_JFR_PROFILING.get();

        if (!shouldRun) {
            closeRollingBuffer();
            rollingStatus = "Disabled";
            return;
        }

        if (rollingRecording != null
                && rollingRecording.getState() == RecordingState.RUNNING) {
            return;
        }

        long now = System.nanoTime();
        if (lastRollingStartAttemptNanos != 0L
                && now - lastRollingStartAttemptNanos < TimeUnit.SECONDS.toNanos(30L)) {
            return;
        }
        lastRollingStartAttemptNanos = now;

        try {
            int windowSeconds = FiximizerConfig.STALL_JFR_WINDOW_SECONDS.get();
            int maxSizeMiB = FiximizerConfig.STALL_JFR_MAX_SIZE_MIB.get();

            Recording next = new Recording(Configuration.getConfiguration("profile"));
            next.setName("Fiximizer rolling stall buffer");
            next.setToDisk(true);
            next.setMaxAge(Duration.ofSeconds(windowSeconds));
            next.setMaxSize(maxSizeMiB * 1024L * 1024L);
            next.start();

            rollingRecording = next;
            rollingActive = true;
            lastRollingDumpNanos = 0L;
            rollingStatus = "Active (up to " + windowSeconds + "s / " + maxSizeMiB + " MiB)";
            Fiximizer.LOGGER.info("JFR rolling stall buffer started (maxAge={}s, maxSize={}MiB).",
                    windowSeconds, maxSizeMiB);
        } catch (IOException | ParseException | RuntimeException | LinkageError exception) {
            rollingRecording = null;
            rollingActive = false;
            rollingStatus = "Unavailable: " + safeMessage(exception);
            Fiximizer.LOGGER.warn(
                    "Could not start the rolling JFR buffer; text and thread diagnostics remain available.",
                    exception);
        }
    }

    /**
     * Saves a bounded copy of the rolling recording. The copy is stopped
     * immediately, while the original recording continues collecting events.
     *
     * @return the exported file name, or a short status if no export was made
     */
    public static String dumpRollingSnapshot(Path directory, String fileStem) {
        Recording snapshot;
        long now = System.nanoTime();

        synchronized (FlightRecorderService.class) {
            if (rollingRecording == null
                    || rollingRecording.getState() != RecordingState.RUNNING) {
                return "unavailable";
            }

            long cooldown = TimeUnit.MILLISECONDS.toNanos(
                    FiximizerConfig.STALL_JFR_DUMP_INTERVAL_MS.get());
            if (lastRollingDumpNanos != 0L && now - lastRollingDumpNanos < cooldown) {
                return "cooldown";
            }

            try {
                // 'true' stops the clone, not rollingRecording itself.
                snapshot = rollingRecording.copy(true);
                lastRollingDumpNanos = now;
            } catch (RuntimeException | LinkageError exception) {
                Fiximizer.LOGGER.warn("Could not clone the rolling JFR buffer.", exception);
                return "copy-failed";
            }
        }

        Path output = directory.resolve(fileStem + ".jfr");
        try {
            Files.createDirectories(directory);
            try (snapshot) {
                snapshot.dump(output);
            }

            pruneRollingExports(directory, FiximizerConfig.MAX_STALL_JFR_EXPORTS.get());
            Fiximizer.LOGGER.info("Rolling JFR snapshot exported: {}", output.getFileName());
            return output.getFileName().toString();
        } catch (IOException | RuntimeException | LinkageError exception) {
            try {
                snapshot.close();
            } catch (RuntimeException ignored) {
                // Keep the original export error.
            }
            Fiximizer.LOGGER.warn("Could not export the rolling JFR snapshot.", exception);
            return "export-failed";
        }
    }

    private static void pruneRollingExports(Path directory, int maximum) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> exports = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("stall-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jfr"))
                    .sorted(Comparator.comparingLong(FlightRecorderService::lastModifiedMillis).reversed())
                    .toList();

            for (int index = Math.max(1, maximum); index < exports.size(); index++) {
                try {
                    Files.deleteIfExists(exports.get(index));
                } catch (IOException exception) {
                    Fiximizer.LOGGER.debug("Could not prune old rolling JFR export {}.",
                            exports.get(index).getFileName(), exception);
                }
            }
        } catch (IOException | RuntimeException exception) {
            Fiximizer.LOGGER.debug("Could not enumerate rolling JFR exports.", exception);
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static synchronized void closeRollingBuffer() {
        Recording recording = rollingRecording;
        rollingRecording = null;
        rollingActive = false;
        if (recording == null) {
            return;
        }
        try {
            recording.stop();
        } catch (RuntimeException ignored) {
            // It may already have been stopped by the JVM.
        }
        try {
            recording.close();
        } catch (RuntimeException exception) {
            Fiximizer.LOGGER.debug("Could not close the rolling JFR buffer.", exception);
        }
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
            Recording recording = newProfileRecording(
                    "Fiximizer performance profile",
                    FiximizerConfig.MAX_PROFILE_SECONDS.get() + 10L,
                    128L * 1024L * 1024L
            );
            recording.start();

            activeRecording = recording;
            startedAtNanos = System.nanoTime();
            state = State.RECORDING;
            message = "Recording";
            Fiximizer.LOGGER.info("JFR performance recording started.");
            return true;
        } catch (IOException | ParseException | RuntimeException | LinkageError exception) {
            state = State.FAILED;
            message = "Unable to start JFR: " + safeMessage(exception);
            Fiximizer.LOGGER.error("Unable to start Fiximizer JFR recording.", exception);
            return false;
        }
    }

    private static Recording newProfileRecording(String name, long maxAgeSeconds, long maxSizeBytes)
            throws IOException, ParseException {
        Recording recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setName(name);
        recording.setToDisk(true);
        recording.setMaxAge(Duration.ofSeconds(maxAgeSeconds));
        recording.setMaxSize(maxSizeBytes);
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
        recording.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1));
        recording.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofSeconds(1));
        recording.enable("jdk.GarbageCollection");
        recording.enable("jdk.ObjectAllocationSample").withStackTrace();
        return recording;
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

    public static String rollingStatus() {
        return rollingStatus;
    }

    public static boolean rollingActive() {
        return rollingActive;
    }

    public static String lastSavedFile() {
        return lastSavedFile;
    }

    public static String lastReportFile() {
        return lastReportFile;
    }

    public static long elapsedSeconds() {
        if (state != State.RECORDING || startedAtNanos == 0L) {
            return 0L;
        }
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000_000L);
    }

    private static void saveRecording(Recording recording, Path gameDirectory) {
        Path output = null;
        boolean saved = false;

        try {
            Path profileDirectory = gameDirectory.resolve("fiximizer").resolve("profiles");
            Files.createDirectories(profileDirectory);

            String timestamp = LocalDateTime.now().format(FILE_TIME);
            output = profileDirectory.resolve(
                    "fiximizer-" + timestamp + "-" + FILE_SEQUENCE.incrementAndGet() + ".jfr");

            recording.stop();
            recording.dump(output);

            lastSavedFile = output.getFileName().toString();
            saved = true;
            Fiximizer.LOGGER.info("JFR profile saved to {}.", output.getFileName());
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

        if (!saved || output == null) {
            return;
        }

        if (FiximizerConfig.ENABLE_PROFILE_ANALYSIS.get()) {
            message = "Recording saved; analysing hotspots...";
            try {
                Path report = JfrProfileAnalyzer.analyzeAndWrite(output);
                lastReportFile = report.getFileName().toString();
                message = "Saved " + lastSavedFile + " / analysis " + lastReportFile;
                Fiximizer.LOGGER.info("JFR hotspot report saved to {}.", report.getFileName());
            } catch (IOException | RuntimeException | LinkageError exception) {
                message = "JFR saved; analysis failed: " + safeMessage(exception);
                Fiximizer.LOGGER.warn("Unable to analyse Fiximizer JFR recording.", exception);
            }
        } else {
            message = "Saved: " + lastSavedFile;
        }

        state = State.IDLE;
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }
}

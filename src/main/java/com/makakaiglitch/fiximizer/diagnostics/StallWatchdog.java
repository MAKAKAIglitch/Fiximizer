package com.makakaiglitch.fiximizer.diagnostics;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.profiler.FlightRecorderService;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Detects long-running client, render and integrated-server event spans.
 * The watcher captures immutable JVM snapshots immediately; attribution,
 * JFR export and all filesystem work are handled by a separate bounded writer.
 */
public final class StallWatchdog {
    private enum SpanType {
        SERVER_TICK("integrated server tick"),
        CLIENT_TICK("client tick"),
        RENDER_FRAME("render frame");

        private final String label;

        SpanType(String label) {
            this.label = label;
        }
    }

    private static final int MAX_CAPTURED_THREADS = 256;
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ROOT);
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean MANUAL_DUMP_REQUESTED = new AtomicBoolean();
    private static final AtomicLong FILE_SEQUENCE = new AtomicLong();
    private static final AtomicReference<ActiveSpan> SERVER_TICK = new AtomicReference<>();
    private static final AtomicReference<ActiveSpan> CLIENT_TICK = new AtomicReference<>();
    private static final AtomicReference<ActiveSpan> RENDER_FRAME = new AtomicReference<>();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private static final ScheduledExecutorService WATCHER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    daemonFactory("Fiximizer-Stall-Watchdog", Thread.NORM_PRIORITY - 1));
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
            daemonFactory("Fiximizer-Stall-Writer", Thread.NORM_PRIORITY - 1),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private static volatile Path gameDirectory;
    private static volatile String lastReportFile = "";
    private static volatile String lastJfrFile = "";
    private static volatile long lastQueueWarningNanos;

    private StallWatchdog() {
    }

    public static void start(Path directory) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        gameDirectory = directory.toAbsolutePath().normalize();
        WATCHER.scheduleWithFixedDelay(StallWatchdog::watchSafely, 200L, 200L, TimeUnit.MILLISECONDS);

        // Build the potentially expensive class-to-mod index off the game threads,
        // before the first stall report needs an attribution lookup.
        try {
            WRITER.execute(ModAttribution::warmUp);
        } catch (RejectedExecutionException exception) {
            Fiximizer.LOGGER.warn("Could not queue the initial mod-attribution index build.");
        }

        Fiximizer.LOGGER.info("Stall watchdog started (threshold={} ms, poll=200 ms).",
                FiximizerConfig.STALL_THRESHOLD_MS.get());
    }

    public static void beginServerTick() { begin(SERVER_TICK, SpanType.SERVER_TICK); }
    public static void endServerTick() { SERVER_TICK.set(null); }
    public static void beginClientTick() { begin(CLIENT_TICK, SpanType.CLIENT_TICK); }
    public static void endClientTick() { CLIENT_TICK.set(null); }
    public static void beginRenderFrame() { begin(RENDER_FRAME, SpanType.RENDER_FRAME); }
    public static void endRenderFrame() { RENDER_FRAME.set(null); }

    public static boolean requestManualDump() {
        if (!enabled()) {
            return false;
        }
        return MANUAL_DUMP_REQUESTED.compareAndSet(false, true);
    }

    public static boolean enabled() {
        return STARTED.get() && FiximizerConfig.ENABLE_STALL_WATCHDOG.get();
    }

    public static String lastReportFile() {
        return lastReportFile;
    }

    public static String lastJfrFile() {
        return lastJfrFile;
    }

    private static void begin(AtomicReference<ActiveSpan> reference, SpanType type) {
        if (!enabled()) {
            reference.set(null);
            return;
        }
        reference.set(new ActiveSpan(type, Thread.currentThread(), System.nanoTime()));
    }

    private static void watchSafely() {
        try {
            FlightRecorderService.syncRollingBuffer();

            if (!FiximizerConfig.ENABLE_STALL_WATCHDOG.get()) {
                SERVER_TICK.set(null);
                CLIENT_TICK.set(null);
                RENDER_FRAME.set(null);
                MANUAL_DUMP_REQUESTED.set(false);
                return;
            }

            if (MANUAL_DUMP_REQUESTED.compareAndSet(true, false)) {
                captureAndQueue("manual diagnostic snapshot", null, 0L);
            }

            long now = System.nanoTime();
            long threshold = TimeUnit.MILLISECONDS.toNanos(FiximizerConfig.STALL_THRESHOLD_MS.get());
            long repeat = TimeUnit.MILLISECONDS.toNanos(FiximizerConfig.STALL_REPEAT_INTERVAL_MS.get());
            int maximum = FiximizerConfig.MAX_DUMPS_PER_STALL.get();

            inspectSpan(SERVER_TICK, now, threshold, repeat, maximum);
            inspectSpan(CLIENT_TICK, now, threshold, repeat, maximum);
            inspectSpan(RENDER_FRAME, now, threshold, repeat, maximum);
        } catch (RuntimeException exception) {
            Fiximizer.LOGGER.warn("Stall watchdog iteration failed; monitoring will continue.", exception);
        }
    }

    private static void inspectSpan(
            AtomicReference<ActiveSpan> reference,
            long now,
            long threshold,
            long repeat,
            int maximum
    ) {
        ActiveSpan span = reference.get();
        if (span == null) {
            return;
        }

        long duration = Math.max(0L, now - span.startedAtNanos);
        if (!StallCapturePolicy.shouldCapture(
                duration, now, threshold, span.lastCaptureAtNanos, repeat,
                span.captureCount, maximum)) {
            return;
        }
        if (reference.get() != span) {
            return;
        }

        // Throttle attempts even if the writer queue is full.
        span.lastCaptureAtNanos = now;
        if (captureAndQueue(span.type.label, span.thread, duration)) {
            span.captureCount++;
        }
    }

    private static boolean captureAndQueue(String cause, Thread targetThread, long durationNanos) {
        if (WRITER.getQueue().remainingCapacity() == 0) {
            logQueueSaturated();
            return false;
        }

        CapturedSnapshot snapshot;
        try {
            snapshot = captureSnapshot(cause, targetThread, durationNanos);
        } catch (RuntimeException exception) {
            Fiximizer.LOGGER.warn("Unable to capture a stall snapshot.", exception);
            return false;
        }

        try {
            WRITER.execute(() -> writeSnapshot(snapshot));
            return true;
        } catch (RejectedExecutionException exception) {
            logQueueSaturated();
            return false;
        }
    }

    /**
     * Captures ThreadInfo and JVM counters on the watchdog worker, while the
     * observed span is still active. The writer later formats this immutable
     * snapshot and performs disk/JFR operations.
     */
    private static CapturedSnapshot captureSnapshot(
            String cause,
            Thread targetThread,
            long durationNanos
    ) {
        Instant capturedAt = Instant.now();
        Runtime runtime = Runtime.getRuntime();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();

        long[] allIds = THREADS.getAllThreadIds();
        long targetThreadId = targetThread == null ? -1L : targetThread.threadId();
        Set<Long> selectedIds = new LinkedHashSet<>();
        if (targetThreadId > 0L) {
            selectedIds.add(targetThreadId);
        }
        for (long id : allIds) {
            if (selectedIds.size() >= MAX_CAPTURED_THREADS) {
                break;
            }
            selectedIds.add(id);
        }

        long[] ids = selectedIds.stream().mapToLong(Long::longValue).toArray();
        ThreadInfo[] threadInfos = THREADS.getThreadInfo(
                ids, FiximizerConfig.STALL_STACK_DEPTH.get());

        long[] deadlockedIds = null;
        ThreadInfo[] deadlockedInfos = new ThreadInfo[0];
        String deadlockStatus = "available";
        try {
            deadlockedIds = THREADS.findDeadlockedThreads();
            if (deadlockedIds != null && deadlockedIds.length > 0) {
                deadlockedInfos = THREADS.getThreadInfo(
                        deadlockedIds, FiximizerConfig.STALL_STACK_DEPTH.get());
            }
        } catch (UnsupportedOperationException | SecurityException exception) {
            deadlockStatus = "unavailable (" + exception.getClass().getSimpleName() + ")";
        }

        double processCpuLoad = -1.0;
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            processCpuLoad = extended.getProcessCpuLoad();
        }

        List<GcReading> gcReadings = new ArrayList<>();
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcReadings.add(new GcReading(
                    collector.getName(),
                    collector.getCollectionCount(),
                    collector.getCollectionTime()
            ));
        }

        return new CapturedSnapshot(
                cause,
                durationNanos,
                capturedAt,
                targetThreadId,
                targetThread == null ? "none" : targetThread.getName(),
                FiximizerConfig.STALL_THRESHOLD_MS.get(),
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.vm.name", "unknown"),
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"),
                runtime.availableProcessors(),
                heap.getUsed(),
                heap.getMax(),
                heap.getCommitted(),
                allIds.length,
                WRITER.getQueue().size(),
                processCpuLoad,
                List.copyOf(gcReadings),
                threadInfos,
                deadlockedIds == null ? new long[0] : deadlockedIds,
                deadlockedInfos,
                deadlockStatus,
                allIds.length > MAX_CAPTURED_THREADS
        );
    }

    private static void writeSnapshot(CapturedSnapshot snapshot) {
        Path directory = gameDirectory.resolve("logs").resolve("fiximizer").resolve("stalls");
        String baseName = "stall-" + FILE_TIME.format(LocalDateTime.now())
                + "-" + FILE_SEQUENCE.incrementAndGet();

        try {
            Files.createDirectories(directory);

            // JFR cloning/dumping happens on the writer, never on a game thread.
            String jfrResult = FlightRecorderService.dumpRollingSnapshot(directory, baseName);
            if (jfrResult.endsWith(".jfr")) {
                lastJfrFile = jfrResult;
            }

            String report = buildReport(snapshot, jfrResult);
            Path output = directory.resolve(baseName + ".txt");
            Files.writeString(
                    output,
                    report,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );

            pruneReports(directory, FiximizerConfig.MAX_STALL_REPORTS.get());
            lastReportFile = output.getFileName().toString();
            Fiximizer.LOGGER.warn("Stall diagnostic saved: {} (trigger={}, duration={} ms).",
                    output.getFileName(),
                    snapshot.cause,
                    String.format(Locale.ROOT, "%.2f", snapshot.durationNanos / 1_000_000.0));
        } catch (IOException | RuntimeException exception) {
            Fiximizer.LOGGER.error("Unable to write Fiximizer stall diagnostic {}.",
                    baseName, exception);
        }
    }

    private static String buildReport(CapturedSnapshot snapshot, String jfrResult) {
        StringBuilder report = new StringBuilder(32_768);
        report.append("Fiximizer stall diagnostic\n");
        report.append("===========================\n");
        report.append("Captured: ").append(snapshot.capturedAt).append('\n');
        report.append("Trigger: ").append(snapshot.cause).append('\n');
        report.append("Trigger thread: ").append(snapshot.targetThreadName)
                .append(" (id=").append(snapshot.targetThreadId).append(")\n");
        report.append("Observed duration: ")
                .append(String.format(Locale.ROOT, "%.2f ms",
                        snapshot.durationNanos / 1_000_000.0)).append('\n');
        report.append("Configured threshold: ").append(snapshot.thresholdMillis).append(" ms\n");
        report.append("Java: ").append(snapshot.javaVersion).append('\n');
        report.append("VM: ").append(snapshot.vmName).append('\n');
        report.append("OS: ").append(snapshot.osName).append(' ')
                .append(snapshot.osVersion).append(" / ").append(snapshot.osArch).append('\n');
        report.append("Processors: ").append(snapshot.availableProcessors).append('\n');
        report.append("Heap used: ").append(formatBytes(snapshot.heapUsedBytes))
                .append(" / max ").append(formatBytes(snapshot.heapMaxBytes)).append('\n');
        report.append("Heap committed: ").append(formatBytes(snapshot.heapCommittedBytes)).append('\n');
        report.append("Live JVM threads: ").append(snapshot.liveThreadCount).append('\n');
        report.append("Writer queue at capture: ").append(snapshot.writerQueueSize).append("/16 waiting reports\n");
        report.append("Process CPU load: ");
        if (Double.isFinite(snapshot.processCpuLoad) && snapshot.processCpuLoad >= 0.0) {
            report.append(String.format(Locale.ROOT, "%.1f%%", snapshot.processCpuLoad * 100.0));
        } else {
            report.append("not available");
        }
        report.append('\n');

        report.append("\n[GARBAGE COLLECTORS]\n");
        for (GcReading reading : snapshot.gcReadings) {
            report.append(reading.name)
                    .append(": collections=").append(reading.collectionCount)
                    .append(", time=").append(reading.collectionTimeMillis).append(" ms\n");
        }

        report.append("\n[JFR ROLLING SNAPSHOT]\n");
        report.append("status=").append(jfrResult).append('\n');
        report.append("buffer=").append(FlightRecorderService.rollingStatus()).append('\n');

        report.append("\n[MOD ATTRIBUTION — HEURISTIC]\n");
        List<ModAttribution.Evidence> candidates =
                ModAttribution.analyze(snapshot.threadInfos, snapshot.targetThreadId);
        if (candidates.isEmpty()) {
            report.append("No mod ownership could be resolved from the captured stack frames.\n");
        } else {
            report.append("Confidence is based on stack-frame position/frequency, not proof of causation.\n");
            report.append("confidence | score | modId | name | version | trigger frames | top-5 trigger frames | other-thread frames\n");
            for (ModAttribution.Evidence candidate : candidates) {
                report.append(candidate.confidence()).append(" | ")
                        .append(candidate.score()).append(" | ")
                        .append(candidate.modId()).append(" | ")
                        .append(clean(candidate.displayName())).append(" | ")
                        .append(clean(candidate.version())).append(" | ")
                        .append(candidate.triggerFrames()).append(" | ")
                        .append(candidate.topTriggerFrames()).append(" | ")
                        .append(candidate.otherThreadFrames()).append('\n');
                if (candidate.firstTriggerFrame() != null) {
                    report.append("  first: ").append(candidate.firstThreadName())
                            .append(" -> ").append(candidate.firstTriggerFrame()).append('\n');
                }
            }
        }

        report.append("\n[THREAD SNAPSHOTS]\n");
        for (ThreadInfo info : snapshot.threadInfos) {
            if (info == null) {
                continue;
            }
            report.append("\nThread \"").append(info.getThreadName()).append("\"")
                    .append(" id=").append(info.getThreadId())
                    .append(" state=").append(info.getThreadState()).append('\n');
            if (info.getLockName() != null) {
                report.append("  waiting/blocked on: ").append(info.getLockName()).append('\n');
            }
            if (info.getLockOwnerName() != null) {
                report.append("  lock owner: ").append(info.getLockOwnerName())
                        .append(" (id=").append(info.getLockOwnerId()).append(")\n");
            }
            for (StackTraceElement frame : info.getStackTrace()) {
                report.append("    at ").append(frame).append('\n');
            }
            if (info.getStackTrace().length == 0) {
                report.append("    <no stack frames available>\n");
            }
        }
        report.append("\nCaptured threads: ")
                .append(snapshot.threadInfos.length).append("/")
                .append(snapshot.liveThreadCount).append(" live thread(s)");
        if (snapshot.threadLimitReached) {
            report.append(" (limit ").append(MAX_CAPTURED_THREADS).append(")");
        }
        report.append('\n');

        report.append("\n[DEADLOCK CHECK]\n");
        report.append("status=").append(snapshot.deadlockStatus).append('\n');
        if (snapshot.deadlockedThreadIds.length == 0) {
            report.append("No monitor/synchronizer deadlocks detected.\n");
        } else {
            report.append("Detected ").append(snapshot.deadlockedThreadIds.length)
                    .append(" deadlocked thread(s):\n");
            for (ThreadInfo info : snapshot.deadlockedInfos) {
                if (info == null) {
                    continue;
                }
                report.append("  ").append(info.getThreadName())
                        .append(" (id=").append(info.getThreadId()).append(") ")
                        .append(info.getThreadState()).append(" lock=").append(info.getLockName())
                        .append(" owner=").append(info.getLockOwnerName()).append('\n');
            }
        }

        report.append("\n[INTERPRETATION]\n");
        report.append("A stack trace shows what a thread was doing at capture time; it does not prove that the topmost mod caused the stall.\n");
        report.append("Mod attribution is heuristic. Shared packages, multi-mod JARs, transformed/hidden classes and native code may remain unattributed.\n");
        report.append("Durations are wall-clock time between NeoForge event callbacks, not CPU time, GPU execution time or displayed-frame cadence.\n");
        report.append("Reports include local JVM thread names and class/package names. Review them before sharing publicly.\n");
        return report.toString();
    }

    private static void pruneReports(Path directory, int keepCount) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> reports = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparingLong(StallWatchdog::lastModifiedMillis).reversed())
                    .toList();

            for (int index = Math.max(1, keepCount); index < reports.size(); index++) {
                try {
                    Files.deleteIfExists(reports.get(index));
                } catch (IOException exception) {
                    Fiximizer.LOGGER.debug("Unable to prune old stall report {}.",
                            reports.get(index).getFileName(), exception);
                }
            }
        } catch (IOException | RuntimeException exception) {
            Fiximizer.LOGGER.warn("Unable to apply stall-report retention.", exception);
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static void logQueueSaturated() {
        long now = System.nanoTime();
        if (now - lastQueueWarningNanos >= TimeUnit.SECONDS.toNanos(30L)) {
            lastQueueWarningNanos = now;
            Fiximizer.LOGGER.warn(
                    "Stall diagnostic writer queue is full; a snapshot was skipped to keep memory bounded."
            );
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) {
            return "not available";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double mib = bytes / 1024.0 / 1024.0;
        if (mib < 1024.0) {
            return String.format(Locale.ROOT, "%.1f MiB", mib);
        }
        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static ThreadFactory daemonFactory(String name, int priority) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        };
    }

    private record GcReading(String name, long collectionCount, long collectionTimeMillis) {
    }

    private record CapturedSnapshot(
            String cause,
            long durationNanos,
            Instant capturedAt,
            long targetThreadId,
            String targetThreadName,
            int thresholdMillis,
            String javaVersion,
            String vmName,
            String osName,
            String osVersion,
            String osArch,
            int availableProcessors,
            long heapUsedBytes,
            long heapMaxBytes,
            long heapCommittedBytes,
            int liveThreadCount,
            int writerQueueSize,
            double processCpuLoad,
            List<GcReading> gcReadings,
            ThreadInfo[] threadInfos,
            long[] deadlockedThreadIds,
            ThreadInfo[] deadlockedInfos,
            String deadlockStatus,
            boolean threadLimitReached
    ) {
    }

    private static final class ActiveSpan {
        private final SpanType type;
        private final Thread thread;
        private final long startedAtNanos;
        private long lastCaptureAtNanos;
        private int captureCount;

        private ActiveSpan(SpanType type, Thread thread, long startedAtNanos) {
            this.type = type;
            this.thread = thread;
            this.startedAtNanos = startedAtNanos;
        }
    }
}

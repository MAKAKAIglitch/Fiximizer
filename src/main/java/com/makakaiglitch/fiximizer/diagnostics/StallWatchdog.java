package com.makakaiglitch.fiximizer.diagnostics;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;

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
 * Thread snapshots are captured by a daemon watcher; disk I/O is handled by
 * a separate daemon writer with a bounded queue.
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
    private static volatile long lastQueueWarningNanos;

    private StallWatchdog() {
    }

    public static void start(Path directory) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        gameDirectory = directory.toAbsolutePath().normalize();
        WATCHER.scheduleWithFixedDelay(StallWatchdog::watchSafely, 200L, 200L, TimeUnit.MILLISECONDS);
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
        if (!enabled()) return false;
        return MANUAL_DUMP_REQUESTED.compareAndSet(false, true);
    }

    public static boolean enabled() {
        return STARTED.get() && FiximizerConfig.ENABLE_STALL_WATCHDOG.get();
    }

    public static String lastReportFile() {
        return lastReportFile;
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
        if (span == null) return;

        long duration = Math.max(0L, now - span.startedAtNanos);
        if (!StallCapturePolicy.shouldCapture(
                duration, now, threshold, span.lastCaptureAtNanos, repeat,
                span.captureCount, maximum)) {
            return;
        }
        if (reference.get() != span) return;

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

        Instant capturedAt = Instant.now();
        String report;
        try {
            report = buildReport(cause, targetThread, durationNanos, capturedAt);
        } catch (RuntimeException exception) {
            Fiximizer.LOGGER.warn("Unable to capture a stall snapshot.", exception);
            return false;
        }

        try {
            WRITER.execute(() -> writeReport(report, capturedAt));
            return true;
        } catch (RejectedExecutionException exception) {
            logQueueSaturated();
            return false;
        }
    }

    private static String buildReport(
            String cause, Thread targetThread, long durationNanos, Instant capturedAt
    ) {
        StringBuilder report = new StringBuilder(24_576);
        Runtime runtime = Runtime.getRuntime();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();

        report.append("Fiximizer stall diagnostic\n");
        report.append("===========================\n");
        report.append("Captured: ").append(capturedAt).append('\n');
        report.append("Trigger: ").append(cause).append('\n');
        report.append("Observed duration: ")
                .append(String.format(Locale.ROOT, "%.2f ms", durationNanos / 1_000_000.0))
                .append('\n');
        report.append("Configured threshold: ").append(FiximizerConfig.STALL_THRESHOLD_MS.get())
                .append(" ms\n");
        report.append("Java: ").append(System.getProperty("java.version", "unknown")).append('\n');
        report.append("VM: ").append(System.getProperty("java.vm.name", "unknown")).append('\n');
        report.append("OS: ").append(System.getProperty("os.name", "unknown")).append(' ')
                .append(System.getProperty("os.version", "unknown")).append(" / ")
                .append(System.getProperty("os.arch", "unknown")).append('\n');
        report.append("Processors: ").append(runtime.availableProcessors()).append('\n');
        report.append("Heap used: ").append(formatBytes(heap.getUsed()))
                .append(" / max ").append(formatBytes(heap.getMax())).append('\n');
        report.append("Heap committed: ").append(formatBytes(heap.getCommitted())).append('\n');
        report.append("Live JVM threads: ").append(THREADS.getThreadCount()).append('\n');
        report.append("Writer queue: ").append(WRITER.getQueue().size()).append("/16 waiting reports\n");
        appendProcessCpu(report);
        appendGarbageCollectors(report);

        report.append("\nTHREAD SNAPSHOTS\n----------------\n");
        appendThreadSnapshots(report, targetThread);
        report.append("\nDEADLOCK CHECK\n--------------\n");
        appendDeadlocks(report);

        report.append("\nINTERPRETATION\n--------------\n");
        report.append("A stack trace shows what a thread was doing when this snapshot was taken; ")
                .append("it does not prove that the topmost mod caused the stall.\n");
        report.append("Durations are wall-clock time between NeoForge event callbacks, not CPU time, ")
                .append("GPU execution time or displayed-frame cadence.\n");
        report.append("The report includes local JVM thread names and class/package names. ")
                .append("Review it before sharing publicly.\n");
        return report.toString();
    }

    private static void appendProcessCpu(StringBuilder report) {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            double cpu = extended.getProcessCpuLoad();
            report.append("Process CPU load: ");
            if (Double.isFinite(cpu) && cpu >= 0.0) {
                report.append(String.format(Locale.ROOT, "%.1f%%", cpu * 100.0));
            } else {
                report.append("not available");
            }
            report.append('\n');
        } else {
            report.append("Process CPU load: not available\n");
        }
    }

    private static void appendGarbageCollectors(StringBuilder report) {
        report.append("Garbage collectors:\n");
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            report.append("  ").append(collector.getName())
                    .append(": collections=").append(collector.getCollectionCount())
                    .append(", time=").append(collector.getCollectionTime()).append(" ms\n");
        }
    }

    private static void appendThreadSnapshots(StringBuilder report, Thread targetThread) {
        long[] allIds = THREADS.getAllThreadIds();
        Set<Long> selectedIds = new LinkedHashSet<>();
        if (targetThread != null) selectedIds.add(targetThread.threadId());
        for (long id : allIds) {
            if (selectedIds.size() >= MAX_CAPTURED_THREADS) break;
            selectedIds.add(id);
        }

        long[] ids = selectedIds.stream().mapToLong(Long::longValue).toArray();
        ThreadInfo[] infos = THREADS.getThreadInfo(ids, FiximizerConfig.STALL_STACK_DEPTH.get());
        int written = 0;
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            written++;
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
            StackTraceElement[] stack = info.getStackTrace();
            for (StackTraceElement frame : stack) {
                report.append("    at ").append(frame).append('\n');
            }
            if (stack.length == 0) report.append("    <no stack frames available>\n");
        }

        report.append("\nCaptured threads: ").append(written).append("/")
                .append(allIds.length).append(" live thread(s)");
        if (allIds.length > MAX_CAPTURED_THREADS) {
            report.append(" (limit ").append(MAX_CAPTURED_THREADS).append(")");
        }
        report.append('\n');
    }

    private static void appendDeadlocks(StringBuilder report) {
        try {
            long[] deadlockedIds = THREADS.findDeadlockedThreads();
            if (deadlockedIds == null || deadlockedIds.length == 0) {
                report.append("No monitor/synchronizer deadlocks detected.\n");
                return;
            }
            report.append("Detected ").append(deadlockedIds.length).append(" deadlocked thread(s):\n");
            ThreadInfo[] infos = THREADS.getThreadInfo(
                    deadlockedIds, FiximizerConfig.STALL_STACK_DEPTH.get());
            for (ThreadInfo info : infos) {
                if (info == null) continue;
                report.append("  ").append(info.getThreadName())
                        .append(" (id=").append(info.getThreadId()).append(") ")
                        .append(info.getThreadState()).append(" lock=").append(info.getLockName())
                        .append(" owner=").append(info.getLockOwnerName()).append('\n');
            }
        } catch (UnsupportedOperationException | SecurityException exception) {
            report.append("Deadlock inspection unavailable: ")
                    .append(exception.getClass().getSimpleName()).append('\n');
        }
    }

    private static void writeReport(String report, Instant capturedAt) {
        Path output = null;
        try {
            Path directory = gameDirectory.resolve("logs").resolve("fiximizer").resolve("stalls");
            Files.createDirectories(directory);
            String timestamp = LocalDateTime.now().format(FILE_TIME);
            String filename = "stall-" + timestamp + "-" + FILE_SEQUENCE.incrementAndGet() + ".txt";
            output = directory.resolve(filename);
            Files.writeString(output, report, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            pruneReports(directory, FiximizerConfig.MAX_STALL_REPORTS.get());
            lastReportFile = filename;
            Fiximizer.LOGGER.warn("Stall diagnostic saved: {} (captured at {}).", filename, capturedAt);
        } catch (IOException | RuntimeException exception) {
            Fiximizer.LOGGER.error("Unable to write Fiximizer stall diagnostic {}.",
                    output == null ? "<unavailable>" : output.getFileName(), exception);
        }
    }

    private static void pruneReports(Path directory, int keepCount) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> reports = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparingLong(StallWatchdog::lastModifiedMillis))
                    .toList();
            int excess = Math.max(0, reports.size() - keepCount);
            for (int index = 0; index < excess; index++) Files.deleteIfExists(reports.get(index));
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
        if (bytes < 0L) return "not available";
        if (bytes < 1024L) return bytes + " B";
        double mib = bytes / 1024.0 / 1024.0;
        if (mib < 1024.0) return String.format(Locale.ROOT, "%.1f MiB", mib);
        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0);
    }

    private static ThreadFactory daemonFactory(String name, int priority) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        };
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

package com.makakaiglitch.fiximizer.network;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Selects the faster available batch-statistics backend using an identical local workload. */
public final class NetworkBackendSelector {
    public enum Backend { JAVA, RUST }

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile Backend selected = Backend.JAVA;
    private static volatile RustNetworkBackend rustBackend;
    private static volatile String status = "Java backend (not benchmarked)";
    private static volatile long javaNanos = -1L;
    private static volatile long rustNanos = -1L;

    private NetworkBackendSelector() {
    }

    public static void initializeAsync() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            RUNNING.set(true);
            try {
                initialize();
            } finally {
                RUNNING.set(false);
            }
        }, "Fiximizer-Network-Backend");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void reselectAsync() {
        if (RUNNING.get()) return;
        STARTED.set(false);
        initializeAsync();
    }

    private static void initialize() {
        int requested = FiximizerConfig.NETWORK_BACKEND_MODE.get();
        if (requested == 1) {
            selected = Backend.JAVA;
            status = "Java backend selected manually";
            return;
        }

        if (!FiximizerConfig.ENABLE_NETWORK_NATIVE_BACKEND.get()) {
            rustBackend = null;
            selected = Backend.JAVA;
            status = "Java selected; Rust backend disabled in settings";
            return;
        }

        try {
            rustBackend = RustNetworkBackend.load();
        } catch (IOException | RuntimeException | LinkageError exception) {
            rustBackend = null;
            selected = Backend.JAVA;
            status = requested == 2
                    ? "Rust requested, unavailable; using Java fallback"
                    : "Auto selected Java; Rust backend unavailable";
            Fiximizer.LOGGER.info("Network Rust backend unavailable: {}", safeMessage(exception));
            return;
        }

        if (requested == 2) {
            selected = Backend.RUST;
            status = "Rust backend selected manually";
            return;
        }
        benchmarkAndSelect();
    }

    private static void benchmarkAndSelect() {
        final int sampleCount = 64; // Match the live ping-statistics window.
        final int rounds = 40;
        long[] samples = new long[sampleCount];
        long[] javaOutput = new long[3];
        long[] rustOutput = new long[3];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = ((long) (i * 1103515245L + 12345L) & 0x7fffffffL) % 2500L;
        }

        for (int i = 0; i < 8; i++) {
            NetworkStatistics.summarizeJava(samples, javaOutput);
            rustBackend.summarize(samples, rustOutput);
        }
        if (!Arrays.equals(javaOutput, rustOutput)) {
            selected = Backend.JAVA;
            status = "Java selected; Rust correctness check failed";
            rustBackend = null;
            return;
        }

        long[] javaRuns = new long[rounds];
        long[] rustRuns = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            long start = System.nanoTime();
            NetworkStatistics.summarizeJava(samples, javaOutput);
            javaRuns[i] = System.nanoTime() - start;

            start = System.nanoTime();
            boolean success = rustBackend.summarize(samples, rustOutput);
            rustRuns[i] = System.nanoTime() - start;
            if (!success || !Arrays.equals(javaOutput, rustOutput)) {
                selected = Backend.JAVA;
                status = "Java selected; Rust runtime validation failed";
                rustBackend = null;
                return;
            }
        }

        javaNanos = median(javaRuns);
        rustNanos = median(rustRuns);
        if (rustNanos > 0L && rustNanos * 1.10d < javaNanos) {
            selected = Backend.RUST;
            status = "Auto selected Rust (at least 10% faster in local batch test)";
        } else {
            selected = Backend.JAVA;
            status = "Auto selected Java (equal or faster in local batch test)";
        }
        Fiximizer.LOGGER.info("Network backend: {}; Java median={} ns, Rust median={} ns",
                selected, javaNanos, rustNanos);
    }

    private static long median(long[] values) {
        long[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    public static boolean summarize(long[] samples, long[] output) {
        if (selected == Backend.RUST && rustBackend != null) {
            if (rustBackend.summarize(samples, output)) return true;
            selected = Backend.JAVA;
            status = "Rust runtime error; switched safely to Java";
            Fiximizer.LOGGER.warn("Rust network statistics failed; using Java fallback.");
        }
        return NetworkStatistics.summarizeJava(samples, output);
    }

    public static boolean isRunning() { return RUNNING.get(); }
    public static Backend selected() { return selected; }
    public static String status() { return status; }
    public static long javaMedianNanos() { return javaNanos; }
    public static long rustMedianNanos() { return rustNanos; }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) return throwable.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
}

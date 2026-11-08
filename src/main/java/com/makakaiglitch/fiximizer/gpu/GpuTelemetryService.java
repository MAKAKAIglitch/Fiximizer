package com.makakaiglitch.fiximizer.gpu;

import com.makakaiglitch.fiximizer.Fiximizer;
import oshi.SystemInfo;
import oshi.hardware.GpuStats;
import oshi.hardware.GraphicsCard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class GpuTelemetryService {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile ScheduledExecutorService executor;
    private static volatile List<GpuSensor> sensors = List.of();
    private static volatile List<GpuReading> latest = List.of();
    private static volatile String status = "Not started";

    private GpuTelemetryService() {
    }

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Fiximizer-GPU-Monitor");
            thread.setDaemon(true);
            return thread;
        };

        executor = Executors.newSingleThreadScheduledExecutor(factory);
        executor.execute(GpuTelemetryService::initialize);
        Runtime.getRuntime().addShutdownHook(new Thread(GpuTelemetryService::close,
                "Fiximizer-GPU-Shutdown"));
    }

    private static void initialize() {
        try {
            List<GraphicsCard> cards = new SystemInfo().getHardware().getGraphicsCards();
            if (cards.isEmpty()) {
                status = "No graphics adapter found";
                return;
            }

            List<GpuSensor> discovered = new ArrayList<>();
            for (GraphicsCard card : cards) {
                GpuStats stats = card.createStatsSession();
                stats.getGpuUtilization(); // Prime delta-based utilization backends.
                discovered.add(new GpuSensor(card, stats));
            }

            sensors = List.copyOf(discovered);
            sample();
            status = "Detected " + sensors.size() + " GPU(s)";
            executor.scheduleWithFixedDelay(
                    GpuTelemetryService::sampleSafely,
                    2L,
                    2L,
                    TimeUnit.SECONDS
            );
        } catch (Throwable throwable) {
            status = "GPU telemetry unavailable: " + safeMessage(throwable);
            Fiximizer.LOGGER.warn("GPU telemetry is unavailable; CPU-only scheduling remains active.", throwable);
        }
    }

    private static void sampleSafely() {
        try {
            sample();
        } catch (Throwable throwable) {
            status = "GPU telemetry error: " + safeMessage(throwable);
            Fiximizer.LOGGER.debug("GPU telemetry sample failed.", throwable);
        }
    }

    private static void sample() {
        List<GpuReading> values = new ArrayList<>(sensors.size());
        for (GpuSensor sensor : sensors) {
            GpuStats stats = sensor.stats();
            values.add(new GpuReading(
                    sensor.card().getName(),
                    sensor.card().getVendor(),
                    stats.getGpuUtilization(),
                    stats.getVramUsed(),
                    sensor.card().getVRam(),
                    stats.getTemperature(),
                    stats.getPowerDraw()
            ));
        }
        latest = List.copyOf(values);
    }

    public static List<GpuReading> latest() {
        return latest;
    }

    public static String status() {
        return status;
    }

    public static void close() {
        ScheduledExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
        }

        for (GpuSensor sensor : sensors) {
            try {
                sensor.stats().close();
            } catch (RuntimeException ignored) {
                // Best-effort release during JVM shutdown.
            }
        }
        sensors = List.of();
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return value.length() > 100 ? value.substring(0, 100) : value;
    }

    private record GpuSensor(GraphicsCard card, GpuStats stats) {
    }
}

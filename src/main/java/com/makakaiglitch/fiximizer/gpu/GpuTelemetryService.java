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
        List<GpuSensor> discovered = new ArrayList<>();
        try {
            List<GraphicsCard> cards = new SystemInfo().getHardware().getGraphicsCards();
            if (cards.isEmpty()) {
                status = "No graphics adapter found";
                return;
            }

            for (GraphicsCard card : cards) {
                GpuStats stats = card.createStatsSession();
                discovered.add(new GpuSensor(card, stats));
                stats.getGpuUtilization(); // Prime delta-based utilization backends.
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
        } catch (LinkageError | RuntimeException exception) {
            closeSensors(discovered);
            sensors = List.of();
            latest = List.of();
            status = "GPU telemetry unavailable: " + safeMessage(exception);
            Fiximizer.LOGGER.warn("GPU telemetry is unavailable; CPU-only scheduling remains active.", exception);
        }
    }

    private static void sampleSafely() {
        try {
            sample();
        } catch (LinkageError | RuntimeException exception) {
            status = "GPU telemetry error: " + safeMessage(exception);
            Fiximizer.LOGGER.debug("GPU telemetry sample failed.", exception);
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

        closeSensors(sensors);
        sensors = List.of();
    }

    private static void closeSensors(List<GpuSensor> values) {
        for (GpuSensor sensor : values) {
            try {
                sensor.stats().close();
            } catch (RuntimeException | LinkageError ignored) {
                // Best-effort release during initialization failure or JVM shutdown.
            }
        }
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

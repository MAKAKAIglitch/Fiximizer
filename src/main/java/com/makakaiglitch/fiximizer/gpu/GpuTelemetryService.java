package com.makakaiglitch.fiximizer.gpu;

import com.makakaiglitch.fiximizer.Fiximizer;
import oshi.SystemInfo;
import oshi.hardware.GraphicsCard;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uses the OSHI copy already shipped with Minecraft 1.21.1.
 *
 * Minecraft's OSHI 6.4.10 exposes GPU identity and total VRAM, but predates
 * OSHI's live GpuStats API. Live readings are therefore queried reflectively
 * and remain unavailable on stock 1.21.1 instead of introducing a second
 * OSHI module or crashing during startup.
 */
public final class GpuTelemetryService {
    private static final String GRAPHICS_CARD_API = "oshi.hardware.GraphicsCard";
    private static final String GPU_STATS_API = "oshi.hardware.GpuStats";

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
                Object stats = createStatsSession(card);
                discovered.add(new GpuSensor(card, stats));
            }

            sensors = List.copyOf(discovered);
            sample();

            boolean hasLiveStats = sensors.stream().anyMatch(sensor -> sensor.stats() != null);
            status = hasLiveStats
                    ? "Detected " + sensors.size() + " GPU(s); live sensor API available"
                    : "Detected " + sensors.size()
                            + " GPU(s); live sensor metrics are unavailable in Minecraft's OSHI 6.4.10";

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

    private static Object createStatsSession(GraphicsCard card) {
        try {
            Object stats = invokeApi(card, GRAPHICS_CARD_API, "createStatsSession");
            invokeApi(stats, GPU_STATS_API, "getGpuUtilization"); // Prime delta-based backends.
            return stats;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            // Stock Minecraft 1.21.1 uses OSHI 6.4.10, which has no GpuStats API.
            return null;
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
            Object stats = sensor.stats();
            values.add(new GpuReading(
                    sensor.card().getName(),
                    sensor.card().getVendor(),
                    readDouble(stats, "getGpuUtilization"),
                    readLong(stats, "getVramUsed"),
                    sensor.card().getVRam(),
                    readDouble(stats, "getTemperature"),
                    readDouble(stats, "getPowerDraw")
            ));
        }
        latest = List.copyOf(values);
    }

    private static double readDouble(Object target, String methodName) {
        Number value = invokeNumber(target, methodName);
        return value == null ? -1.0 : value.doubleValue();
    }

    private static long readLong(Object target, String methodName) {
        Number value = invokeNumber(target, methodName);
        return value == null ? -1L : value.longValue();
    }

    private static Number invokeNumber(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Object value = invokeApi(target, GPU_STATS_API, methodName);
            return value instanceof Number number ? number : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return null;
        }
    }

    private static Object invokeApi(Object receiver, String apiClassName, String methodName)
            throws ReflectiveOperationException {
        if (receiver == null) {
            throw new IllegalArgumentException("Receiver is null");
        }
        ClassLoader loader = receiver.getClass().getClassLoader();
        Class<?> api = Class.forName(apiClassName, false, loader);
        Method method = api.getMethod(methodName);
        return method.invoke(receiver);
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
            if (sensor.stats() == null) {
                continue;
            }
            try {
                invokeApi(sensor.stats(), GPU_STATS_API, "close");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
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

    private record GpuSensor(GraphicsCard card, Object stats) {
    }
}

package com.makakaiglitch.fiximizer.scheduler;

/**
 * Runtime strategy presets. Each preset controls only Fiximizer-owned work;
 * it never changes Minecraft or third-party thread priorities.
 */
public enum OptimizationProfile {
    SMOOTHNESS(
            "smoothness",
            2,
            48,
            0.58,
            0.78,
            true,
            0.85,
            60.0,
            1_000_000L,
            8L * 1024L * 1024L
    ),
    LOW_LATENCY(
            "low_latency",
            2,
            32,
            0.55,
            0.76,
            true,
            0.90,
            55.0,
            2_000_000L,
            16L * 1024L * 1024L
    ),
    BALANCED(
            "balanced",
            3,
            128,
            0.70,
            0.85,
            true,
            0.75,
            75.0,
            250_000L,
            2L * 1024L * 1024L
    ),
    MAX_PERFORMANCE(
            "max_performance",
            4,
            256,
            0.80,
            0.93,
            true,
            0.60,
            90.0,
            250_000L,
            1L * 1024L * 1024L
    ),
    MEMORY_SAVER(
            "memory_saver",
            1,
            16,
            0.35,
            0.60,
            false,
            1.0,
            0.0,
            Long.MAX_VALUE,
            Long.MAX_VALUE
    );

    private final String id;
    private final int maximumWorkers;
    private final int queueCapacity;
    private final double reduceWorkersAtCpuLoad;
    private final double singleWorkerAtCpuLoad;
    private final boolean openClAllowed;
    private final double openClCpuThreshold;
    private final double maximumGpuLoadForOffload;
    private final long minimumGpuOperations;
    private final long minimumGpuTransferBytes;

    OptimizationProfile(
            String id,
            int maximumWorkers,
            int queueCapacity,
            double reduceWorkersAtCpuLoad,
            double singleWorkerAtCpuLoad,
            boolean openClAllowed,
            double openClCpuThreshold,
            double maximumGpuLoadForOffload,
            long minimumGpuOperations,
            long minimumGpuTransferBytes
    ) {
        this.id = id;
        this.maximumWorkers = maximumWorkers;
        this.queueCapacity = queueCapacity;
        this.reduceWorkersAtCpuLoad = reduceWorkersAtCpuLoad;
        this.singleWorkerAtCpuLoad = singleWorkerAtCpuLoad;
        this.openClAllowed = openClAllowed;
        this.openClCpuThreshold = openClCpuThreshold;
        this.maximumGpuLoadForOffload = maximumGpuLoadForOffload;
        this.minimumGpuOperations = minimumGpuOperations;
        this.minimumGpuTransferBytes = minimumGpuTransferBytes;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "profile.fiximizer." + id;
    }

    public String descriptionKey() {
        return translationKey() + ".description";
    }

    public int maximumWorkers() {
        return maximumWorkers;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    public double reduceWorkersAtCpuLoad() {
        return reduceWorkersAtCpuLoad;
    }

    public double singleWorkerAtCpuLoad() {
        return singleWorkerAtCpuLoad;
    }

    public boolean openClAllowed() {
        return openClAllowed;
    }

    public double openClCpuThreshold() {
        return openClCpuThreshold;
    }

    public double maximumGpuLoadForOffload() {
        return maximumGpuLoadForOffload;
    }

    public long minimumGpuOperations() {
        return minimumGpuOperations;
    }

    public long minimumGpuTransferBytes() {
        return minimumGpuTransferBytes;
    }

    public int queueOrder(TaskPriority priority) {
        return switch (this) {
            case LOW_LATENCY -> switch (priority) {
                case CRITICAL -> 0;
                case HIGH -> 1;
                case NORMAL -> 3;
                case BACKGROUND -> 5;
            };
            case SMOOTHNESS -> switch (priority) {
                case CRITICAL -> 0;
                case HIGH -> 1;
                case NORMAL -> 2;
                case BACKGROUND -> 4;
            };
            case MEMORY_SAVER -> switch (priority) {
                case CRITICAL -> 0;
                case HIGH -> 1;
                case NORMAL -> 2;
                case BACKGROUND -> 4;
            };
            case BALANCED, MAX_PERFORMANCE -> priority.queueOrder();
        };
    }

    public OptimizationProfile next() {
        OptimizationProfile[] profiles = values();
        return profiles[(ordinal() + 1) % profiles.length];
    }

    public static OptimizationProfile fromId(String id) {
        if (id == null || id.isBlank()) {
            return SMOOTHNESS;
        }
        for (OptimizationProfile profile : values()) {
            if (profile.id.equalsIgnoreCase(id.trim())) {
                return profile;
            }
        }
        return SMOOTHNESS;
    }
}

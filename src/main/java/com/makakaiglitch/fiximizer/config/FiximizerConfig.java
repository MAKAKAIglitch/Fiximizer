package com.makakaiglitch.fiximizer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class FiximizerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_DIAGNOSTICS;
    public static final ModConfigSpec.IntValue SAMPLE_INTERVAL_SECONDS;
    public static final ModConfigSpec.BooleanValue SHOW_STARTUP_SUMMARY;
    public static final ModConfigSpec.BooleanValue DETECT_COMPATIBLE_MODS;

    public static final ModConfigSpec.BooleanValue ENABLE_JFR_PROFILING;
    public static final ModConfigSpec.BooleanValue ENABLE_PROFILE_ANALYSIS;
    public static final ModConfigSpec.IntValue MAX_PROFILE_SECONDS;

    public static final ModConfigSpec.BooleanValue ADAPTIVE_SCHEDULER_ENABLED;
    public static final ModConfigSpec.IntValue MAX_BACKGROUND_WORKERS;

    public static final ModConfigSpec.BooleanValue ENABLE_GPU_TELEMETRY;
    public static final ModConfigSpec.BooleanValue ENABLE_OPENCL_PROBE;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("diagnostics");

        ENABLE_DIAGNOSTICS = BUILDER
                .comment("Collect lightweight local runtime diagnostics.")
                .translation("config.fiximizer.diagnostics.enabled")
                .define("enabled", true);

        SAMPLE_INTERVAL_SECONDS = BUILDER
                .comment("Seconds between diagnostic samples.")
                .translation("config.fiximizer.diagnostics.sample_interval")
                .defineInRange("sampleIntervalSeconds", 5, 1, 60);

        SHOW_STARTUP_SUMMARY = BUILDER
                .comment("Log a short optional-mod detection summary during client startup.")
                .translation("config.fiximizer.diagnostics.startup_summary")
                .define("showStartupSummary", true);

        BUILDER.pop();
        BUILDER.push("compatibility");

        DETECT_COMPATIBLE_MODS = BUILDER
                .comment("Detect known optional mods that may receive dedicated integrations in later releases.")
                .translation("config.fiximizer.compatibility.detect_mods")
                .define("detectKnownMods", true);

        BUILDER.pop();
        BUILDER.push("profiler");

        ENABLE_JFR_PROFILING = BUILDER
                .comment("Allow Java Flight Recorder CPU, thread, allocation and GC recordings.")
                .translation("config.fiximizer.profiler.jfr_enabled")
                .define("enableJfrProfiling", true);

        ENABLE_PROFILE_ANALYSIS = BUILDER
                .comment("Generate a local hotspot and investigation-priority report after saving a JFR recording.")
                .translation("config.fiximizer.profiler.analysis_enabled")
                .define("enableProfileAnalysis", true);

        MAX_PROFILE_SECONDS = BUILDER
                .comment("Maximum duration of one JFR recording before it is automatically saved.")
                .translation("config.fiximizer.profiler.max_seconds")
                .defineInRange("maxProfileSeconds", 180, 30, 600);

        BUILDER.pop();
        BUILDER.push("scheduler");

        ADAPTIVE_SCHEDULER_ENABLED = BUILDER
                .comment("Reduce Fiximizer background worker count when system CPU load is high.")
                .translation("config.fiximizer.scheduler.adaptive")
                .define("adaptive", true);

        MAX_BACKGROUND_WORKERS = BUILDER
                .comment("Global upper limit for Fiximizer-managed background workers; gameplay profiles may use fewer.")
                .translation("config.fiximizer.scheduler.max_workers")
                .defineInRange("maxBackgroundWorkers", 4, 1, 4);

        BUILDER.pop();
        BUILDER.push("gpu");

        ENABLE_GPU_TELEMETRY = BUILDER
                .comment("Read GPU utilization, temperature and memory through OSHI when supported.")
                .translation("config.fiximizer.gpu.telemetry")
                .define("enableTelemetry", true);

        ENABLE_OPENCL_PROBE = BUILDER
                .comment("Check for an installed OpenCL runtime and available GPU compute devices.")
                .translation("config.fiximizer.gpu.opencl_probe")
                .define("enableOpenClProbe", true);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private FiximizerConfig() {
    }
}

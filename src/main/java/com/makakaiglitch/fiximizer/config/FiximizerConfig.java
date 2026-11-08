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
    public static final ModConfigSpec.BooleanValue ENABLE_FRAME_TIME_PROFILING;
    public static final ModConfigSpec.BooleanValue ENABLE_SERVER_TICK_PROFILING;
    public static final ModConfigSpec.BooleanValue ENABLE_STALL_WATCHDOG;
    public static final ModConfigSpec.IntValue STALL_THRESHOLD_MS;
    public static final ModConfigSpec.IntValue STALL_REPEAT_INTERVAL_MS;
    public static final ModConfigSpec.IntValue MAX_DUMPS_PER_STALL;
    public static final ModConfigSpec.IntValue STALL_STACK_DEPTH;
    public static final ModConfigSpec.IntValue MAX_STALL_REPORTS;
    public static final ModConfigSpec.BooleanValue ENABLE_STALL_JFR;
    public static final ModConfigSpec.IntValue STALL_JFR_WINDOW_SECONDS;
    public static final ModConfigSpec.IntValue STALL_JFR_MAX_SIZE_MIB;
    public static final ModConfigSpec.IntValue STALL_JFR_DUMP_INTERVAL_MS;
    public static final ModConfigSpec.IntValue MAX_STALL_JFR_EXPORTS;

    public static final ModConfigSpec.BooleanValue ADAPTIVE_SCHEDULER_ENABLED;
    public static final ModConfigSpec.IntValue MAX_BACKGROUND_WORKERS;
    public static final ModConfigSpec.BooleanValue ENABLE_MEMORY_GUARD;
    public static final ModConfigSpec.IntValue MEMORY_PRESSURE_THRESHOLD;
    public static final ModConfigSpec.IntValue MEMORY_RECOVERY_THRESHOLD;
    public static final ModConfigSpec.BooleanValue ENABLE_STRING_CANONICALIZATION;
    public static final ModConfigSpec.BooleanValue RECOMMEND_G1_STRING_DEDUP;

    public static final ModConfigSpec.BooleanValue ENABLE_CREATE_INTEGRATION;
    public static final ModConfigSpec.BooleanValue ENABLE_AERONAUTICS_INTEGRATION;
    public static final ModConfigSpec.BooleanValue ENABLE_SABLE_INTEGRATION;
    public static final ModConfigSpec.BooleanValue ENABLE_DH_INTEGRATION;
    public static final ModConfigSpec.BooleanValue ENABLE_VOXY_INTEGRATION;

    public static final ModConfigSpec.BooleanValue ENABLE_GPU_TELEMETRY;
    public static final ModConfigSpec.BooleanValue ENABLE_OPENCL_PROBE;
    public static final ModConfigSpec.BooleanValue ENABLE_GPU_BENCHMARK;
    public static final ModConfigSpec.BooleanValue ENABLE_GPU_SPILL;
    public static final ModConfigSpec.IntValue MAX_GPU_SPILL_MIB;

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
        BUILDER.push("profiler");

        ENABLE_JFR_PROFILING = BUILDER
                .comment("Allow Java Flight Recorder CPU, thread, allocation and GC recordings.")
                .translation("config.fiximizer.profiler.jfr_enabled")
                .define("enableJfrProfiling", true);

        ENABLE_PROFILE_ANALYSIS = BUILDER
                .comment("Generate a local hotspot and investigation-priority report after a JFR recording.")
                .translation("config.fiximizer.profiler.analysis_enabled")
                .define("enableProfileAnalysis", true);

        MAX_PROFILE_SECONDS = BUILDER
                .comment("Maximum duration of one JFR recording before it is automatically saved.")
                .translation("config.fiximizer.profiler.max_seconds")
                .defineInRange("maxProfileSeconds", 180, 30, 600);

        ENABLE_FRAME_TIME_PROFILING = BUILDER
                .comment("Measure client render-call and client-tick wall-clock durations with bounded rolling windows.")
                .translation("config.fiximizer.profiler.frame_timing")
                .define("enableClientTiming", true);

        ENABLE_SERVER_TICK_PROFILING = BUILDER
                .comment("Measure integrated single-player server tick wall-clock duration.")
                .translation("config.fiximizer.profiler.server_tick")
                .define("enableServerTickTiming", true);

        BUILDER.pop();
        BUILDER.push("watchdog");

        ENABLE_STALL_WATCHDOG = BUILDER
                .comment("Detect long client, render and integrated-server event spans using a separate daemon thread.")
                .translation("config.fiximizer.watchdog.enabled")
                .define("enabled", true);

        STALL_THRESHOLD_MS = BUILDER
                .comment("Minimum event duration before a diagnostic snapshot is captured.")
                .translation("config.fiximizer.watchdog.threshold")
                .defineInRange("thresholdMillis", 2000, 500, 30000);

        STALL_REPEAT_INTERVAL_MS = BUILDER
                .comment("Minimum time between snapshots while the same event remains stalled.")
                .translation("config.fiximizer.watchdog.repeat_interval")
                .defineInRange("repeatIntervalMillis", 2000, 500, 30000);

        MAX_DUMPS_PER_STALL = BUILDER
                .comment("Maximum number of snapshots captured for one continuous event stall.")
                .translation("config.fiximizer.watchdog.max_dumps")
                .defineInRange("maxDumpsPerStall", 8, 1, 30);

        STALL_STACK_DEPTH = BUILDER
                .comment("Maximum stack frames captured for each JVM thread in a diagnostic report.")
                .translation("config.fiximizer.watchdog.stack_depth")
                .defineInRange("stackDepth", 32, 8, 128);

        MAX_STALL_REPORTS = BUILDER
                .comment("Maximum number of local text stall reports retained on disk.")
                .translation("config.fiximizer.watchdog.retention")
                .defineInRange("maxReports", 20, 5, 100);

        ENABLE_STALL_JFR = BUILDER
                .comment("Keep a bounded JFR ring buffer so stall reports can include recent CPU, GC and allocation events.")
                .translation("config.fiximizer.watchdog.jfr_enabled")
                .define("rollingJfr", true);

        STALL_JFR_WINDOW_SECONDS = BUILDER
                .comment("Maximum age of events kept in the rolling JFR buffer; the size limit may shorten this window.")
                .translation("config.fiximizer.watchdog.jfr_window")
                .defineInRange("jfrWindowSeconds", 60, 15, 180);

        STALL_JFR_MAX_SIZE_MIB = BUILDER
                .comment("Maximum disk repository size for the rolling JFR buffer.")
                .translation("config.fiximizer.watchdog.jfr_size")
                .defineInRange("jfrMaxSizeMiB", 64, 16, 256);

        STALL_JFR_DUMP_INTERVAL_MS = BUILDER
                .comment("Minimum interval between exported JFR snapshots.")
                .translation("config.fiximizer.watchdog.jfr_interval")
                .defineInRange("jfrDumpIntervalMillis", 30000, 5000, 120000);

        MAX_STALL_JFR_EXPORTS = BUILDER
                .comment("Maximum number of exported stall JFR files retained locally.")
                .translation("config.fiximizer.watchdog.jfr_retention")
                .defineInRange("maxJfrExports", 6, 1, 20);

        BUILDER.pop();
        BUILDER.push("scheduler");

        ADAPTIVE_SCHEDULER_ENABLED = BUILDER
                .comment("Adapt Fiximizer worker count using system CPU load and observed queue wait.")
                .translation("config.fiximizer.scheduler.adaptive")
                .define("adaptive", true);

        MAX_BACKGROUND_WORKERS = BUILDER
                .comment("Global upper limit for Fiximizer-managed background workers; gameplay profiles may use fewer.")
                .translation("config.fiximizer.scheduler.max_workers")
                .defineInRange("maxBackgroundWorkers", 4, 1, 8);

        ENABLE_MEMORY_GUARD = BUILDER
                .comment("Temporarily switch Fiximizer to Memory Saver when heap or physical memory stays under pressure.")
                .translation("config.fiximizer.scheduler.memory_guard")
                .define("automaticMemoryGuard", true);

        MEMORY_PRESSURE_THRESHOLD = BUILDER
                .comment("Heap usage percentage that starts the automatic memory guard after sustained pressure.")
                .translation("config.fiximizer.scheduler.memory_pressure")
                .defineInRange("memoryPressureThresholdPercent", 88, 75, 97);

        MEMORY_RECOVERY_THRESHOLD = BUILDER
                .comment("Heap usage percentage below which automatic Memory Saver can be released.")
                .translation("config.fiximizer.scheduler.memory_recovery")
                .defineInRange("memoryRecoveryThresholdPercent", 70, 45, 80);

        BUILDER.pop();
        BUILDER.push("memory");

        ENABLE_STRING_CANONICALIZATION = BUILDER
                .comment("Enable bounded, weak string canonicalization for explicitly submitted immutable integration metadata. No heap scanning or per-tick work.")
                .translation("config.fiximizer.memory.string_dedup")
                .define("stringCanonicalization", true);

        RECOMMEND_G1_STRING_DEDUP = BUILDER
                .comment("Add optional G1 String Deduplication to JVM argument recommendations. Requires G1 and a restart; it may add garbage-collection CPU work.")
                .translation("config.fiximizer.memory.g1_string_dedup")
                .define("recommendG1StringDeduplication", false);

        BUILDER.pop();
        BUILDER.push("integrations");

        DETECT_COMPATIBLE_MODS = BUILDER
                .comment("Detect supported optional mods and display their installed versions.")
                .translation("config.fiximizer.compatibility.detect_mods")
                .define("detectKnownMods", true);

        ENABLE_CREATE_INTEGRATION = BUILDER
                .comment("Allow Fiximizer's Create integration to activate only when Create is installed.")
                .translation("config.fiximizer.integrations.create")
                .define("create", true);

        ENABLE_AERONAUTICS_INTEGRATION = BUILDER
                .comment("Allow the Aeronautics integration to activate only when the mod is installed.")
                .translation("config.fiximizer.integrations.aeronautics")
                .define("aeronautics", true);

        ENABLE_SABLE_INTEGRATION = BUILDER
                .comment("Allow the Sable integration to activate only when Sable is installed.")
                .translation("config.fiximizer.integrations.sable")
                .define("sable", true);

        ENABLE_DH_INTEGRATION = BUILDER
                .comment("Allow the Distant Horizons integration to activate only when it is installed.")
                .translation("config.fiximizer.integrations.distanthorizons")
                .define("distantHorizons", true);

        ENABLE_VOXY_INTEGRATION = BUILDER
                .comment("Allow the Voxy integration to activate only when Voxy is installed.")
                .translation("config.fiximizer.integrations.voxy")
                .define("voxy", true);

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

        ENABLE_GPU_BENCHMARK = BUILDER
                .comment("Allow a manual bounded OpenCL compute and transfer benchmark from the GPU tab.")
                .translation("config.fiximizer.gpu.benchmark")
                .define("enableBenchmark", true);

        ENABLE_GPU_SPILL = BUILDER
                .comment("Allow Fiximizer integrations to store explicitly managed disposable cache blocks in a bounded VRAM pool during memory pressure.")
                .translation("config.fiximizer.gpu.spill")
                .define("enableVramSpill", true);

        MAX_GPU_SPILL_MIB = BUILDER
                .comment("Maximum VRAM pool size. The live GPU memory monitor and a graphics reserve may lower this limit.")
                .translation("config.fiximizer.gpu.spill_limit")
                .defineInRange("maxVramSpillMiB", 256, 64, 512);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private FiximizerConfig() {
    }
}

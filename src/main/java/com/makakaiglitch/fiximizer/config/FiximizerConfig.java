package com.makakaiglitch.fiximizer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class FiximizerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_DIAGNOSTICS;
    public static final ModConfigSpec.IntValue SAMPLE_INTERVAL_SECONDS;
    public static final ModConfigSpec.BooleanValue SHOW_STARTUP_SUMMARY;
    public static final ModConfigSpec.BooleanValue DETECT_COMPATIBLE_MODS;

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
        SPEC = BUILDER.build();
    }

    private FiximizerConfig() {
    }
}

package com.makakaiglitch.fiximizer.benchmark;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfileManager;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Public, privacy-minimized benchmark environment.
 * Mod identifiers are hashed; player data, world paths and server addresses
 * are intentionally not collected.
 */
public record BenchmarkEnvironment(
        String minecraftVersion,
        String neoForgeVersion,
        String fiximizerVersion,
        String javaVersion,
        String javaVendor,
        String osName,
        String osArch,
        int availableProcessors,
        long maxHeapBytes,
        int modCount,
        String modFingerprint,
        int renderDistance,
        int simulationDistance,
        String optimizationProfile,
        String settingsFingerprint,
        String hardwareFingerprint
) {
    public static BenchmarkEnvironment capture(Minecraft minecraft) {
        ModList modList = ModList.get();
        List<String> descriptors = modList.getMods().stream()
                .filter(info -> !"fiximizer".equals(info.getModId())
                        && !"minecraft".equals(info.getModId())
                        && !"neoforge".equals(info.getModId()))
                .map(info -> info.getModId() + ":" + info.getVersion())
                .sorted()
                .toList();

        String modFingerprint = digest(String.join("\n", descriptors));
        String minecraftVersion = SharedConstants.getCurrentVersion().getName();
        String neoForgeVersion = modVersion(modList, "neoforge");
        String fiximizerVersion = modVersion(modList, "fiximizer");
        String javaVersion = property("java.version");
        String javaVendor = property("java.vendor");
        String osName = property("os.name");
        String osArch = property("os.arch");
        int processors = Runtime.getRuntime().availableProcessors();
        long maxHeap = Runtime.getRuntime().maxMemory();
        int renderDistance = minecraft.options.renderDistance().get();
        int simulationDistance = minecraft.options.simulationDistance().get();
        String profile = OptimizationProfileManager.current().id();

        String settings = String.join("|",
                Integer.toString(renderDistance),
                Integer.toString(simulationDistance),
                profile,
                Boolean.toString(FiximizerConfig.ENABLE_FRAME_TIME_PROFILING.get()),
                Boolean.toString(FiximizerConfig.ENABLE_SERVER_TICK_PROFILING.get()),
                Boolean.toString(FiximizerConfig.ENABLE_DIAGNOSTICS.get()),
                Boolean.toString(FiximizerConfig.ENABLE_MEMORY_GUARD.get()),
                Boolean.toString(FiximizerConfig.ENABLE_STRING_CANONICALIZATION.get()),
                Boolean.toString(FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()),
                Boolean.toString(FiximizerConfig.ENABLE_NETWORK_NATIVE_BACKEND.get()),
                Boolean.toString(FiximizerConfig.ENABLE_GPU_TELEMETRY.get()),
                Boolean.toString(FiximizerConfig.ENABLE_OPENCL_PROBE.get()),
                Boolean.toString(FiximizerConfig.ENABLE_GPU_BENCHMARK.get()),
                Boolean.toString(FiximizerConfig.ENABLE_GPU_SPILL.get()),
                Boolean.toString(FiximizerConfig.ENABLE_JFR_PROFILING.get()),
                Boolean.toString(FiximizerConfig.ENABLE_STALL_WATCHDOG.get()),
                Boolean.toString(FiximizerConfig.ENABLE_STALL_JFR.get())
        );
        String settingsFingerprint = digest(settings);
        String hardwareFingerprint = digest(String.join("|",
                osName.toLowerCase(Locale.ROOT),
                osArch.toLowerCase(Locale.ROOT),
                Integer.toString(processors),
                Long.toString(maxHeap),
                javaVendor,
                javaVersion
        ));

        return new BenchmarkEnvironment(
                minecraftVersion,
                neoForgeVersion,
                fiximizerVersion,
                javaVersion,
                javaVendor,
                osName,
                osArch,
                processors,
                maxHeap,
                descriptors.size(),
                modFingerprint,
                renderDistance,
                simulationDistance,
                profile,
                settingsFingerprint,
                hardwareFingerprint
        );
    }

    /** Fiximizer version is recorded but excluded to allow before/after comparisons across its releases. */
    public String compatibilityFingerprint(BenchmarkScenario scenario) {
        String material = String.join("|",
                "benchmark-schema-1",
                scenario.name(),
                minecraftVersion,
                neoForgeVersion,
                javaVersion,
                hardwareFingerprint,
                modFingerprint,
                settingsFingerprint
        );
        return digest(material);
    }

    private static String modVersion(ModList modList, String modId) {
        return modList.getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    private static String property(String key) {
        try {
            return System.getProperty(key, "unknown");
        } catch (SecurityException exception) {
            return "unavailable";
        }
    }

    static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

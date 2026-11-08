package com.makakaiglitch.fiximizer.diagnostics;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Creates conservative Java 21 heap recommendations based on physical RAM
 * and the number of loaded mods. These arguments must be applied in the launcher
 * before the next game start; a running JVM cannot change -Xms or -Xmx.
 */
public final class JvmArgumentAdvisor {
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long MIB = 1024L * 1024L;
    private static final long MAX_HEAP = 10L * GIB;

    private JvmArgumentAdvisor() {
    }

    /**
     * Production entry point. Reads runtime information from Java/NeoForge,
     * then delegates the calculation to the pure overload below.
     */
    public static Recommendation recommend() {
        long totalPhysicalBytes = physicalMemoryTotal();
        int modCount = safeModCount();

        if (totalPhysicalBytes <= 0L) {
            totalPhysicalBytes = Math.max(8L * GIB, Runtime.getRuntime().maxMemory() * 2L);
        }

        Recommendation base = recommend(totalPhysicalBytes, modCount);
        return appendOptionalStringDeduplication(base);
    }

    /**
     * Pure calculation entry point. It can be unit-tested without bootstrapping
     * Minecraft or loading NeoForge's ModList.
     */
    public static Recommendation recommend(long totalPhysicalBytes, int modCount) {
        if (totalPhysicalBytes <= 0L) {
            throw new IllegalArgumentException("Physical memory must be a positive byte count.");
        }
        if (modCount < 0) {
            throw new IllegalArgumentException("Loaded mod count cannot be negative.");
        }

        double ratio = modCount >= 150 ? 0.38 : modCount >= 80 ? 0.32 : 0.28;
        long suggested = (long) (totalPhysicalBytes * ratio);

        // Keep a proportional minimum on small-RAM systems instead of forcing a fixed 2 GiB heap.
        long minimumHeap = Math.min(2L * GIB, Math.max(GIB, (long) (totalPhysicalBytes * 0.35)));

        // Round down to 512 MiB so the recommendation does not oversubscribe the machine.
        long halfGib = GIB / 2L;
        suggested = (suggested / halfGib) * halfGib;
        suggested = Math.min(MAX_HEAP, suggested);

        // Reserve at least 60% of physical RAM for Windows, launcher, GPU driver and other applications.
        long physicalLimit = (long) (totalPhysicalBytes * 0.40);
        suggested = Math.max(minimumHeap, Math.min(suggested, physicalLimit));

        long initial = Math.max(GIB, Math.min(2L * GIB, suggested / 4L));
        String arguments = "-Xms" + formatMiB(initial) + " -Xmx" + formatMiB(suggested);

        String note = String.format(
                Locale.ROOT,
                "Detected %.1f GiB physical RAM and %d loaded mods. Java 21 keeps its default garbage collector; Fiximizer only recommends heap bounds.",
                totalPhysicalBytes / (double) GIB,
                modCount
        );

        return new Recommendation(
                totalPhysicalBytes,
                suggested,
                initial,
                modCount,
                arguments,
                note
        );
    }

    /**
     * G1 String Deduplication is supported only when the active collector is G1.
     * Reading collector names is a one-time advisor/UI operation, not a tick hook.
     */
    public static boolean supportsG1StringDeduplication() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .anyMatch(bean -> bean.getName().toLowerCase(Locale.ROOT).contains("g1"));
    }

    private static Recommendation appendOptionalStringDeduplication(Recommendation base) {
        if (!FiximizerConfig.RECOMMEND_G1_STRING_DEDUP.get()) {
            return base;
        }

        if (!supportsG1StringDeduplication()) {
            return new Recommendation(
                    base.physicalMemoryBytes(),
                    base.maximumHeapBytes(),
                    base.initialHeapBytes(),
                    base.loadedModCount(),
                    base.arguments(),
                    base.note() + " G1 String Deduplication was not added because the active garbage collector is not G1."
            );
        }

        return new Recommendation(
                base.physicalMemoryBytes(),
                base.maximumHeapBytes(),
                base.initialHeapBytes(),
                base.loadedModCount(),
                base.arguments() + " -XX:+UseStringDeduplication",
                base.note() + " Optional G1 String Deduplication is included; it can reduce duplicate String backing storage but may add GC CPU work. Compare before and after."
        );
    }

    public static Path write(Path configDirectory) throws IOException {
        Recommendation recommendation = recommend();
        Files.createDirectories(configDirectory);
        Path output = configDirectory.resolve("fiximizer-jvm-arguments.txt");
        String body = recommendation.arguments() + System.lineSeparator()
                + System.lineSeparator()
                + recommendation.note() + System.lineSeparator()
                + "Paste the first line into Prism Launcher > Edit Instance > Settings > Java > JVM arguments."
                + System.lineSeparator()
                + "These arguments apply after restarting Minecraft; a running JVM cannot change its heap limits."
                + System.lineSeparator();
        Files.writeString(output, body, StandardCharsets.UTF_8);
        return output;
    }

    private static long physicalMemoryTotal() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            return extended.getTotalMemorySize();
        }
        return -1L;
    }

    private static int safeModCount() {
        try {
            return ModList.get().getMods().size();
        } catch (RuntimeException | LinkageError exception) {
            return 0;
        }
    }

    private static String formatMiB(long bytes) {
        return (bytes / MIB) + "M";
    }

    public record Recommendation(
            long physicalMemoryBytes,
            long maximumHeapBytes,
            long initialHeapBytes,
            int loadedModCount,
            String arguments,
            String note
    ) {
    }
}

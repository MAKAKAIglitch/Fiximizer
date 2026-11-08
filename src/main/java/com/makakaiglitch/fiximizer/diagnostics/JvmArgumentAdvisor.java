package com.makakaiglitch.fiximizer.diagnostics;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.memory.RuntimeMemoryCapabilities;
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

    private static volatile Recommendation cachedRuntimeRecommendation;
    private static volatile Boolean cachedG1Support;

    private JvmArgumentAdvisor() {
    }

    /**
     * Production entry point. Reads runtime information from Java/NeoForge,
     * then delegates the calculation to the pure overload below.
     */
    public static Recommendation recommend() {
        Recommendation base = runtimeRecommendation();
        base = withCompactObjectHeaders(base, RuntimeMemoryCapabilities.current());

        // Do not query collector MXBeans on every dashboard frame while the
        // optional G1 setting is disabled (the default).
        if (!FiximizerConfig.RECOMMEND_G1_STRING_DEDUP.get()) {
            return base;
        }
        return withG1StringDeduplication(base, true, supportsG1StringDeduplication());
    }

    private static Recommendation runtimeRecommendation() {
        Recommendation current = cachedRuntimeRecommendation;
        if (current != null) {
            return current;
        }

        synchronized (JvmArgumentAdvisor.class) {
            current = cachedRuntimeRecommendation;
            if (current == null) {
                long totalPhysicalBytes = physicalMemoryTotal();
                int modCount = safeModCount();

                if (totalPhysicalBytes <= 0L) {
                    totalPhysicalBytes = Math.max(8L * GIB, Runtime.getRuntime().maxMemory() * 2L);
                }

                current = recommend(totalPhysicalBytes, modCount);
                cachedRuntimeRecommendation = current;
            }
        }
        return current;
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
        Boolean current = cachedG1Support;
        if (current != null) {
            return current;
        }

        synchronized (JvmArgumentAdvisor.class) {
            current = cachedG1Support;
            if (current == null) {
                current = ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .anyMatch(bean -> bean.getName().toLowerCase(Locale.ROOT).contains("g1"));
                cachedG1Support = current;
            }
        }
        return current;
    }

    /**
     * Automatically selects the broad JDK 25 heap optimization when the
     * running VM exposes it and its loaded-class safety limit is not reached.
     * Java 21 and VMs without this HotSpot option keep the compatible fallback.
     */
    static Recommendation withCompactObjectHeaders(
            Recommendation base,
            RuntimeMemoryCapabilities.Snapshot capabilities
    ) {
        if (capabilities.compactObjectHeadersEnabled()) {
            return copyWithNote(base, base.note()
                    + " Compact Object Headers are already enabled by the running JVM.");
        }

        if (capabilities.shouldRecommendCompactObjectHeaders()) {
            return copyWithArgumentsAndNote(
                    base,
                    base.arguments() + " -XX:+UseCompactObjectHeaders",
                    base.note() + " JDK " + capabilities.javaMajor()
                            + " exposes Compact Object Headers; the flag is recommended for the next launch."
            );
        }

        if (capabilities.compactObjectHeadersClassLimitReached()
                && capabilities.compactObjectHeadersSupported()) {
            return copyWithNote(base, base.note()
                    + " Compact Object Headers were not recommended because the JVM reports "
                    + capabilities.loadedClassCount() + " loaded classes and the feature limit is 4,000,000.");
        }

        return base;
    }

    private static Recommendation copyWithNote(Recommendation base, String note) {
        return copyWithArgumentsAndNote(base, base.arguments(), note);
    }

    private static Recommendation copyWithArgumentsAndNote(
            Recommendation base,
            String arguments,
            String note
    ) {
        return new Recommendation(
                base.physicalMemoryBytes(),
                base.maximumHeapBytes(),
                base.initialHeapBytes(),
                base.loadedModCount(),
                arguments,
                note
        );
    }

    /**
     * Pure policy function so JVM-flag selection can be tested without a live
     * HotSpot collector or NeoForge configuration.
     */
    static Recommendation withG1StringDeduplication(
            Recommendation base,
            boolean requested,
            boolean g1Detected
    ) {
        if (!requested) {
            return base;
        }

        if (!g1Detected) {
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

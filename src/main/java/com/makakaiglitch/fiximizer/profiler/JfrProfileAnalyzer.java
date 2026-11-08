package com.makakaiglitch.fiximizer.profiler;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Streams a JFR file and writes a lightweight offline hotspot report.
 *
 * CPU rankings are based on statistical execution samples, not exact method
 * wall time. Allocation weights are sampled estimates, not exact byte totals.
 */
public final class JfrProfileAnalyzer {
    private static final int MAX_CPU_HOTSPOTS = 20;
    private static final int MAX_ALLOCATION_HOTSPOTS = 12;

    private JfrProfileAnalyzer() {
    }

    public static Path analyzeAndWrite(Path recordingFile) throws IOException {
        Map<String, Long> cpuSamplesByMethod = new HashMap<>();
        Map<String, Long> allocationWeightByClass = new HashMap<>();

        long cpuSampleCount = 0L;
        long cpuSamplesWithJavaFrame = 0L;
        long allocationSampleCount = 0L;
        long estimatedAllocationBytes = 0L;
        long garbageCollectionCount = 0L;
        long garbageCollectionNanos = 0L;

        try (RecordingFile file = new RecordingFile(recordingFile)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String eventName = event.getEventType().getName();

                if ("jdk.ExecutionSample".equals(eventName)) {
                    cpuSampleCount++;

                    RecordedStackTrace stackTrace = event.getStackTrace();
                    if (stackTrace == null) {
                        continue;
                    }

                    for (RecordedFrame frame : stackTrace.getFrames()) {
                        if (!frame.isJavaFrame()) {
                            continue;
                        }

                        RecordedMethod method = frame.getMethod();
                        if (method == null || method.getType() == null) {
                            continue;
                        }

                        String symbol = method.getType().getName() + "#" + method.getName();
                        cpuSamplesByMethod.merge(symbol, 1L, Long::sum);
                        cpuSamplesWithJavaFrame++;
                        break; // Attribute this sample to its first Java frame.
                    }
                } else if ("jdk.ObjectAllocationSample".equals(eventName)) {
                    allocationSampleCount++;

                    long weight = event.hasField("weight")
                            ? Math.max(0L, event.getLong("weight"))
                            : 0L;
                    estimatedAllocationBytes = saturatedAdd(estimatedAllocationBytes, weight);

                    Object value = event.hasField("objectClass")
                            ? event.getValue("objectClass")
                            : null;
                    if (value instanceof RecordedClass recordedClass) {
                        allocationWeightByClass.merge(
                                recordedClass.getName(),
                                weight,
                                JfrProfileAnalyzer::saturatedAdd
                        );
                    }
                } else if ("jdk.GarbageCollection".equals(eventName)) {
                    garbageCollectionCount++;
                    garbageCollectionNanos = saturatedAdd(
                            garbageCollectionNanos,
                            Math.max(0L, event.getDuration().toNanos())
                    );
                }
            }
        }

        Path reportPath = reportPathFor(recordingFile);
        String report = createReport(
                recordingFile,
                cpuSampleCount,
                cpuSamplesWithJavaFrame,
                cpuSamplesByMethod,
                allocationSampleCount,
                estimatedAllocationBytes,
                allocationWeightByClass,
                garbageCollectionCount,
                garbageCollectionNanos
        );
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        return reportPath;
    }

    private static Path reportPathFor(Path recordingFile) {
        String fileName = recordingFile.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String stem = extension > 0 ? fileName.substring(0, extension) : fileName;
        return recordingFile.resolveSibling(stem + "-analysis.txt");
    }

    private static String createReport(
            Path recordingFile,
            long cpuSampleCount,
            long cpuSamplesWithJavaFrame,
            Map<String, Long> cpuSamplesByMethod,
            long allocationSampleCount,
            long estimatedAllocationBytes,
            Map<String, Long> allocationWeightByClass,
            long garbageCollectionCount,
            long garbageCollectionNanos
    ) {
        StringBuilder report = new StringBuilder(8192);
        report.append("Fiximizer — JFR profile analysis\n");
        report.append("================================\n\n");
        report.append("Recording: ").append(recordingFile.getFileName()).append('\n');
        report.append("CPU execution samples: ").append(cpuSampleCount).append('\n');
        report.append("Samples attributed to Java methods: ").append(cpuSamplesWithJavaFrame).append('\n');
        report.append("Allocation samples: ").append(allocationSampleCount).append('\n');
        report.append("Estimated sampled allocation weight: ")
                .append(formatBytes(estimatedAllocationBytes)).append('\n');
        report.append("Garbage collections: ").append(garbageCollectionCount)
                .append(" / total recorded GC duration ")
                .append(String.format(Locale.ROOT, "%.2f s", garbageCollectionNanos / 1_000_000_000.0))
                .append("\n\n");

        report.append("CPU hotspots — first Java frame in each execution sample\n");
        report.append("Priority is an investigation hint, based on share of Java-attributed samples.\n");
        if (cpuSamplesWithJavaFrame == 0L) {
            report.append("No Java execution samples were recorded. Try a longer profile while reproducing the slowdown.\n");
        } else {
            List<Map.Entry<String, Long>> hotspots = sortedEntries(cpuSamplesByMethod);
            int limit = Math.min(MAX_CPU_HOTSPOTS, hotspots.size());
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, Long> entry = hotspots.get(i);
                double share = entry.getValue() * 100.0 / cpuSamplesWithJavaFrame;
                report.append(String.format(
                        Locale.ROOT,
                        "%2d. [%-6s] %5.1f%%  (%d samples)  %s%n",
                        i + 1,
                        priorityFor(share),
                        share,
                        entry.getValue(),
                        entry.getKey()
                ));
            }
        }

        report.append("\nAllocation hotspots — sampled weight by allocated class\n");
        report.append("Allocation weight is statistical and should be treated as an estimate.\n");
        if (allocationWeightByClass.isEmpty()) {
            report.append("No allocation-class samples were recorded.\n");
        } else {
            List<Map.Entry<String, Long>> allocations = sortedEntries(allocationWeightByClass);
            int limit = Math.min(MAX_ALLOCATION_HOTSPOTS, allocations.size());
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, Long> entry = allocations.get(i);
                double share = estimatedAllocationBytes <= 0L
                        ? 0.0
                        : entry.getValue() * 100.0 / estimatedAllocationBytes;
                report.append(String.format(
                        Locale.ROOT,
                        "%2d. %5.1f%%  %s  %s%n",
                        i + 1,
                        share,
                        formatBytes(entry.getValue()),
                        entry.getKey()
                ));
            }
        }

        report.append("\nPriority bands:\n");
        report.append("HIGH   >= 20% of Java execution samples — inspect first\n");
        report.append("MEDIUM >= 5%  of Java execution samples — inspect next\n");
        report.append("LOW    < 5%   of Java execution samples — lower initial priority\n");
        report.append("\nLimitations:\n");
        report.append("- JFR sampling is statistical; sample share is not exact method wall time.\n");
        report.append("- Class/package names do not always map one-to-one to Minecraft mod IDs.\n");
        report.append("- Use these rankings to choose profiling/integration targets, not to change OS thread priorities.\n");
        report.append("- Compare profiles from the same reproducible scene before and after an optimization.\n");
        return report.toString();
    }

    private static List<Map.Entry<String, Long>> sortedEntries(Map<String, Long> values) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()));
        return entries;
    }

    private static String priorityFor(double share) {
        if (share >= 20.0) {
            return "HIGH";
        }
        if (share >= 5.0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024.0) {
            return String.format(Locale.ROOT, "%.1f KiB", kib);
        }
        double mib = kib / 1024.0;
        if (mib < 1024.0) {
            return String.format(Locale.ROOT, "%.1f MiB", mib);
        }
        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}

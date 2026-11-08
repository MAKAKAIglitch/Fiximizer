package com.makakaiglitch.fiximizer.profiler;

import com.makakaiglitch.fiximizer.diagnostics.ModAttribution;
import com.makakaiglitch.fiximizer.diagnostics.ModCorrelationScorer;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Correlates a rolling JFR recording with the thread stack snapshot captured
 * for one incident. Matching uses Java thread IDs when available, not names
 * alone. All results are heuristic investigation signals.
 */
public final class JfrIncidentCorrelator {
    private static final int MAX_CANDIDATES = 12;
    private static final int MAX_METHODS_PER_MOD = 4;

    private JfrIncidentCorrelator() {
    }

    public static Path analyzeAndWrite(
            Path recordingFile,
            long triggerThreadId,
            String triggerThreadName,
            StackTraceElement[] triggerStack
    ) throws IOException {
        Map<String, MutableCandidate> candidates = new LinkedHashMap<>();

        long totalExecutionSamples = 0L;
        long javaExecutionSamples = 0L;
        long triggerThreadExecutionSamples = 0L;
        long allocationSampleCount = 0L;
        long totalAllocationWeight = 0L;

        try (RecordingFile file = new RecordingFile(recordingFile)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String eventName = event.getEventType().getName();

                if ("jdk.ExecutionSample".equals(eventName)) {
                    totalExecutionSamples++;
                    RecordedThread sampledThread = event.getThread("sampledThread");
                    boolean triggerThread = matchesTriggerThread(
                            sampledThread, triggerThreadId, triggerThreadName);
                    if (triggerThread) {
                        triggerThreadExecutionSamples++;
                    }

                    RecordedStackTrace stackTrace = event.getStackTrace();
                    RecordedFrame firstJavaFrame = firstJavaFrame(stackTrace);
                    if (firstJavaFrame == null) {
                        continue;
                    }

                    RecordedMethod method = firstJavaFrame.getMethod();
                    String className = method.getType().getName();
                    ModAttribution.Ownership owner = ModAttribution.resolveClassOwner(className);
                    if (owner == null) {
                        continue;
                    }

                    javaExecutionSamples++;
                    MutableCandidate candidate = candidate(candidates, owner);
                    recordOwnershipQuality(candidate, owner);
                    candidate.allThreadCpuSamples++;
                    if (triggerThread) {
                        candidate.triggerThreadCpuSamples++;
                    }

                    String methodName = className + "#" + method.getName();
                    candidate.methods.merge(methodName, 1L, Long::sum);
                } else if ("jdk.ObjectAllocationSample".equals(eventName)) {
                    allocationSampleCount++;
                    long weight = event.hasField("weight")
                            ? Math.max(0L, event.getLong("weight"))
                            : 0L;
                    totalAllocationWeight = saturatedAdd(totalAllocationWeight, weight);

                    Object value = event.hasField("objectClass")
                            ? event.getValue("objectClass")
                            : null;
                    if (value instanceof RecordedClass recordedClass) {
                        ModAttribution.Ownership owner =
                                ModAttribution.resolveClassOwner(recordedClass.getName());
                        if (owner != null) {
                            MutableCandidate candidate = candidate(candidates, owner);
                            recordOwnershipQuality(candidate, owner);
                            candidate.sampledAllocationBytes =
                                    saturatedAdd(candidate.sampledAllocationBytes, weight);
                            candidate.allocationSamples++;
                        }
                    }
                }
            }
        }

        collectTriggerStack(candidates, triggerStack);

        List<MutableCandidate> ranked = new ArrayList<>(candidates.values());
        for (MutableCandidate candidate : ranked) {
            candidate.score = ModCorrelationScorer.score(
                    candidate.triggerTopFrames,
                    candidate.triggerFrames,
                    candidate.triggerThreadCpuSamples,
                    candidate.allThreadCpuSamples,
                    javaExecutionSamples,
                    candidate.sampledAllocationBytes,
                    totalAllocationWeight
            );
            candidate.confidence = ModCorrelationScorer.confidence(
                    candidate.triggerTopFrames,
                    candidate.triggerFrames,
                    candidate.triggerThreadCpuSamples,
                    candidate.allThreadCpuSamples,
                    candidate.sampledAllocationBytes
            );
        }
        ranked.sort(Comparator.comparingDouble(
                (MutableCandidate candidate) -> candidate.score).reversed()
                .thenComparing(candidate -> candidate.owner.modId()));

        Path reportPath = reportPathFor(recordingFile);
        Files.writeString(
                reportPath,
                createReport(
                        recordingFile,
                        triggerThreadId,
                        triggerThreadName,
                        totalExecutionSamples,
                        javaExecutionSamples,
                        triggerThreadExecutionSamples,
                        allocationSampleCount,
                        totalAllocationWeight,
                        ranked
                ),
                StandardCharsets.UTF_8
        );
        return reportPath;
    }

    private static void collectTriggerStack(
            Map<String, MutableCandidate> candidates,
            StackTraceElement[] stack
    ) {
        if (stack == null) {
            return;
        }
        for (int depth = 0; depth < stack.length; depth++) {
            ModAttribution.Ownership owner =
                    ModAttribution.resolveClassOwner(stack[depth].getClassName());
            if (owner == null) {
                continue;
            }
            MutableCandidate candidate = candidate(candidates, owner);
            recordOwnershipQuality(candidate, owner);
            candidate.triggerFrames++;
            if (depth < 5) {
                candidate.triggerTopFrames++;
            }
        }
    }

    private static boolean matchesTriggerThread(
            RecordedThread recorded,
            long triggerThreadId,
            String triggerThreadName
    ) {
        if (recorded == null) {
            return false;
        }
        if (triggerThreadId > 0L) {
            long recordedJavaId = recorded.getJavaThreadId();
            if (recordedJavaId > 0L) {
                return recordedJavaId == triggerThreadId;
            }
        }
        return triggerThreadName != null
                && !triggerThreadName.isBlank()
                && triggerThreadName.equals(recorded.getJavaName());
    }

    private static RecordedFrame firstJavaFrame(RecordedStackTrace stackTrace) {
        if (stackTrace == null) {
            return null;
        }
        for (RecordedFrame frame : stackTrace.getFrames()) {
            if (frame.isJavaFrame()
                    && frame.getMethod() != null
                    && frame.getMethod().getType() != null) {
                return frame;
            }
        }
        return null;
    }

    private static MutableCandidate candidate(
            Map<String, MutableCandidate> candidates,
            ModAttribution.Ownership owner
    ) {
        return candidates.computeIfAbsent(
                owner.modId(),
                ignored -> new MutableCandidate(owner)
        );
    }

    private static void recordOwnershipQuality(
            MutableCandidate candidate,
            ModAttribution.Ownership owner
    ) {
        if (owner.exactClassMatch()) {
            candidate.exactClassMatches++;
        } else {
            candidate.packagePrefixMatches++;
        }
    }

    private static String createReport(
            Path recordingFile,
            long triggerThreadId,
            String triggerThreadName,
            long totalExecutionSamples,
            long javaExecutionSamples,
            long triggerThreadExecutionSamples,
            long allocationSampleCount,
            long totalAllocationWeight,
            List<MutableCandidate> candidates
    ) {
        StringBuilder report = new StringBuilder(12_000);
        report.append("Fiximizer — JFR / stall correlation\n");
        report.append("===================================\n\n");
        report.append("Recording: ").append(recordingFile.getFileName()).append('\n');
        report.append("Trigger thread: ").append(clean(triggerThreadName))
                .append(" (Java thread ID=").append(triggerThreadId).append(")\n");
        report.append("Execution samples: ").append(totalExecutionSamples).append('\n');
        report.append("Mod-attributed Java first-frame samples: ").append(javaExecutionSamples).append('\n');
        report.append("Samples on trigger thread: ").append(triggerThreadExecutionSamples).append('\n');
        report.append("Allocation samples: ").append(allocationSampleCount).append('\n');
        report.append("Estimated sampled allocation weight: ")
                .append(formatBytes(totalAllocationWeight)).append("\n\n");

        if (triggerThreadId <= 0L) {
            report.append("No trigger thread ID was supplied; thread-specific JFR matching is unavailable.\n");
        } else {
            report.append("JFR samples are matched by RecordedThread.getJavaThreadId() when available; ")
                    .append("thread-name matching is only a fallback.\n");
        }

        report.append("\nRanked mod candidates\n");
        report.append("---------------------\n");
        if (candidates.isEmpty()) {
            report.append("No JFR or stack classes could be attributed to a loaded mod.\n");
        } else {
            report.append("confidence | score | mod ID | name | version | exact-class matches | package-prefix matches")
                    .append(" | trigger stack frames | top-5 frames | trigger-thread CPU samples | all CPU samples | allocation samples | allocation weight\n");
            int count = Math.min(MAX_CANDIDATES, candidates.size());
            for (int index = 0; index < count; index++) {
                MutableCandidate candidate = candidates.get(index);
                double triggerCpuShare = ModCorrelationScorer.percentage(
                        candidate.triggerThreadCpuSamples, triggerThreadExecutionSamples);
                double allocationShare = ModCorrelationScorer.percentage(
                        candidate.sampledAllocationBytes, totalAllocationWeight);

                report.append(candidate.confidence).append(" | ")
                        .append(String.format(Locale.ROOT, "%.1f", candidate.score)).append(" | ")
                        .append(candidate.owner.modId()).append(" | ")
                        .append(clean(candidate.owner.displayName())).append(" | ")
                        .append(clean(candidate.owner.version())).append(" | ")
                        .append(candidate.exactClassMatches).append(" | ")
                        .append(candidate.packagePrefixMatches).append(" | ")
                        .append(candidate.triggerFrames).append(" | ")
                        .append(candidate.triggerTopFrames).append(" | ")
                        .append(candidate.triggerThreadCpuSamples).append(" (")
                        .append(String.format(Locale.ROOT, "%.1f%%", triggerCpuShare)).append(") | ")
                        .append(candidate.allThreadCpuSamples).append(" | ")
                        .append(candidate.allocationSamples).append(" | ")
                        .append(formatBytes(candidate.sampledAllocationBytes)).append(" (")
                        .append(String.format(Locale.ROOT, "%.1f%%", allocationShare)).append(")\n");

                if (!candidate.methods.isEmpty()) {
                    report.append("  sampled methods: ");
                    List<Map.Entry<String, Long>> methods = candidate.methods.entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .limit(MAX_METHODS_PER_MOD)
                            .toList();
                    for (int methodIndex = 0; methodIndex < methods.size(); methodIndex++) {
                        if (methodIndex > 0) {
                            report.append("; ");
                        }
                        Map.Entry<String, Long> method = methods.get(methodIndex);
                        report.append(method.getKey()).append(" (").append(method.getValue()).append(')');
                    }
                    report.append('\n');
                }
            }
        }

        report.append("\nScoring notes\n");
        report.append("-------------\n");
        report.append("- Trigger stack frames receive the strongest weight, with extra weight for the first five frames.\n");
        report.append("- JFR CPU samples from the exact trigger Java thread are stronger than samples from other threads.\n");
        report.append("- Sampled allocation weight is additional evidence, not a direct leak measurement.\n");
        report.append("- HIGH/MEDIUM/LOW are heuristic evidence bands, not probabilities or proof of causation.\n");
        report.append("- Native methods, shared packages, multi-mod JARs and missing class metadata may remain unattributed.\n");
        report.append("- JFR samples are statistical; they do not represent exact per-method wall time.\n");
        return report.toString();
    }

    private static Path reportPathFor(Path recordingFile) {
        String name = recordingFile.getFileName().toString();
        int extension = name.lastIndexOf('.');
        String stem = extension > 0 ? name.substring(0, extension) : name;
        return recordingFile.resolveSibling(stem + "-correlation.txt");
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
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
        return String.format(Locale.ROOT, "%.1f MiB", mib);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static final class MutableCandidate {
        private final ModAttribution.Ownership owner;
        private final Map<String, Long> methods = new TreeMap<>();
        private int triggerFrames;
        private int triggerTopFrames;
        private long triggerThreadCpuSamples;
        private long allThreadCpuSamples;
        private long sampledAllocationBytes;
        private long allocationSamples;
        private int exactClassMatches;
        private int packagePrefixMatches;
        private double score;
        private String confidence = "LOW";

        private MutableCandidate(ModAttribution.Ownership owner) {
            this.owner = owner;
        }
    }
}

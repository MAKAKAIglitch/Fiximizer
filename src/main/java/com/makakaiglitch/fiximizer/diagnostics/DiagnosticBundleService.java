package com.makakaiglitch.fiximizer.diagnostics;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.memory.MemoryOptimizer;
import com.makakaiglitch.fiximizer.network.NetworkTelemetryService;
import com.makakaiglitch.fiximizer.network.NetworkTelemetrySnapshot;
import com.makakaiglitch.fiximizer.scheduler.AdaptiveTaskScheduler;
import com.makakaiglitch.fiximizer.scheduler.MemoryPressureController;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfileManager;
import com.makakaiglitch.fiximizer.scheduler.SchedulerSnapshot;
import com.makakaiglitch.fiximizer.scheduler.TaskPriority;
import com.makakaiglitch.fiximizer.startup.StartupProfiler;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Builds a local, bounded and privacy-reviewed diagnostic ZIP.
 *
 * No network APIs are used. Mod inventory and raw JFR recordings are
 * intentionally excluded; users must review the preview before export.
 */
public final class DiagnosticBundleService {
    private static final int MAX_LOG_TAIL_BYTES = 256 * 1024;
    private static final int MAX_LOG_LINES = 120;
    private static final int MAX_LOG_OUTPUT_CHARS = 48 * 1024;
    private static final int MAX_INCIDENT_BYTES = 192 * 1024;
    private static final int MAX_ARCHIVES_RETAINED = 5;
    private static final AtomicLong FILE_SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private DiagnosticBundleService() {
    }

    /** Returns the exact entry names that the confirmation screen will show. */
    public static Preview preview(Path gameDirectory) {
        List<String> entries = new ArrayList<>(List.of(
                "manifest.json",
                "system.json",
                "performance.json",
                "network.json",
                "startup.txt"
        ));

        Path latestLog = gameDirectory.resolve("logs").resolve("latest.log");
        if (Files.isRegularFile(latestLog, LinkOption.NOFOLLOW_LINKS)) {
            entries.add("errors.log (filtered from latest.log)");
        }
        if (incidentReportPath(gameDirectory).isPresent()) {
            entries.add("incident.txt (latest stall report)");
        }
        return new Preview(entries);
    }

    /**
     * Export on Fiximizer's bounded background scheduler, never on a game,
     * render, server-tick or Netty event-loop thread.
     */
    public static CompletableFuture<ExportResult> exportAsync(Path gameDirectory, String playerName) {
        return AdaptiveTaskScheduler.submit(
                "Diagnostic bundle export",
                TaskPriority.BACKGROUND,
                () -> exportNow(gameDirectory, playerName)
        );
    }

    private static ExportResult exportNow(Path gameDirectory, String playerName) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", new byte[0]);

        entries.put("system.json", utf8(systemJson()));
        entries.put("performance.json", utf8(performanceJson()));
        entries.put("network.json", utf8(networkJson()));

        DiagnosticTextRedactor.RedactionResult startup =
                DiagnosticTextRedactor.redact(StartupProfiler.createReport(), playerName);
        entries.put("startup.txt", utf8(limitText(startup.text(), MAX_INCIDENT_BYTES)));

        int redactions = startup.redactedOccurrences();
        int omittedChatLines = startup.omittedChatLines();

        DiagnosticTextRedactor.RedactionResult errors = latestErrors(gameDirectory, playerName);
        if (errors != null) {
            entries.put("errors.log", utf8(limitText(errors.text(), MAX_LOG_OUTPUT_CHARS)));
            redactions += errors.redactedOccurrences();
            omittedChatLines += errors.omittedChatLines();
        }

        DiagnosticTextRedactor.RedactionResult incident = incidentReport(gameDirectory)
                .map(report -> DiagnosticTextRedactor.redact(report, playerName))
                .orElse(null);
        if (incident != null) {
            entries.put("incident.txt", utf8(limitText(incident.text(), MAX_INCIDENT_BYTES)));
            redactions += incident.redactedOccurrences();
            omittedChatLines += incident.omittedChatLines();
        }

        entries.put("manifest.json", utf8(manifestJson(redactions, omittedChatLines, entries.keySet())));

        Path outputDirectory = gameDirectory.resolve("logs")
                .resolve("fiximizer").resolve("diagnostic-bundles");
        Files.createDirectories(outputDirectory);

        String fileName = "fiximizer-diagnostics-" + FILE_TIME.format(Instant.now())
                + "-" + FILE_SEQUENCE.incrementAndGet() + ".zip";
        Path output = outputDirectory.resolve(fileName);
        Path temporary = Files.createTempFile(outputDirectory, ".diagnostic-", ".tmp");
        try {
            DiagnosticBundleArchiveWriter.write(temporary, entries);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, output);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }

        pruneOldArchives(outputDirectory);
        Fiximizer.LOGGER.info("Created local diagnostic bundle {} ({} entries, {} redactions).",
                fileName, entries.size(), redactions);
        return new ExportResult(fileName, entries.size(), redactions);
    }

    private static String systemJson() {
        Runtime runtime = Runtime.getRuntime();
        StringBuilder json = new StringBuilder(512);
        json.append("{\n")
                .append("  \"javaVersion\": ").append(jsonString(property("java.version"))).append(",\n")
                .append("  \"vmName\": ").append(jsonString(property("java.vm.name"))).append(",\n")
                .append("  \"osName\": ").append(jsonString(property("os.name"))).append(",\n")
                .append("  \"osArchitecture\": ").append(jsonString(property("os.arch"))).append(",\n")
                .append("  \"availableProcessors\": ").append(runtime.availableProcessors()).append(",\n")
                .append("  \"maxHeapBytes\": ").append(runtime.maxMemory()).append(",\n")
                .append("  \"garbageCollectors\": ").append(stringArray(
                        ManagementFactory.getGarbageCollectorMXBeans().stream()
                                .map(bean -> bean.getName()).toList()))
                .append("\n}\n");
        return json.toString();
    }

    private static String performanceJson() {
        DiagnosticsSnapshot diagnostic = DiagnosticsService.latest();
        SchedulerSnapshot scheduler = AdaptiveTaskScheduler.snapshot();
        var taskTelemetry = scheduler.telemetry();
        var memory = MemoryOptimizer.stringSnapshot();

        return "{\n"
                + "  \"sampleAvailable\": " + diagnostic.hasSample() + ",\n"
                + "  \"sampledAtEpochMillis\": " + diagnostic.sampledAtMillis() + ",\n"
                + "  \"heapUsedBytes\": " + diagnostic.usedHeapBytes() + ",\n"
                + "  \"heapCommittedBytes\": " + diagnostic.committedHeapBytes() + ",\n"
                + "  \"heapMaxBytes\": " + diagnostic.maxHeapBytes() + ",\n"
                + "  \"systemCpuLoad\": " + jsonNumber(diagnostic.systemCpuLoad()) + ",\n"
                + "  \"processCpuLoad\": " + jsonNumber(diagnostic.processCpuLoad()) + ",\n"
                + "  \"activeJvmThreads\": " + diagnostic.activeThreadCount() + ",\n"
                + "  \"profile\": " + jsonString(OptimizationProfileManager.current().id()) + ",\n"
                + "  \"memoryPressureOverride\": " + MemoryPressureController.active() + ",\n"
                + "  \"schedulerActiveTasks\": " + scheduler.activeTasks() + ",\n"
                + "  \"schedulerQueuedTasks\": " + scheduler.queuedTasks() + ",\n"
                + "  \"schedulerPoolSize\": " + scheduler.poolSize() + ",\n"
                + "  \"schedulerTargetWorkers\": " + scheduler.targetWorkers() + ",\n"
                + "  \"schedulerMaximumWorkers\": " + scheduler.maximumWorkers() + ",\n"
                + "  \"schedulerSamples\": " + taskTelemetry.sampleCount() + ",\n"
                + "  \"schedulerQueueWaitP95Ms\": " + jsonNumber(taskTelemetry.queueWaitP95Millis()) + ",\n"
                + "  \"schedulerExecutionP95Ms\": " + jsonNumber(taskTelemetry.executionP95Millis()) + ",\n"
                + "  \"schedulerFailedTasks\": " + taskTelemetry.failedTasks() + ",\n"
                + "  \"schedulerRejectedTasks\": " + taskTelemetry.rejectedTasks() + ",\n"
                + "  \"stringCanonicalizationEnabled\": " + com.makakaiglitch.fiximizer.config.FiximizerConfig.ENABLE_STRING_CANONICALIZATION.get() + ",\n"
                + "  \"stringCanonicalizationOccupiedSlots\": " + memory.occupiedSlots() + ",\n"
                + "  \"stringCanonicalizationCapacity\": " + memory.capacity() + ",\n"
                + "  \"stringCanonicalizationHits\": " + memory.hits() + ",\n"
                + "  \"stringCanonicalizationRegistrations\": " + memory.registrations() + ",\n"
                + "  \"stringCanonicalizationStaleReuses\": " + memory.staleReuses() + ",\n"
                + "  \"stringCanonicalizationCollisionEvictions\": " + memory.collisionEvictions() + ",\n"
                + "  \"stringCanonicalizationContentionFallbacks\": " + memory.contentionFallbacks() + "\n"
                + "}\n";
    }

    private static String networkJson() {
        NetworkTelemetrySnapshot network = NetworkTelemetryService.snapshot();
        return "{\n"
                + "  \"connected\": " + network.connected() + ",\n"
                + "  \"inboundBytes\": " + network.inboundBytes() + ",\n"
                + "  \"outboundBytes\": " + network.outboundBytes() + ",\n"
                + "  \"inboundFrames\": " + network.inboundFrames() + ",\n"
                + "  \"outboundFrames\": " + network.outboundFrames() + ",\n"
                + "  \"serverReportedPingMs\": " + network.serverReportedPingMillis() + ",\n"
                + "  \"pingP50Ms\": " + network.pingP50Millis() + ",\n"
                + "  \"pingP95Ms\": " + network.pingP95Millis() + ",\n"
                + "  \"jitterMs\": " + network.jitterMillis() + "\n"
                + "}\n";
    }

    private static String manifestJson(int redactions, int omittedChatLines, java.util.Set<String> entryNames) {
        String version = ModList.get().getModContainerById(Fiximizer.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
        List<String> omitted = List.of(
                "mod inventory",
                "chat and packet payloads",
                "raw JFR recordings",
                "player UUIDs and names",
                "server addresses and IP addresses",
                "full local filesystem paths"
        );
        return "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"createdAt\": " + jsonString(Instant.now().toString()) + ",\n"
                + "  \"fiximizerVersion\": " + jsonString(version) + ",\n"
                + "  \"javaVersion\": " + jsonString(property("java.version")) + ",\n"
                + "  \"osName\": " + jsonString(property("os.name")) + ",\n"
                + "  \"entries\": " + stringArray(new ArrayList<>(entryNames)) + ",\n"
                + "  \"redactedOccurrences\": " + redactions + ",\n"
                + "  \"omittedChatLines\": " + omittedChatLines + ",\n"
                + "  \"omittedByDefault\": " + stringArray(omitted) + ",\n"
                + "  \"networkUpload\": false\n"
                + "}\n";
    }

    private static DiagnosticTextRedactor.RedactionResult latestErrors(Path gameDirectory, String playerName) {
        Path log = gameDirectory.resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }

        try {
            String tail = readTail(log, MAX_LOG_TAIL_BYTES);
            List<String> selected = new ArrayList<>();
            for (String line : tail.split("\\R")) {
                if (line.matches("(?i).*\\b(?:WARN|WARNING|ERROR|FATAL|Exception)\\b.*")) {
                    selected.add(line.length() > 2000 ? line.substring(0, 2000) : line);
                }
            }
            if (selected.size() > MAX_LOG_LINES) {
                selected = selected.subList(selected.size() - MAX_LOG_LINES, selected.size());
            }

            String raw = selected.isEmpty()
                    ? "No warning or error lines found in the bounded tail of latest.log.\n"
                    : String.join("\n", selected);
            return DiagnosticTextRedactor.redact(raw, playerName);
        } catch (IOException | RuntimeException exception) {
            Fiximizer.LOGGER.debug("Could not read the bounded log tail for a diagnostic bundle ({})",
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private static String readTail(Path path, int maximumBytes) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, maximumBytes);
            channel.position(Math.max(0L, size - length));
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Read only the bounded tail of latest.log.
            }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        }
    }

    private static java.util.Optional<Path> incidentReportPath(Path gameDirectory) {
        String reportName = StallWatchdog.lastReportFile();
        if (reportName == null || reportName.isBlank()) {
            return java.util.Optional.empty();
        }

        try {
            Path name = Path.of(reportName);
            if (name.getNameCount() != 1 || !name.getFileName().toString().equals(reportName)
                    || !reportName.startsWith("stall-") || !reportName.endsWith(".txt")) {
                return java.util.Optional.empty();
            }

            Path directory = gameDirectory.resolve("logs").resolve("fiximizer").resolve("stalls");
            Path report = directory.resolve(reportName).normalize();
            if (!report.getParent().equals(directory.normalize())
                    || !Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) {
                return java.util.Optional.empty();
            }

            long size = Files.size(report);
            if (size <= 0L || size > MAX_INCIDENT_BYTES) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(report);
        } catch (IOException | RuntimeException exception) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<String> incidentReport(Path gameDirectory) {
        return incidentReportPath(gameDirectory).flatMap(report -> {
            try {
                return java.util.Optional.of(Files.readString(report, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException exception) {
                return java.util.Optional.empty();
            }
        });
    }

    private static void pruneOldArchives(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> archives = files
                    .filter(path -> path.getFileName().toString().startsWith("fiximizer-diagnostics-"))
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingLong(DiagnosticBundleService::modifiedAt).reversed())
                    .toList();
            for (int index = MAX_ARCHIVES_RETAINED; index < archives.size(); index++) {
                try {
                    Files.deleteIfExists(archives.get(index));
                } catch (IOException ignored) {
                    // Retention is best-effort; export success must not depend on pruning.
                }
            }
        } catch (IOException ignored) {
            // A failed retention scan does not invalidate the newly written archive.
        }
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static String property(String key) {
        try {
            return System.getProperty(key, "unknown");
        } catch (SecurityException exception) {
            return "unavailable";
        }
    }

    private static String stringArray(List<String> values) {
        return "[" + values.stream().map(DiagnosticBundleService::jsonString)
                .collect(java.util.stream.Collectors.joining(", ")) + "]";
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String jsonNumber(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "null";
    }

    private static String limitText(String value, int maximumBytes) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= maximumBytes) {
            return value;
        }

        String marker = "\n[truncated by Fiximizer export limit]\n";
        int end = Math.max(0, maximumBytes - marker.getBytes(StandardCharsets.UTF_8).length);
        end = Math.min(end, encoded.length);
        // UTF-8 continuation bytes have the bit pattern 10xxxxxx. Move the
        // boundary back to the start of the current code point before decoding.
        while (end > 0 && end < encoded.length && (encoded[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(encoded, 0, end, StandardCharsets.UTF_8) + marker;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public record Preview(List<String> entries) {
        public Preview {
            entries = List.copyOf(entries);
        }
    }

    public record ExportResult(String fileName, int entryCount, int redactedOccurrences) {
    }
}

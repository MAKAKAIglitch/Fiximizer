package com.makakaiglitch.fiximizer.diagnostics;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.compat.ModIntegrationManager;
import com.makakaiglitch.fiximizer.gpu.GpuBenchmarkService;
import com.makakaiglitch.fiximizer.gpu.GpuSpillService;
import com.makakaiglitch.fiximizer.gpu.GpuTelemetryService;
import com.makakaiglitch.fiximizer.gpu.OpenClService;
import com.makakaiglitch.fiximizer.memory.MemoryOptimizer;
import com.makakaiglitch.fiximizer.network.NetworkBackendSelector;
import com.makakaiglitch.fiximizer.network.NetworkTelemetryService;
import com.makakaiglitch.fiximizer.network.NetworkTelemetrySnapshot;
import com.makakaiglitch.fiximizer.profiler.FlightRecorderService;
import com.makakaiglitch.fiximizer.scheduler.AdaptiveTaskScheduler;
import com.makakaiglitch.fiximizer.scheduler.MemoryPressureController;
import com.makakaiglitch.fiximizer.scheduler.SchedulerSnapshot;
import com.makakaiglitch.fiximizer.startup.StartupOptimizer;
import com.makakaiglitch.fiximizer.util.TextSanitizer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cheap, passive health inspection for Fiximizer-owned runtime services.
 *
 * This class reads already-published state only. It does not initialize
 * hardware APIs, open network connections, run benchmarks, or perform I/O.
 */
public final class RuntimeHealthService {
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final RuntimeHealthStatusTracker TRACKER = new RuntimeHealthStatusTracker();

    private static volatile Snapshot cachedSnapshot;
    private static volatile long cachedAtNanos;

    private RuntimeHealthService() {
    }

    public enum State {
        HEALTHY,
        DISABLED,
        DEGRADED,
        UNSUPPORTED,
        FAILED,
        NOT_STARTED,
        IDLE
    }

    public record SubsystemStatus(
            String id,
            State state,
            String reason,
            String fallback,
            boolean userActionRequired,
            long lastTransitionEpochMillis
    ) {
    }

    public record Snapshot(long capturedAtEpochMillis, List<SubsystemStatus> subsystems) {
        public Snapshot {
            subsystems = List.copyOf(subsystems);
        }

        public int count(State state) {
            return (int) subsystems.stream().filter(status -> status.state() == state).count();
        }

        public int attentionCount() {
            return count(State.DEGRADED) + count(State.UNSUPPORTED) + count(State.FAILED);
        }

        public int waitingCount() {
            return count(State.NOT_STARTED) + count(State.IDLE);
        }

        public int actionRequiredCount() {
            return (int) subsystems.stream().filter(SubsystemStatus::userActionRequired).count();
        }

        /** Human-readable, privacy-conscious report intended for clipboard export. */
        public String createReport() {
            StringBuilder report = new StringBuilder(2048);
            report.append("Fiximizer Runtime Health Check\n");
            report.append("==============================\n");
            report.append("Captured: ").append(Instant.ofEpochMilli(capturedAtEpochMillis)).append('\n');
            report.append("Summary: ").append(count(State.HEALTHY)).append(" healthy, ")
                    .append(attentionCount()).append(" need attention, ")
                    .append(count(State.DISABLED)).append(" disabled, ")
                    .append(waitingCount()).append(" idle or pending.\n");
            report.append("This is a passive status snapshot; it does not run benchmarks or hardware probes.\n\n");

            for (SubsystemStatus status : subsystems) {
                report.append('[').append(status.state()).append("] ").append(status.id()).append('\n');
                report.append("  ").append(status.reason()).append('\n');
                if (!status.fallback().isBlank()) {
                    report.append("  Fallback: ").append(status.fallback()).append('\n');
                }
                report.append("  Last transition: ")
                        .append(Instant.ofEpochMilli(status.lastTransitionEpochMillis())).append('\n');
                if (status.userActionRequired()) {
                    report.append("  User action recommended.\n");
                }
                report.append('\n');
            }
            return report.toString();
        }
    }

    /**
     * Returns a cached snapshot for at most one second. Refresh invalidates only
     * this snapshot; it never restarts services or repeats hardware detection.
     */
    public static Snapshot snapshot() {
        long now = System.nanoTime();
        Snapshot current = cachedSnapshot;
        if (current != null && now - cachedAtNanos < CACHE_NANOS) {
            return current;
        }

        synchronized (RuntimeHealthService.class) {
            now = System.nanoTime();
            current = cachedSnapshot;
            if (current != null && now - cachedAtNanos < CACHE_NANOS) {
                return current;
            }

            long capturedAt = System.currentTimeMillis();
            List<SubsystemStatus> observations = collectStatuses();
            List<SubsystemStatus> tracked = TRACKER.update(observations, capturedAt);
            current = new Snapshot(capturedAt, tracked);
            cachedSnapshot = current;
            cachedAtNanos = now;
            return current;
        }
    }

    public static Snapshot refresh() {
        synchronized (RuntimeHealthService.class) {
            cachedSnapshot = null;
            cachedAtNanos = 0L;
        }
        return snapshot();
    }

    private static List<SubsystemStatus> collectStatuses() {
        List<SubsystemStatus> statuses = new ArrayList<>(12);
        statuses.add(startupStatus());
        statuses.add(diagnosticsStatus());
        statuses.add(jfrStatus());
        statuses.add(memoryGuardStatus());
        statuses.add(schedulerStatus());
        statuses.add(memoryOptimizerStatus());
        statuses.add(networkTelemetryStatus());
        statuses.add(networkBackendStatus());
        statuses.add(gpuTelemetryStatus());
        statuses.add(openClStatus());
        statuses.add(vramCacheStatus());
        statuses.add(integrationsStatus());
        return statuses;
    }

    private static SubsystemStatus startupStatus() {
        if (!StartupOptimizer.completed()) {
            return status("startup", State.NOT_STARTED,
                    "Waiting for the client to reach 40 stable ticks.",
                    "Minecraft startup continues normally; optional Fiximizer services are deferred.", false);
        }
        if (ModIntegrationManager.isInitialized() && !ModIntegrationManager.lastRefreshSucceeded()) {
            return status("startup", State.FAILED,
                    "The optional-mod compatibility scan did not complete successfully.",
                    "Minecraft remains available; optional integration gates stay closed.", true);
        }
        if (StartupOptimizer.compatibilityScanSucceeded()) {
            return status("startup", State.HEALTHY,
                    "Deferred startup completed and the compatibility scan succeeded.", "", false);
        }
        return status("startup", State.DEGRADED,
                "The client reached the stable-startup milestone, but the compatibility scan has no success result.",
                "Core gameplay remains available; optional integrations stay inactive until a successful scan.", false);
    }

    private static SubsystemStatus diagnosticsStatus() {
        if (!FiximizerConfig.ENABLE_DIAGNOSTICS.get()) {
            return status("diagnostics", State.DISABLED,
                    "Periodic diagnostic sampling is disabled in configuration.", "", false);
        }
        if (!DiagnosticsService.latest().hasSample()) {
            return status("diagnostics", State.NOT_STARTED,
                    "The first diagnostic sample has not been published yet.",
                    "No diagnostic data is assumed to be zero or valid until a sample exists.", false);
        }
        return status("diagnostics", State.HEALTHY,
                "Diagnostic sampling is active and a snapshot is available.", "", false);
    }

    private static SubsystemStatus jfrStatus() {
        if (!FiximizerConfig.ENABLE_JFR_PROFILING.get()) {
            return status("jfr", State.DISABLED,
                    "Java Flight Recorder profiling is disabled in configuration.", "", false);
        }

        FlightRecorderService.State recordingState = FlightRecorderService.state();
        String rolling = FlightRecorderService.rollingStatus();
        if (recordingState == FlightRecorderService.State.FAILED) {
            if (FlightRecorderService.rollingActive()) {
                return status("jfr", State.DEGRADED,
                        "Manual JFR recording failed: " + FlightRecorderService.message()
                                + "; the rolling stall buffer is still active.",
                        "Rolling JFR snapshots and text-based watchdog reports remain available.", true);
            }
            return status("jfr", State.FAILED,
                    FlightRecorderService.message(),
                    "Text-based watchdog reports and ordinary diagnostics remain available.", true);
        }
        if (FlightRecorderService.rollingActive()
                || recordingState == FlightRecorderService.State.RECORDING
                || recordingState == FlightRecorderService.State.SAVING) {
            return status("jfr", State.HEALTHY,
                    "JFR is active; user recording state is " + recordingState + ".",
                    "", false);
        }
        if (!FiximizerConfig.ENABLE_STALL_WATCHDOG.get()
                || !FiximizerConfig.ENABLE_STALL_JFR.get()) {
            return status("jfr", State.IDLE,
                    "The rolling stall buffer is disabled; manual JFR profiling remains available.",
                    "Enable the watchdog and rolling JFR buffer to capture recent events around stalls.", false);
        }
        String lower = rolling.toLowerCase(Locale.ROOT);
        if (lower.contains("unavailable") || lower.contains("failed") || lower.contains("error")) {
            return status("jfr", State.DEGRADED,
                    rolling,
                    "Text-based watchdog reports and ordinary diagnostics remain available.", false);
        }
        if (lower.contains("not started")) {
            return status("jfr", State.NOT_STARTED,
                    "The rolling JFR buffer has not started yet.",
                    "The watchdog starts the bounded buffer on its background worker.", false);
        }
        return status("jfr", State.IDLE, rolling,
                "Manual JFR profiling can still be started from the dashboard.", false);
    }

    private static SubsystemStatus memoryGuardStatus() {
        if (!FiximizerConfig.ENABLE_MEMORY_GUARD.get()) {
            return status("memory_guard", State.DISABLED,
                    "Automatic memory-pressure monitoring is disabled.", "", false);
        }
        return status("memory_guard", State.HEALTHY,
                MemoryPressureController.active()
                        ? "The memory-pressure guard is active and Memory Saver is overriding the selected profile."
                        : "The memory-pressure guard is enabled and waiting for sustained pressure.",
                "The user's selected optimization profile is preserved and restored after pressure recovers.", false);
    }

    private static SubsystemStatus schedulerStatus() {
        SchedulerSnapshot scheduler = AdaptiveTaskScheduler.snapshot();
        var telemetry = scheduler.telemetry();
        boolean problems = telemetry.failedTasks() > 0L || telemetry.rejectedTasks() > 0L;
        String reason = "Workers " + scheduler.poolSize() + "/" + scheduler.maximumWorkers()
                + ", active tasks " + scheduler.activeTasks()
                + ", queued tasks " + scheduler.queuedTasks()
                + ", failures " + telemetry.failedTasks()
                + ", rejected " + telemetry.rejectedTasks() + ".";
        return status("scheduler", problems ? State.DEGRADED : State.HEALTHY,
                reason,
                "Minecraft-owned work stays on its original threads; Fiximizer background queues remain bounded.",
                false);
    }

    private static SubsystemStatus memoryOptimizerStatus() {
        if (!FiximizerConfig.ENABLE_STRING_CANONICALIZATION.get()) {
            return status("memory_optimizer", State.DISABLED,
                    "String canonicalization is disabled; no deduplication table is used.", "", false);
        }
        var memory = MemoryOptimizer.stringSnapshot();
        if (memory.contentionFallbacks() > 0L) {
            return status("memory_optimizer", State.DEGRADED,
                    "String canonicalization has used " + memory.contentionFallbacks()
                            + " contention fallback(s).",
                    "The original strings are returned when the bounded cache cannot be used safely.", false);
        }
        return status("memory_optimizer", State.HEALTHY,
                "Bounded string canonicalization is enabled; " + memory.hits()
                        + " cache hits and " + memory.occupiedSlots() + "/" + memory.capacity()
                        + " occupied slots.",
                "Only explicitly submitted immutable strings are considered; the heap is not scanned.", false);
    }

    private static SubsystemStatus networkTelemetryStatus() {
        if (!FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()) {
            return status("network_telemetry", State.DISABLED,
                    "Passive network telemetry is disabled in configuration.", "", false);
        }
        NetworkTelemetrySnapshot network = NetworkTelemetryService.snapshot();
        if (!network.connected()) {
            return status("network_telemetry", State.IDLE,
                    "Telemetry is enabled and waiting for a multiplayer connection.",
                    "No connection is required for single-player gameplay; no test connection is created.", false);
        }
        return status("network_telemetry", State.HEALTHY,
                "Passive byte and frame counters are active for the current connection.", "", false);
    }

    private static SubsystemStatus networkBackendStatus() {
        if (!FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()) {
            return status("network_backend", State.DISABLED,
                    "The network statistics backend is not used while telemetry is disabled.", "", false);
        }
        if (NetworkBackendSelector.isRunning()) {
            return status("network_backend", State.NOT_STARTED,
                    "Java/Rust backend selection or validation is still running.",
                    "The Java implementation remains the baseline.", false);
        }
        String detail = NetworkBackendSelector.status();
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("unavailable") || lower.contains("failed")
                || lower.contains("error") || lower.contains("fallback")
                || lower.contains("could not start")) {
            return status("network_backend", State.DEGRADED, detail,
                    "The Java statistics implementation remains available.", false);
        }
        if (!NetworkBackendSelector.isInitialized()) {
            return status("network_backend", State.NOT_STARTED,
                    "Network statistics backend selection has not been requested yet.",
                    "The Java implementation remains available as the baseline.", false);
        }
        return status("network_backend", State.HEALTHY,
                NetworkBackendSelector.selected().name() + " backend: " + detail,
                "", false);
    }

    private static SubsystemStatus gpuTelemetryStatus() {
        if (!FiximizerConfig.ENABLE_GPU_TELEMETRY.get()) {
            return status("gpu_telemetry", State.DISABLED,
                    "GPU telemetry is disabled in configuration.", "", false);
        }
        String detail = GpuTelemetryService.status();
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("not started")) {
            return status("gpu_telemetry", State.NOT_STARTED,
                    "GPU discovery has not been requested yet.", "", false);
        }
        if (lower.contains("no graphics adapter")) {
            return status("gpu_telemetry", State.UNSUPPORTED, detail,
                    "CPU-only scheduling and diagnostics remain available.", false);
        }
        if (lower.contains("unavailable") || lower.contains("error")
                || lower.contains("metrics are unavailable")) {
            return status("gpu_telemetry", State.DEGRADED, detail,
                    "The CPU path remains active; unavailable GPU readings are not treated as zero.", false);
        }
        return status("gpu_telemetry", State.HEALTHY, detail, "", false);
    }

    private static SubsystemStatus openClStatus() {
        if (!FiximizerConfig.ENABLE_OPENCL_PROBE.get()
                || !com.makakaiglitch.fiximizer.scheduler.OptimizationProfileManager.current().openClAllowed()) {
            return status("opencl", State.DISABLED,
                    "OpenCL probing is disabled by configuration or the active optimization profile.", "", false);
        }
        if (!OpenClService.checked()) {
            return status("opencl", State.NOT_STARTED,
                    "The optional OpenCL device probe has not completed.", "", false);
        }
        if (OpenClService.devices().isEmpty()) {
            return status("opencl", State.UNSUPPORTED, OpenClService.status(),
                    "CPU compute and Java fallback paths remain available.", false);
        }
        return status("opencl", State.HEALTHY,
                OpenClService.devices().size() + " OpenCL GPU device(s) detected.", "", false);
    }

    private static SubsystemStatus vramCacheStatus() {
        if (!FiximizerConfig.ENABLE_GPU_SPILL.get()) {
            return status("vram_cache", State.DISABLED,
                    "The optional VRAM cache is disabled in configuration.", "", false);
        }
        if (!MemoryPressureController.active()) {
            return status("vram_cache", State.IDLE,
                    "The VRAM cache is dormant until the memory-pressure guard is active.",
                    "Ordinary heap memory remains in use; no data is moved to the GPU.", false);
        }
        if (!OpenClService.checked()) {
            return status("vram_cache", State.NOT_STARTED,
                    "VRAM storage is waiting for the OpenCL capability check.",
                    "The normal heap path remains available.", false);
        }
        if (OpenClService.devices().isEmpty()) {
            return status("vram_cache", State.UNSUPPORTED,
                    "No OpenCL GPU device is available for VRAM storage.",
                    "The normal heap path remains available.", false);
        }
        if (!GpuBenchmarkService.latest().passed()) {
            return status("vram_cache", State.IDLE,
                    "VRAM storage is waiting for a successful explicit GPU correctness benchmark.",
                    "No cache blocks are stored until the test passes; ordinary heap memory remains in use.", false);
        }
        if (!GpuSpillService.available()) {
            return status("vram_cache", State.DEGRADED,
                    "No safe VRAM budget is currently available for disposable cache blocks.",
                    "The normal heap path remains available and no arbitrary game objects are moved.", false);
        }
        return status("vram_cache", State.HEALTHY,
                "The bounded VRAM cache has a safe budget of "
                        + GpuSpillService.currentBudgetBytes() + " bytes.",
                "Only explicitly submitted disposable cache blocks can use this storage.", false);
    }

    private static SubsystemStatus integrationsStatus() {
        if (!FiximizerConfig.DETECT_COMPATIBLE_MODS.get()) {
            return status("mod_integrations", State.DISABLED,
                    "Optional mod detection is disabled in configuration.", "", false);
        }
        if (!ModIntegrationManager.isInitialized()) {
            return status("mod_integrations", State.NOT_STARTED,
                    "Optional mod compatibility detection has not completed yet.", "", false);
        }
        if (!ModIntegrationManager.lastRefreshSucceeded()) {
            return status("mod_integrations", State.FAILED,
                    "Optional mod compatibility detection failed; all integration gates remain closed.",
                    "Minecraft and unrelated Fiximizer subsystems continue without optional adapters.", true);
        }
        return status("mod_integrations", State.HEALTHY,
                ModIntegrationManager.activeCount() + " compatible integration(s) active.",
                "", false);
    }

    private static SubsystemStatus status(
            String id,
            State state,
            String reason,
            String fallback,
            boolean actionRequired
    ) {
        return new SubsystemStatus(
                id,
                state,
                safeText(reason),
                safeText(fallback),
                actionRequired,
                0L
        );
    }

    private static String safeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String clean = DiagnosticTextRedactor.redact(value, null).text();
        clean = TextSanitizer.sanitize(clean);
        if (clean.length() > 180) {
            return clean.substring(0, 177) + "...";
        }
        return clean;
    }

    /** Stable JSON representation shared with the Diagnostic Bundle exporter. */
    public static String toJson(Snapshot snapshot) {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"capturedAtEpochMillis\": ").append(snapshot.capturedAtEpochMillis()).append(",\n")
                .append("  \"summary\": {\n")
                .append("    \"healthy\": ").append(snapshot.count(State.HEALTHY)).append(",\n")
                .append("    \"attention\": ").append(snapshot.attentionCount()).append(",\n")
                .append("    \"disabled\": ").append(snapshot.count(State.DISABLED)).append(",\n")
                .append("    \"idleOrPending\": ").append(snapshot.waitingCount()).append(",\n")
                .append("    \"actionRequired\": ").append(snapshot.actionRequiredCount()).append("\n")
                .append("  },\n")
                .append("  \"subsystems\": [\n");
        for (int index = 0; index < snapshot.subsystems().size(); index++) {
            SubsystemStatus status = snapshot.subsystems().get(index);
            json.append("    {\n")
                    .append("      \"id\": ").append(jsonString(status.id())).append(",\n")
                    .append("      \"state\": ").append(jsonString(status.state().name().toLowerCase(Locale.ROOT))).append(",\n")
                    .append("      \"reason\": ").append(jsonString(status.reason())).append(",\n")
                    .append("      \"fallback\": ").append(jsonString(status.fallback())).append(",\n")
                    .append("      \"userActionRequired\": ").append(status.userActionRequired()).append(",\n")
                    .append("      \"lastTransitionEpochMillis\": ").append(status.lastTransitionEpochMillis()).append("\n")
                    .append("    }");
            if (index + 1 < snapshot.subsystems().size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]\n")
                .append("}\n");
        return json.toString();
    }

    private static String jsonString(String value) {
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

    static List<SubsystemStatus> trackForTests(
            List<SubsystemStatus> observations,
            Map<String, SubsystemStatus> previous,
            long now
    ) {
        return RuntimeHealthStatusTracker.apply(observations, previous, now);
    }

    static void resetForTests() {
        synchronized (RuntimeHealthService.class) {
            cachedSnapshot = null;
            cachedAtNanos = 0L;
            TRACKER.clear();
        }
    }
}

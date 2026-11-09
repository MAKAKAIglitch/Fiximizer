package com.makakaiglitch.fiximizer.client;

import com.makakaiglitch.fiximizer.benchmark.BenchmarkComparison;
import com.makakaiglitch.fiximizer.benchmark.BenchmarkEngine;
import com.makakaiglitch.fiximizer.benchmark.BenchmarkEnvironment;
import com.makakaiglitch.fiximizer.benchmark.BenchmarkRun;
import com.makakaiglitch.fiximizer.benchmark.BenchmarkSampleWindow;
import com.makakaiglitch.fiximizer.benchmark.BenchmarkScenario;
import com.makakaiglitch.fiximizer.compat.CompatibilityDetector;
import com.makakaiglitch.fiximizer.compat.IntegrationStatus;
import com.makakaiglitch.fiximizer.compat.create.CreateContraptionProfiler;
import com.makakaiglitch.fiximizer.compat.sable.RollingPhysicsWindow;
import com.makakaiglitch.fiximizer.compat.sable.SablePhysicsProfiler;
import com.makakaiglitch.fiximizer.compat.ModIntegrationManager;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticBundleService;
import com.makakaiglitch.fiximizer.diagnostics.RuntimeHealthService;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsService;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsSnapshot;
import com.makakaiglitch.fiximizer.diagnostics.RollingDurationWindow;
import com.makakaiglitch.fiximizer.diagnostics.ServerTickProfiler;
import com.makakaiglitch.fiximizer.diagnostics.StallWatchdog;
import com.makakaiglitch.fiximizer.diagnostics.JvmArgumentAdvisor;
import com.makakaiglitch.fiximizer.gpu.GpuBenchmarkResult;
import com.makakaiglitch.fiximizer.gpu.GpuBenchmarkService;
import com.makakaiglitch.fiximizer.gpu.GpuMemoryBudget;
import com.makakaiglitch.fiximizer.gpu.GpuReading;
import com.makakaiglitch.fiximizer.gpu.GpuSpillService;
import com.makakaiglitch.fiximizer.gpu.GpuTelemetryService;
import com.makakaiglitch.fiximizer.gpu.OpenClService;
import com.makakaiglitch.fiximizer.memory.MemoryOptimizer;
import com.makakaiglitch.fiximizer.memory.RuntimeMemoryCapabilities;
import com.makakaiglitch.fiximizer.memory.StringDeduplicator;
import com.makakaiglitch.fiximizer.network.NetworkBackendSelector;
import com.makakaiglitch.fiximizer.network.NetworkCompatibilityDetector;
import com.makakaiglitch.fiximizer.network.NetworkTelemetryService;
import com.makakaiglitch.fiximizer.network.NetworkTelemetrySnapshot;
import com.makakaiglitch.fiximizer.network.NetworkSocketTuning;
import com.makakaiglitch.fiximizer.profiler.FlightRecorderService;
import com.makakaiglitch.fiximizer.scheduler.AdaptiveTaskScheduler;
import com.makakaiglitch.fiximizer.scheduler.MemoryPressureController;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfile;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfileManager;
import com.makakaiglitch.fiximizer.scheduler.SchedulerSnapshot;
import com.makakaiglitch.fiximizer.scheduler.SchedulerTelemetry;
import com.makakaiglitch.fiximizer.startup.StartupOptimizer;
import com.makakaiglitch.fiximizer.startup.StartupProfiler;
import com.makakaiglitch.fiximizer.startup.StartupTimeline.Stage;
import com.makakaiglitch.fiximizer.util.TextSanitizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FiximizerScreen extends Screen {
    private enum Tab {
        OVERVIEW,
        PERFORMANCE,
        INTEGRATIONS,
        PHYSICS,
        JVM,
        GPU,
        FRAMES,
        SERVER,
        MEMORY,
        NETWORK,
        STARTUP,
        BENCHMARK
    }

    private final Screen parent;
    private Tab selectedTab = Tab.OVERVIEW;

    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Button> integrationButtons = new ArrayList<>();
    private StringDeduplicator.Snapshot memoryStats = MemoryOptimizer.stringSnapshot();
    private RuntimeMemoryCapabilities.Snapshot memoryCapabilities;

    private Button refreshButton;
    private Button profileButton;
    private Button memoryGuardButton;
    private Button startProfileButton;
    private Button stopProfileButton;
    private Button copyJvmButton;
    private Button saveJvmButton;
    private Button memoryDedupButton;
    private Button g1StringDedupButton;
    private Button detectModsButton;
    private Button gpuTestButton;
    private Button gpuSpillButton;
    private Button gpuSpillLimitButton;
    private Button frameProfilerButton;
    private Button serverTickProfilerButton;
    private Button copyStartupReportButton;
    private Button dumpDiagnosticsButton;
    private Button healthCopyButton;
    private Button bundleExportButton;
    private boolean bundleExportRunning;
    private Button networkTelemetryButton;
    private Button networkBackendButton;
    private Button networkNativeBackendButton;
    private Button networkTcpNoDelayButton;
    private Button networkBenchmarkButton;
    private Button benchmarkScenarioButton;
    private Button benchmarkRunButton;
    private Button benchmarkBaselineButton;
    private Button benchmarkPathButton;
    private BenchmarkScenario selectedBenchmarkScenario = BenchmarkScenario.MENU_IDLE;
    private boolean integrationButtonsSynchronized;

    private int panelLeft;
    private int panelTop;
    private int contentLeft;
    private int contentWidth;

    public FiximizerScreen(Screen parent) {
        super(Component.translatable("screen.fiximizer.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelLeft = Math.max(12, (this.width - 560) / 2);
        panelTop = Math.max(12, (this.height - 350) / 2);
        contentLeft = panelLeft + 158;
        contentWidth = 382;

        tabButtons.clear();
        integrationButtons.clear();

        int navX = panelLeft + 14;
        int navY = panelTop + 65;
        int navWidth = 128;

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_overview"),
                button -> selectTab(Tab.OVERVIEW)
        ).bounds(navX, navY, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_performance"),
                button -> selectTab(Tab.PERFORMANCE)
        ).bounds(navX, navY + 23, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_integrations"),
                button -> selectTab(Tab.INTEGRATIONS)
        ).bounds(navX, navY + 46, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_physics"),
                button -> selectTab(Tab.PHYSICS)
        ).bounds(navX, navY + 69, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_jvm"),
                button -> selectTab(Tab.JVM)
        ).bounds(navX, navY + 92, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_gpu"),
                button -> selectTab(Tab.GPU)
        ).bounds(navX, navY + 115, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_frames"),
                button -> selectTab(Tab.FRAMES)
        ).bounds(navX, navY + 138, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_server"),
                button -> selectTab(Tab.SERVER)
        ).bounds(navX, navY + 161, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_memory"),
                button -> selectTab(Tab.MEMORY)
        ).bounds(navX, navY + 184, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_network"),
                button -> selectTab(Tab.NETWORK)
        ).bounds(navX, navY + 207, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_startup"),
                button -> selectTab(Tab.STARTUP)
        ).bounds(navX, navY + 230, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_benchmark"),
                button -> selectTab(Tab.BENCHMARK)
        ).bounds(navX, navY + 253, navWidth, 22).build()));

        refreshButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.refresh"),
                button -> {
                    DiagnosticsService.sampleNow();
                    ModIntegrationManager.refresh();
                    updateIntegrationButtons();
                    integrationButtonsSynchronized = ModIntegrationManager.isInitialized();
                }
        ).bounds(contentLeft, panelTop + 272, 82, 20).build());

        startProfileButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.profile_start"),
                button -> FlightRecorderService.start()
        ).bounds(contentLeft + 90, panelTop + 272, 88, 20).build());

        stopProfileButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.profile_stop"),
                button -> FlightRecorderService.stopAndSave(Minecraft.getInstance().gameDirectory.toPath())
        ).bounds(contentLeft + 186, panelTop + 272, 96, 20).build());

        dumpDiagnosticsButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.watchdog_dump"),
                button -> {
                    if (StallWatchdog.requestManualDump()) {
                        dumpDiagnosticsButton.setMessage(Component.translatable("screen.fiximizer.watchdog_queued"));
                    } else {
                        dumpDiagnosticsButton.setMessage(Component.translatable("screen.fiximizer.watchdog_queue_full"));
                    }
                }
        ).bounds(contentLeft + 284, panelTop + 272, 86, 20).build());

        healthCopyButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.health_copy"),
                button -> copyRuntimeHealthReport()
        ).bounds(contentLeft, panelTop + 296, 164, 20).build());

        bundleExportButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.bundle_export"),
                button -> previewDiagnosticBundle()
        ).bounds(contentLeft + 172, panelTop + 296, 210, 20).build());

        benchmarkScenarioButton = addRenderableWidget(Button.builder(
                benchmarkScenarioLabel(),
                button -> {
                    selectedBenchmarkScenario = selectedBenchmarkScenario.next();
                    benchmarkScenarioButton.setMessage(benchmarkScenarioLabel());
                }
        ).bounds(contentLeft, panelTop + 115, 340, 20).build());

        benchmarkRunButton = addRenderableWidget(Button.builder(
                benchmarkRunLabel(),
                button -> toggleBenchmarkRun()
        ).bounds(contentLeft, panelTop + 142, 165, 20).build());

        benchmarkBaselineButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.benchmark.set_baseline"),
                button -> {
                    if (BenchmarkEngine.saveLatestAsBaseline(Minecraft.getInstance().gameDirectory.toPath())) {
                        benchmarkBaselineButton.setMessage(Component.translatable(
                                "screen.fiximizer.benchmark.baseline_saving"));
                    } else {
                        benchmarkBaselineButton.setMessage(Component.translatable(
                                "screen.fiximizer.benchmark.baseline_unavailable"));
                    }
                }
        ).bounds(contentLeft + 175, panelTop + 142, 165, 20).build());

        benchmarkPathButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.benchmark.copy_path"),
                button -> copyBenchmarkRunPath()
        ).bounds(contentLeft, panelTop + 169, 340, 20).build());

        profileButton = addRenderableWidget(Button.builder(
                profileButtonLabel(),
                button -> cycleOptimizationProfile()
        ).bounds(contentLeft, panelTop + 105, 340, 22).build());

        memoryGuardButton = addRenderableWidget(Button.builder(
                memoryGuardLabel(),
                button -> toggleMemoryGuard()
        ).bounds(contentLeft, panelTop + 138, 340, 22).build());

        detectModsButton = addRenderableWidget(Button.builder(
                detectModsLabel(),
                button -> {
                    toggleConfig(FiximizerConfig.DETECT_COMPATIBLE_MODS);
                    ModIntegrationManager.refresh();
                    SablePhysicsProfiler.syncIntegrationState();
                    updateIntegrationButtons();
                    integrationButtonsSynchronized = ModIntegrationManager.isInitialized();
                    detectModsButton.setMessage(detectModsLabel());
                }
        ).bounds(contentLeft, panelTop + 105, 340, 22).build());

        int integrationY = panelTop + 143;
        integrationButtons.add(createIntegrationButton(
                "screen.fiximizer.integration_create",
                FiximizerConfig.ENABLE_CREATE_INTEGRATION,
                "create",
                integrationY
        ));
        integrationButtons.add(createIntegrationButton(
                "screen.fiximizer.integration_aeronautics",
                FiximizerConfig.ENABLE_AERONAUTICS_INTEGRATION,
                "aeronautics_bundled",
                integrationY + 27
        ));
        integrationButtons.add(createIntegrationButton(
                "screen.fiximizer.integration_sable",
                FiximizerConfig.ENABLE_SABLE_INTEGRATION,
                "sable",
                integrationY + 54
        ));
        integrationButtons.add(createIntegrationButton(
                "screen.fiximizer.integration_dh",
                FiximizerConfig.ENABLE_DH_INTEGRATION,
                "distanthorizons",
                integrationY + 81
        ));
        integrationButtons.add(createIntegrationButton(
                "screen.fiximizer.integration_voxy",
                FiximizerConfig.ENABLE_VOXY_INTEGRATION,
                "voxy",
                integrationY + 108
        ));

        copyJvmButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.jvm_copy"),
                button -> copyJvmArguments()
        ).bounds(contentLeft, panelTop + 198, 150, 22).build());

        saveJvmButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.jvm_save"),
                button -> saveJvmArguments()
        ).bounds(contentLeft + 160, panelTop + 198, 180, 22).build());

        memoryDedupButton = addRenderableWidget(Button.builder(
                memoryDedupLabel(),
                button -> {
                    FiximizerConfig.ENABLE_STRING_CANONICALIZATION.set(
                            !FiximizerConfig.ENABLE_STRING_CANONICALIZATION.get());
                    FiximizerConfig.SPEC.save();
                    if (!FiximizerConfig.ENABLE_STRING_CANONICALIZATION.get()) {
                        MemoryOptimizer.clear();
                    } else {
                        ModIntegrationManager.refresh();
                    }
                    memoryStats = MemoryOptimizer.stringSnapshot();
                    memoryDedupButton.setMessage(memoryDedupLabel());
                }
        ).bounds(contentLeft, panelTop + 260, 165, 20).build());

        g1StringDedupButton = addRenderableWidget(Button.builder(
                g1StringDedupLabel(),
                button -> {
                    FiximizerConfig.RECOMMEND_G1_STRING_DEDUP.set(
                            !FiximizerConfig.RECOMMEND_G1_STRING_DEDUP.get());
                    FiximizerConfig.SPEC.save();
                    g1StringDedupButton.setMessage(g1StringDedupLabel());
                }
        ).bounds(contentLeft + 175, panelTop + 260, 165, 20).build());

        gpuSpillButton = addRenderableWidget(Button.builder(
                gpuSpillLabel(),
                button -> {
                    FiximizerConfig.ENABLE_GPU_SPILL.set(!FiximizerConfig.ENABLE_GPU_SPILL.get());
                    FiximizerConfig.SPEC.save();
                    gpuSpillButton.setMessage(gpuSpillLabel());
                }
        ).bounds(contentLeft, panelTop + 190, 340, 20).build());

        gpuSpillLimitButton = addRenderableWidget(Button.builder(
                gpuSpillLimitLabel(),
                button -> {
                    int next = FiximizerConfig.MAX_GPU_SPILL_MIB.get() >= 512
                            ? 128
                            : FiximizerConfig.MAX_GPU_SPILL_MIB.get() + 128;
                    FiximizerConfig.MAX_GPU_SPILL_MIB.set(next);
                    FiximizerConfig.SPEC.save();
                    gpuSpillLimitButton.setMessage(gpuSpillLimitLabel());
                }
        ).bounds(contentLeft, panelTop + 218, 340, 20).build());

        frameProfilerButton = addRenderableWidget(Button.builder(
                frameProfilerLabel(),
                button -> {
                    toggleConfig(FiximizerConfig.ENABLE_FRAME_TIME_PROFILING);
                    ClientRuntimeProfiler.synchronizeEnabledState();
                    frameProfilerButton.setMessage(frameProfilerLabel());
                }
        ).bounds(contentLeft, panelTop + 122, 340, 20).build());

        serverTickProfilerButton = addRenderableWidget(Button.builder(
                serverTickProfilerLabel(),
                button -> {
                    toggleConfig(FiximizerConfig.ENABLE_SERVER_TICK_PROFILING);
                    serverTickProfilerButton.setMessage(serverTickProfilerLabel());
                }
        ).bounds(contentLeft, panelTop + 122, 340, 20).build());

        networkTelemetryButton = addRenderableWidget(Button.builder(
                networkTelemetryLabel(),
                button -> {
                    FiximizerConfig.ENABLE_NETWORK_TELEMETRY.set(!FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get());
                    FiximizerConfig.SPEC.save();
                    NetworkTelemetryService.refreshHandler();
                    if (FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()) {
                        NetworkBackendSelector.reselectAsync();
                    }
                    networkTelemetryButton.setMessage(networkTelemetryLabel());
                }
        ).bounds(contentLeft, panelTop + 105, 340, 20).build());

        networkBackendButton = addRenderableWidget(Button.builder(
                networkBackendLabel(),
                button -> {
                    int next = (FiximizerConfig.NETWORK_BACKEND_MODE.get() + 1) % 3;
                    FiximizerConfig.NETWORK_BACKEND_MODE.set(next);
                    FiximizerConfig.SPEC.save();
                    NetworkBackendSelector.reselectAsync();
                    networkBackendButton.setMessage(networkBackendLabel());
                }
        ).bounds(contentLeft, panelTop + 133, 340, 20).build());

        networkTcpNoDelayButton = addRenderableWidget(Button.builder(
                networkTcpNoDelayLabel(),
                button -> {
                    FiximizerConfig.ENABLE_NETWORK_TCP_NODELAY.set(
                            !FiximizerConfig.ENABLE_NETWORK_TCP_NODELAY.get());
                    FiximizerConfig.SPEC.save();
                    NetworkSocketTuning.refresh();
                    networkTcpNoDelayButton.setMessage(networkTcpNoDelayLabel());
                }
        ).bounds(contentLeft, panelTop + 161, 340, 20).build());

        networkNativeBackendButton = addRenderableWidget(Button.builder(
                networkNativeBackendLabel(),
                button -> {
                    FiximizerConfig.ENABLE_NETWORK_NATIVE_BACKEND.set(
                            !FiximizerConfig.ENABLE_NETWORK_NATIVE_BACKEND.get());
                    FiximizerConfig.SPEC.save();
                    NetworkBackendSelector.reselectAsync();
                    networkNativeBackendButton.setMessage(networkNativeBackendLabel());
                }
        ).bounds(contentLeft, panelTop + 189, 340, 20).build());

        networkBenchmarkButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.network_benchmark_button"),
                button -> {
                    FiximizerConfig.NETWORK_BACKEND_MODE.set(0);
                    FiximizerConfig.SPEC.save();
                    NetworkBackendSelector.reselectAsync();
                    networkBackendButton.setMessage(networkBackendLabel());
                }
        ).bounds(contentLeft, panelTop + 217, 340, 20).build());

        copyStartupReportButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.startup_copy_report"),
                button -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(StartupProfiler.createReport());
                    copyStartupReportButton.setMessage(Component.translatable("screen.fiximizer.startup_copied"));
                }
        ).bounds(contentLeft, panelTop + 272, 160, 20).build());

        gpuTestButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.gpu_test_button"),
                button -> {
                    if (!GpuBenchmarkService.runAsync(Minecraft.getInstance().gameDirectory.toPath())) {
                        gpuTestButton.setMessage(Component.translatable("screen.fiximizer.gpu_test_running"));
                    } else {
                        gpuTestButton.setMessage(Component.translatable("screen.fiximizer.gpu_test_started"));
                    }
                }
        ).bounds(contentLeft, panelTop + 272, 340, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                button -> onClose()
        ).bounds(panelLeft + 456, panelTop + 326, 88, 20).build());

        updateVisibility();
    }

    private void copyRuntimeHealthReport() {
        Minecraft minecraft = Minecraft.getInstance();
        RuntimeHealthService.Snapshot snapshot = RuntimeHealthService.refresh();
        StringBuilder report = new StringBuilder(4096);
        report.append(Component.translatable("screen.fiximizer.health.report_title").getString()).append('\n');
        report.append(Component.translatable("screen.fiximizer.health.report_summary",
                snapshot.count(RuntimeHealthService.State.HEALTHY),
                snapshot.attentionCount(),
                snapshot.count(RuntimeHealthService.State.DISABLED),
                snapshot.waitingCount()).getString()).append("\n\n");

        for (RuntimeHealthService.SubsystemStatus status : snapshot.subsystems()) {
            String stateKey = status.state().name().toLowerCase(Locale.ROOT);
            report.append(Component.translatable("screen.fiximizer.health.subsystem." + status.id()).getString())
                    .append(" — ")
                    .append(Component.translatable("screen.fiximizer.health.state." + stateKey).getString())
                    .append('\n');
            report.append("  ").append(status.reason()).append('\n');
            if (!status.fallback().isBlank()) {
                report.append("  ")
                        .append(Component.translatable("screen.fiximizer.health.fallback").getString())
                        .append(": ").append(status.fallback()).append('\n');
            }
            report.append("  ")
                    .append(Component.translatable("screen.fiximizer.health.transition").getString())
                    .append(": ").append(status.lastTransitionEpochMillis()).append('\n');
            if (status.userActionRequired()) {
                report.append("  ")
                        .append(Component.translatable("screen.fiximizer.health.action_required").getString())
                        .append('\n');
            }
            report.append('\n');
        }

        minecraft.keyboardHandler.setClipboard(report.toString());
        healthCopyButton.setMessage(Component.translatable("screen.fiximizer.health_copied"));
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.translatable(
                    "screen.fiximizer.health.copied_message",
                    snapshot.count(RuntimeHealthService.State.HEALTHY),
                    snapshot.attentionCount(),
                    snapshot.subsystems().size()
            ));
        }
    }

    private void previewDiagnosticBundle() {
        Minecraft minecraft = Minecraft.getInstance();
        DiagnosticBundleService.Preview preview =
                DiagnosticBundleService.preview(minecraft.gameDirectory.toPath());
        String includedFiles = String.join("\n", preview.entries());
        Component message = Component.translatable(
                "screen.fiximizer.bundle_preview_message",
                includedFiles
        );

        minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    minecraft.setScreen(this);
                    if (!confirmed || bundleExportRunning) {
                        return;
                    }

                    bundleExportRunning = true;
                    bundleExportButton.active = false;
                    bundleExportButton.setMessage(Component.translatable("screen.fiximizer.bundle_working"));

                    try {
                        DiagnosticBundleService.exportAsync(
                                minecraft.gameDirectory.toPath(),
                                minecraft.getUser().getName()
                        ).whenComplete((result, error) -> minecraft.execute(() -> {
                            bundleExportRunning = false;
                            if (bundleExportButton != null) {
                                bundleExportButton.active = true;
                                if (error == null) {
                                    bundleExportButton.setMessage(Component.translatable(
                                            "screen.fiximizer.bundle_export"
                                    ));
                                } else {
                                    bundleExportButton.setMessage(Component.translatable(
                                            "screen.fiximizer.bundle_failed",
                                            error.getClass().getSimpleName()
                                    ));
                                }
                            }

                            if (minecraft.player != null) {
                                if (error == null) {
                                    minecraft.player.sendSystemMessage(Component.translatable(
                                            "screen.fiximizer.bundle_saved",
                                            result.fileName(),
                                            result.entryCount(),
                                            result.redactedOccurrences()
                                    ));
                                } else {
                                    minecraft.player.sendSystemMessage(Component.translatable(
                                            "screen.fiximizer.bundle_failed",
                                            error.getClass().getSimpleName()
                                    ));
                                }
                            }
                        }));
                    } catch (RuntimeException exception) {
                        bundleExportRunning = false;
                        bundleExportButton.active = true;
                        bundleExportButton.setMessage(Component.translatable(
                                "screen.fiximizer.bundle_failed",
                                exception.getClass().getSimpleName()
                        ));
                    }
                },
                Component.translatable("screen.fiximizer.bundle_preview_title"),
                message
        ));
    }

    private Button createIntegrationButton(
            String translationKey,
            ModConfigSpec.BooleanValue setting,
            String detectedModId,
            int y
    ) {
        return addRenderableWidget(Button.builder(
                integrationLabel(translationKey, setting),
                button -> {
                    toggleConfig(setting);
                    ModIntegrationManager.refresh();
                    SablePhysicsProfiler.syncIntegrationState();
                    updateIntegrationButtons();
                    integrationButtonsSynchronized = ModIntegrationManager.isInitialized();
                    button.setMessage(integrationLabel(translationKey, setting));
                }
        ).bounds(contentLeft + 222, y, 118, 20).build());
    }

    private Component integrationLabel(String translationKey, ModConfigSpec.BooleanValue setting) {
        return Component.translatable(
                "screen.fiximizer.integration_toggle",
                Component.translatable(translationKey),
                Component.translatable(setting.get()
                        ? "screen.fiximizer.enabled"
                        : "screen.fiximizer.disabled")
        );
    }

    private void selectTab(Tab tab) {
        selectedTab = tab;
        if (tab == Tab.MEMORY && memoryCapabilities == null) {
            memoryCapabilities = RuntimeMemoryCapabilities.current();
        }
        updateVisibility();
    }

    private void updateVisibility() {
        for (int i = 0; i < tabButtons.size(); i++) {
            tabButtons.get(i).active = i != selectedTab.ordinal();
        }

        boolean overview = selectedTab == Tab.OVERVIEW;
        boolean performance = selectedTab == Tab.PERFORMANCE;
        boolean integrations = selectedTab == Tab.INTEGRATIONS;
        boolean jvm = selectedTab == Tab.JVM;
        boolean gpu = selectedTab == Tab.GPU;
        boolean frames = selectedTab == Tab.FRAMES;
        boolean server = selectedTab == Tab.SERVER;
        boolean memory = selectedTab == Tab.MEMORY;
        boolean network = selectedTab == Tab.NETWORK;
        boolean startup = selectedTab == Tab.STARTUP;
        boolean benchmark = selectedTab == Tab.BENCHMARK;

        refreshButton.visible = overview;
        dumpDiagnosticsButton.visible = overview;
        dumpDiagnosticsButton.active = StallWatchdog.enabled();
        healthCopyButton.visible = overview;
        bundleExportButton.visible = overview;
        bundleExportButton.active = !bundleExportRunning;
        startProfileButton.visible = overview;
        stopProfileButton.visible = overview;
        profileButton.visible = performance;
        memoryGuardButton.visible = performance;
        detectModsButton.visible = integrations;
        copyJvmButton.visible = jvm;
        saveJvmButton.visible = jvm;
        memoryDedupButton.visible = memory;
        g1StringDedupButton.visible = memory;
        gpuTestButton.visible = gpu;
        frameProfilerButton.visible = frames;
        serverTickProfilerButton.visible = server;
        networkTelemetryButton.visible = network;
        networkBackendButton.visible = network;
        networkNativeBackendButton.visible = network;
        networkTcpNoDelayButton.visible = network;
        networkBenchmarkButton.visible = network;
        networkBenchmarkButton.active = !NetworkBackendSelector.isRunning();
        copyStartupReportButton.visible = startup;
        benchmarkScenarioButton.visible = benchmark;
        benchmarkRunButton.visible = benchmark;
        benchmarkBaselineButton.visible = benchmark;
        benchmarkPathButton.visible = benchmark;
        gpuSpillButton.visible = gpu;
        gpuSpillLimitButton.visible = gpu;
        gpuTestButton.active = !GpuBenchmarkService.running()
                && FiximizerConfig.ENABLE_GPU_BENCHMARK.get();

        BenchmarkEngine.Snapshot benchmarkSnapshot = BenchmarkEngine.snapshot();
        boolean benchmarkActive = benchmarkSnapshot.state() == BenchmarkEngine.State.WARMING_UP
                || benchmarkSnapshot.state() == BenchmarkEngine.State.RECORDING
                || benchmarkSnapshot.state() == BenchmarkEngine.State.SAVING;
        benchmarkScenarioButton.active = !benchmarkActive && !benchmarkSnapshot.baselineSaveRunning();
        benchmarkRunButton.active = benchmarkSnapshot.state() != BenchmarkEngine.State.SAVING
                && !benchmarkSnapshot.baselineSaveRunning();
        benchmarkRunButton.setMessage(benchmarkRunLabel());
        BenchmarkRun latestBenchmark = BenchmarkEngine.latestRun();
        benchmarkBaselineButton.active = latestBenchmark != null && latestBenchmark.valid()
                && !benchmarkActive && !benchmarkSnapshot.baselineSaveRunning();
        benchmarkBaselineButton.setMessage(Component.translatable(benchmarkSnapshot.baselineSaveRunning()
                ? "screen.fiximizer.benchmark.baseline_saving"
                : "screen.fiximizer.benchmark.set_baseline"));
        benchmarkPathButton.active = !benchmarkSnapshot.savedFile().isBlank();

        for (Button button : integrationButtons) {
            button.visible = integrations;
        }

        FlightRecorderService.State recorderState = FlightRecorderService.state();
        startProfileButton.active = recorderState == FlightRecorderService.State.IDLE
                || recorderState == FlightRecorderService.State.FAILED;
        stopProfileButton.active = recorderState == FlightRecorderService.State.RECORDING;

        updateIntegrationButtons();
        integrationButtonsSynchronized = ModIntegrationManager.isInitialized();
    }

    @Override
    public void tick() {
        super.tick();
        if (!integrationButtonsSynchronized && ModIntegrationManager.isInitialized()) {
            updateIntegrationButtons();
            integrationButtonsSynchronized = true;
        }
        if (selectedTab == Tab.MEMORY) {
            memoryStats = MemoryOptimizer.stringSnapshot();
        }
        if (selectedTab == Tab.BENCHMARK) {
            benchmarkRunButton.setMessage(benchmarkRunLabel());
            BenchmarkEngine.Snapshot snapshot = BenchmarkEngine.snapshot();
            benchmarkBaselineButton.active = BenchmarkEngine.latestRun() != null
                    && BenchmarkEngine.latestRun().valid()
                    && snapshot.state() != BenchmarkEngine.State.WARMING_UP
                    && snapshot.state() != BenchmarkEngine.State.RECORDING
                    && snapshot.state() != BenchmarkEngine.State.SAVING
                    && !snapshot.baselineSaveRunning();
            benchmarkBaselineButton.setMessage(Component.translatable(snapshot.baselineSaveRunning()
                    ? "screen.fiximizer.benchmark.baseline_saving"
                    : "screen.fiximizer.benchmark.set_baseline"));
            benchmarkPathButton.active = !snapshot.savedFile().isBlank();
        }
    }

    private void updateIntegrationButtons() {
        List<IntegrationStatus> statuses = ModIntegrationManager.statuses();
        String[] targetIds = {"create", "aeronautics_bundled", "sable", "distanthorizons", "voxy"};

        for (int i = 0; i < integrationButtons.size(); i++) {
            String id = targetIds[i];
            boolean installed = statuses.stream().anyMatch(status ->
                    status.detected() && (status.detectedModId().equals(id)
                            || (id.equals("aeronautics_bundled")
                            && (status.detectedModId().equals("create_aeronautics")
                            || status.detectedModId().equals("aeronautics")))));
            integrationButtons.get(i).active = installed;
        }
    }

    private void toggleMemoryGuard() {
        FiximizerConfig.ENABLE_MEMORY_GUARD.set(!FiximizerConfig.ENABLE_MEMORY_GUARD.get());
        FiximizerConfig.SPEC.save();
        if (!FiximizerConfig.ENABLE_MEMORY_GUARD.get()) {
            MemoryPressureController.reset();
        } else {
            DiagnosticsService.sampleNow();
        }
        memoryGuardButton.setMessage(memoryGuardLabel());
    }

    private void toggleConfig(ModConfigSpec.BooleanValue configValue) {
        configValue.set(!configValue.get());
        FiximizerConfig.SPEC.save();
    }

    private void cycleOptimizationProfile() {
        Path configDirectory = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        if (OptimizationProfileManager.cycle(configDirectory)) {
            profileButton.setMessage(profileButtonLabel());
            DiagnosticsService.sampleNow();
            if (FiximizerConfig.ENABLE_OPENCL_PROBE.get()
                    && OptimizationProfileManager.current().openClAllowed()
                    && !OpenClService.checked()) {
                OpenClService.probeAsync();
            }
        } else {
            profileButton.setMessage(Component.translatable(
                    "screen.fiximizer.profile_save_failed",
                    OptimizationProfileManager.status()
            ));
        }
    }

    private Component memoryDedupLabel() {
        return Component.translatable(
                "screen.fiximizer.memory.string_dedup_toggle",
                Component.translatable(FiximizerConfig.ENABLE_STRING_CANONICALIZATION.get()
                        ? "screen.fiximizer.enabled"
                        : "screen.fiximizer.disabled")
        );
    }

    private Component g1StringDedupLabel() {
        return Component.translatable(
                "screen.fiximizer.memory.g1_string_dedup_toggle",
                Component.translatable(FiximizerConfig.RECOMMEND_G1_STRING_DEDUP.get()
                        ? "screen.fiximizer.enabled"
                        : "screen.fiximizer.disabled"),
                Component.translatable(JvmArgumentAdvisor.supportsG1StringDeduplication()
                        ? "screen.fiximizer.memory.g1_supported"
                        : "screen.fiximizer.memory.g1_unavailable")
        );
    }

    private void copyJvmArguments() {
        JvmArgumentAdvisor.Recommendation recommendation = JvmArgumentAdvisor.recommend();
        Minecraft.getInstance().keyboardHandler.setClipboard(recommendation.arguments());
        copyJvmButton.setMessage(Component.translatable("screen.fiximizer.jvm_copied"));
    }

    private void saveJvmArguments() {
        try {
            Path configDirectory = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
            Path output = JvmArgumentAdvisor.write(configDirectory);
            saveJvmButton.setMessage(Component.translatable(
                    "screen.fiximizer.jvm_saved",
                    output.getFileName().toString()
            ));
        } catch (IOException exception) {
            saveJvmButton.setMessage(Component.translatable("screen.fiximizer.jvm_save_failed"));
        }
    }


    private Component networkTelemetryLabel() {
        return Component.translatable(
                "screen.fiximizer.network_telemetry_toggle",
                Component.translatable(FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()
                        ? "screen.fiximizer.enabled"
                        : "screen.fiximizer.disabled")
        );
    }

    private Component networkNativeBackendLabel() {
        return Component.translatable(
                "screen.fiximizer.network_native_backend_toggle",
                Component.translatable(FiximizerConfig.ENABLE_NETWORK_NATIVE_BACKEND.get()
                        ? "screen.fiximizer.enabled"
                        : "screen.fiximizer.disabled")
        );
    }

    private Component networkTcpNoDelayLabel() {
        return Component.translatable(
                "screen.fiximizer.network_tcp_nodelay_toggle",
                Component.translatable(FiximizerConfig.ENABLE_NETWORK_TCP_NODELAY.get()
                        ? "screen.fiximizer.enabled"
                        : "screen.fiximizer.disabled")
        );
    }

    private Component networkBackendLabel() {
        String mode = switch (FiximizerConfig.NETWORK_BACKEND_MODE.get()) {
            case 1 -> "Java";
            case 2 -> "Rust";
            default -> "Auto";
        };
        return Component.translatable("screen.fiximizer.network_backend_button", mode);
    }

    private Component serverTickProfilerLabel() {
        return Component.translatable(
                "screen.fiximizer.server_profiler_toggle",
                Component.translatable(FiximizerConfig.ENABLE_SERVER_TICK_PROFILING.get()
                        ? "screen.fiximizer.enabled"
                        : "screen.fiximizer.disabled")
        );
    }

    private Component frameProfilerLabel() {
        return Component.translatable(
                "screen.fiximizer.frame_profiler_toggle",
                Component.translatable(FiximizerConfig.ENABLE_FRAME_TIME_PROFILING.get()
                        ? "screen.fiximizer.enabled"
                        : "screen.fiximizer.disabled")
        );
    }

    private Component gpuSpillLabel() {
        return Component.translatable(
                "screen.fiximizer.gpu_spill_toggle",
                Component.translatable(FiximizerConfig.ENABLE_GPU_SPILL.get()
                        ? "screen.fiximizer.enabled"
                        : "screen.fiximizer.disabled")
        );
    }

    private Component gpuSpillLimitLabel() {
        return Component.translatable(
                "screen.fiximizer.gpu_spill_limit",
                FiximizerConfig.MAX_GPU_SPILL_MIB.get()
        );
    }

    private Component profileButtonLabel() {
        return Component.translatable(
                "screen.fiximizer.profile_button",
                Component.translatable(OptimizationProfileManager.selected().translationKey())
        );
    }

    private Component memoryGuardLabel() {
        return Component.translatable(
                "screen.fiximizer.memory_guard_button",
                Component.translatable(FiximizerConfig.ENABLE_MEMORY_GUARD.get()
                        ? "screen.fiximizer.enabled"
                        : "screen.fiximizer.disabled")
        );
    }

    private Component detectModsLabel() {
        return Component.translatable(
                "screen.fiximizer.detect_mods_button",
                Component.translatable(FiximizerConfig.DETECT_COMPATIBLE_MODS.get()
                        ? "screen.fiximizer.enabled"
                        : "screen.fiximizer.disabled")
        );
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xE80B101B);
        graphics.fill(panelLeft, panelTop, panelLeft + 560, panelTop + 350, 0xF0182233);
        graphics.fill(panelLeft, panelTop, panelLeft + 560, panelTop + 3, 0xFF54B8CE);
        graphics.fill(panelLeft + 150, panelTop + 48, panelLeft + 151, panelTop + 340, 0xFF34445C);

        graphics.drawString(this.font, Component.translatable("screen.fiximizer.title"),
                panelLeft + 18, panelTop + 17, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("screen.fiximizer.brand_subtitle"),
                panelLeft + 18, panelTop + 33, 0xFF9FB3C8);

        graphics.drawString(this.font, Component.translatable("screen.fiximizer.sidebar"),
                panelLeft + 18, panelTop + 51, 0xFF7993B2);

        drawCurrentTab(graphics);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawCurrentTab(GuiGraphics graphics) {
        switch (selectedTab) {
            case OVERVIEW -> drawOverview(graphics);
            case PERFORMANCE -> drawPerformance(graphics);
            case INTEGRATIONS -> drawIntegrations(graphics);
            case PHYSICS -> drawPhysics(graphics);
            case JVM -> drawJvm(graphics);
            case GPU -> drawGpu(graphics);
            case FRAMES -> drawFrames(graphics);
            case SERVER -> drawServer(graphics);
            case MEMORY -> drawMemory(graphics);
            case NETWORK -> drawNetwork(graphics);
            case STARTUP -> drawStartup(graphics);
            case BENCHMARK -> drawBenchmark(graphics);
        }
    }

    private void drawOverview(GuiGraphics graphics) {
        DiagnosticsSnapshot snapshot = DiagnosticsService.latest();
        drawSectionTitle(graphics, "screen.fiximizer.overview_title", panelTop + 58);

        // The overview has a fixed action bar below it. Keep every status on a
        // single clipped line so long device names, paths, or driver messages
        // can never collide with the buttons.
        int y = panelTop + 80;
        drawCompactValue(graphics, "screen.fiximizer.heap", y,
                DiagnosticsService.formatBytes(snapshot.usedHeapBytes()),
                DiagnosticsService.formatBytes(snapshot.maxHeapBytes()),
                String.format(Locale.ROOT, "%.1f", snapshot.maxHeapUsagePercent()));
        y += 15;

        drawCompactValue(graphics, "screen.fiximizer.cpu", y,
                DiagnosticsService.formatPercent(snapshot.systemCpuLoad()),
                DiagnosticsService.formatPercent(snapshot.processCpuLoad()));
        y += 15;

        drawCompactValue(graphics, "screen.fiximizer.threads", y,
                snapshot.activeThreadCount(), snapshot.loadedModCount());
        y += 15;

        drawCompactValue(graphics, "screen.fiximizer.profile_effective", y,
                Component.translatable(OptimizationProfileManager.current().translationKey()));
        y += 15;

        if (MemoryPressureController.active()) {
            drawCompactLine(graphics,
                    Component.translatable("screen.fiximizer.memory_override",
                            String.format(Locale.ROOT, "%.1f", MemoryPressureController.heapUsagePercent())),
                    contentLeft, y, contentWidth - 8, 0xFFFFB85C);
        } else {
            drawCompactValue(graphics, "screen.fiximizer.memory_guard_status", y,
                    Component.translatable("screen.fiximizer.normal"));
        }
        y += 15;

        drawCompactValue(graphics, "screen.fiximizer.gpu", y, gpuLabel());
        y += 15;

        drawCompactValue(graphics, "screen.fiximizer.opencl", y, openClLabel());
        y += 15;

        drawCompactValue(graphics, "screen.fiximizer.jfr", y, recorderLabel());
        y += 15;

        drawCompactValue(graphics, "screen.fiximizer.integrations", y,
                ModIntegrationManager.activeCount(), CompatibilityDetector.targetCount());
        y += 15;

        SchedulerSnapshot scheduler = AdaptiveTaskScheduler.snapshot();
        drawCompactValue(graphics, "screen.fiximizer.scheduler", y,
                scheduler.activeTasks(), scheduler.queuedTasks(),
                scheduler.targetWorkers(), scheduler.maximumWorkers());
        y += 15;

        SchedulerTelemetry.Snapshot schedulerTelemetry = scheduler.telemetry();
        drawCompactValue(graphics, "screen.fiximizer.scheduler_latency", y,
                String.format(Locale.ROOT, "%.1f", schedulerTelemetry.queueWaitP95Millis()),
                String.format(Locale.ROOT, "%.1f", schedulerTelemetry.executionP95Millis()),
                schedulerTelemetry.failedTasks(), schedulerTelemetry.rejectedTasks());
        y += 15;

        String lastDump = StallWatchdog.lastReportFile();
        drawCompactValue(graphics, "screen.fiximizer.watchdog_status", y,
                Component.translatable(StallWatchdog.enabled()
                        ? "screen.fiximizer.enabled" : "screen.fiximizer.disabled"),
                FiximizerConfig.STALL_THRESHOLD_MS.get(),
                Component.literal(lastDump.isBlank()
                        ? Component.translatable("screen.fiximizer.watchdog_no_report").getString()
                        : lastDump));
        y += 15;

        RuntimeHealthService.Snapshot health = RuntimeHealthService.snapshot();
        drawCompactValue(graphics, "screen.fiximizer.health_summary", y,
                health.count(RuntimeHealthService.State.HEALTHY),
                health.attentionCount(),
                health.count(RuntimeHealthService.State.DISABLED),
                health.waitingCount());
    }

    private void drawCompactValue(
            GuiGraphics graphics,
            String translationKey,
            int y,
            Object... arguments
    ) {
        drawCompactLine(
                graphics,
                Component.translatable(translationKey, arguments),
                contentLeft,
                y,
                contentWidth - 8,
                0xFFFFFFFF
        );
    }

    private void drawCompactLine(
            GuiGraphics graphics,
            Component component,
            int x,
            int y,
            int maxWidth,
            int color
    ) {
        String text = TextSanitizer.sanitize(component.getString());
        if (text.isEmpty()) {
            text = "—";
        }

        boolean truncated = this.font.width(text) > maxWidth;
        if (truncated) {
            while (!text.isEmpty() && this.font.width(text + "…") > maxWidth) {
                int lastCodePoint = text.codePointBefore(text.length());
                text = text.substring(0, text.length() - Character.charCount(lastCodePoint))
                        .stripTrailing();
            }
            text += "…";
        }

        graphics.drawString(this.font, Component.literal(text), x, y, color);
    }

    private void drawNetwork(GuiGraphics graphics) {
        drawSectionTitle(graphics, "screen.fiximizer.network_title", panelTop + 58);
        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.network_intro"),
                contentLeft,
                panelTop + 80,
                contentWidth - 8,
                0xFFB9C9DC);

        NetworkTelemetrySnapshot snapshot = NetworkTelemetryService.snapshot();
        int y = panelTop + 245;
        drawCompactValue(graphics, "screen.fiximizer.network_connection",
                y, Component.translatable(snapshot.connected()
                        ? "screen.fiximizer.connected" : "screen.fiximizer.disconnected"));
        y += 15;

        if (snapshot.serverReportedPingMillis() >= 0) {
            drawCompactValue(graphics, "screen.fiximizer.network_ping",
                    y,
                    snapshot.serverReportedPingMillis(),
                    snapshot.pingP50Millis(),
                    snapshot.pingP95Millis(),
                    snapshot.jitterMillis());
        } else {
            drawCompactValue(graphics, "screen.fiximizer.network_ping_waiting", y);
        }
        y += 15;

        drawCompactValue(graphics, "screen.fiximizer.network_traffic",
                y,
                DiagnosticsService.formatBytes(snapshot.inboundBytes()),
                DiagnosticsService.formatBytes(snapshot.outboundBytes()),
                snapshot.inboundFrames(),
                snapshot.outboundFrames());
        y += 15;

        drawCompactValue(graphics, "screen.fiximizer.network_backend",
                y,
                NetworkBackendSelector.selected().name(),
                NetworkBackendSelector.status(),
                formatNetworkBenchmarkTime(NetworkBackendSelector.javaMedianNanos()),
                formatNetworkBenchmarkTime(NetworkBackendSelector.rustMedianNanos()));
        y += 15;

        drawCompactValue(graphics, "screen.fiximizer.network_tcp_nodelay",
                y,
                NetworkSocketTuning.status());
        y += 15;

        drawCompactValue(graphics, "screen.fiximizer.network_compatibility",
                y,
                NetworkCompatibilityDetector.snapshot().detectedSummary(),
                NetworkCompatibilityDetector.snapshot().policy());
    }

    private String formatNetworkBenchmarkTime(long nanos) {
        return nanos < 0L ? "—" : String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private void drawStartup(GuiGraphics graphics) {
        drawSectionTitle(graphics, "screen.fiximizer.startup_title", panelTop + 58);
        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.startup_intro"),
                contentLeft,
                panelTop + 80,
                contentWidth - 8,
                0xFFB9C9DC);

        int y = panelTop + 126;
        drawCompactValue(graphics, "screen.fiximizer.startup_jvm_first_title", y,
                formatStartupDuration(StartupProfiler.millisFromJvmStartToFirstTitle()));
        y += 17;
        drawCompactValue(graphics, "screen.fiximizer.startup_jvm_stable", y,
                formatStartupDuration(StartupProfiler.millisFromJvmStartToStableClient()));
        y += 17;
        drawCompactValue(graphics, "screen.fiximizer.startup_fiximizer_init", y,
                formatStartupDuration(StartupProfiler.elapsedMillis(
                        Stage.FIXIMIZER_MOD_CONSTRUCTED,
                        Stage.CLIENT_SETUP_COMPLETED)));
        y += 17;
        drawCompactValue(graphics, "screen.fiximizer.startup_client_setup", y,
                formatStartupDuration(StartupProfiler.elapsedMillis(
                        Stage.CLIENT_SETUP_STARTED,
                        Stage.CLIENT_SETUP_COMPLETED)));
        y += 17;
        drawCompactValue(graphics, "screen.fiximizer.startup_stable_wait", y,
                formatStartupDuration(StartupProfiler.elapsedMillis(
                        Stage.FIRST_TITLE_SCREEN_TICK,
                        Stage.STABLE_CLIENT_READY)));
        y += 17;
        drawCompactValue(graphics, "screen.fiximizer.startup_mod_scan_time", y,
                formatStartupDuration(StartupProfiler.elapsedMillis(
                        Stage.OPTIONAL_MOD_SCAN_STARTED,
                        Stage.OPTIONAL_MOD_SCAN_COMPLETED)));
        y += 17;

        String scanStatus;
        if (!ModIntegrationManager.isInitialized()) {
            scanStatus = Component.translatable(StartupOptimizer.completed()
                    ? "screen.fiximizer.startup_scan_failed"
                    : "screen.fiximizer.startup_scan_pending").getString();
        } else {
            scanStatus = Component.translatable(ModIntegrationManager.lastRefreshSucceeded()
                    ? "screen.fiximizer.startup_scan_done"
                    : "screen.fiximizer.startup_scan_failed").getString();
        }
        drawCompactValue(graphics, "screen.fiximizer.startup_mod_scan", y, scanStatus);
        y += 17;
        drawCompactValue(graphics, "screen.fiximizer.startup_optional_services", y,
                Component.translatable(StartupOptimizer.completed()
                        ? "screen.fiximizer.startup_services_scheduled"
                        : "screen.fiximizer.startup_scan_pending").getString());
    }

    private String formatStartupDuration(long millis) {
        if (millis < 0L) {
            return Component.translatable("screen.fiximizer.startup_pending").getString();
        }
        return String.format(Locale.ROOT, "%.1f ms", (double) millis);
    }

    private void drawBenchmark(GuiGraphics graphics) {
        drawSectionTitle(graphics, "screen.fiximizer.benchmark.title", panelTop + 58);
        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.benchmark.intro"),
                contentLeft,
                panelTop + 80,
                contentWidth - 8,
                0xFFB9C9DC);

        BenchmarkEngine.Snapshot status = BenchmarkEngine.snapshot();
        drawCompactValue(graphics, "screen.fiximizer.benchmark.state", panelTop + 198,
                Component.translatable("screen.fiximizer.benchmark.state."
                        + status.state().name().toLowerCase(Locale.ROOT)));
        if (status.state() == BenchmarkEngine.State.WARMING_UP
                || status.state() == BenchmarkEngine.State.RECORDING) {
            drawCompactValue(graphics, "screen.fiximizer.benchmark.remaining", panelTop + 213,
                    status.remainingSeconds());
            drawCompactValue(graphics, "screen.fiximizer.benchmark.live_samples", panelTop + 228,
                    status.renderSamples(), status.clientTickSamples(), status.serverTickSamples());
        } else {
            String savedName = status.savedFile().isBlank()
                    ? Component.translatable("screen.fiximizer.benchmark.no_saved_result").getString()
                    : Path.of(status.savedFile()).getFileName().toString();
            drawCompactValue(graphics, "screen.fiximizer.benchmark.result_path", panelTop + 213,
                    savedName);
            BenchmarkRun completedRun = BenchmarkEngine.latestRun();
            if (completedRun != null) {
                var before = completedRun.runtimeBefore();
                var after = completedRun.runtimeAfter();
                String gcDelta = before.gcCollectionTimeMillis() < 0L
                        || after.gcCollectionTimeMillis() < 0L
                        ? "n/a"
                        : Long.toString(after.gcCollectionTimeMillis() - before.gcCollectionTimeMillis());
                drawCompactValue(graphics, "screen.fiximizer.benchmark.runtime",
                        panelTop + 228,
                        DiagnosticsService.formatBytes(before.heapUsedBytes()),
                        DiagnosticsService.formatBytes(after.heapUsedBytes()),
                        gcDelta);
            }
        }

        BenchmarkRun run = BenchmarkEngine.latestRun();
        if (run == null) {
            drawWrapped(graphics,
                    Component.translatable("screen.fiximizer.benchmark.no_result"),
                    contentLeft,
                    panelTop + 243,
                    contentWidth - 8,
                    0xFF9FB3C8);
            return;
        }

        var render = run.renderCall();
        var clientTick = run.clientTick();
        var serverTick = run.integratedServerTick();
        drawCompactValue(graphics, "screen.fiximizer.benchmark.render_result", panelTop + 243,
                render.sampleCount(),
                String.format(Locale.ROOT, "%.2f", render.p50Millis()),
                String.format(Locale.ROOT, "%.2f", render.p95Millis()),
                String.format(Locale.ROOT, "%.2f", render.p99Millis()));
        drawCompactValue(graphics, "screen.fiximizer.benchmark.client_result", panelTop + 258,
                clientTick.sampleCount(),
                String.format(Locale.ROOT, "%.2f", clientTick.p50Millis()),
                String.format(Locale.ROOT, "%.2f", clientTick.p95Millis()),
                String.format(Locale.ROOT, "%.2f", clientTick.p99Millis()));
        if (serverTick.hasSamples()) {
            drawCompactValue(graphics, "screen.fiximizer.benchmark.server_result", panelTop + 273,
                    serverTick.sampleCount(),
                    String.format(Locale.ROOT, "%.2f", serverTick.p50Millis()),
                    String.format(Locale.ROOT, "%.2f", serverTick.p95Millis()),
                    String.format(Locale.ROOT, "%.2f", serverTick.p99Millis()));
        }

        BenchmarkComparison comparison = BenchmarkEngine.latestComparison();
        if (comparison != null && comparison.compatible() && comparison.renderCall() != null) {
            BenchmarkComparison.MetricDelta delta = comparison.renderCall();
            drawCompactValue(graphics, "screen.fiximizer.benchmark.comparison", panelTop + 288,
                    delta.p95ChangeMillis() > 0 ? "+" : "",
                    String.format(Locale.ROOT, "%.2f", delta.p95ChangeMillis()),
                    delta.p95PercentAvailable()
                            ? String.format(Locale.ROOT, "%.1f%%", delta.p95ChangePercent())
                            : "n/a");
        } else if (!run.valid()) {
            drawCompactValue(graphics, "screen.fiximizer.benchmark.incomplete", panelTop + 288);
        }
    }

    private void toggleBenchmarkRun() {
        BenchmarkEngine.State state = BenchmarkEngine.snapshot().state();
        if (state == BenchmarkEngine.State.WARMING_UP || state == BenchmarkEngine.State.RECORDING) {
            BenchmarkEngine.cancel();
        } else {
            Minecraft minecraft = Minecraft.getInstance();
            try {
                BenchmarkEnvironment environment = BenchmarkEnvironment.capture(minecraft);
                if (!BenchmarkEngine.start(
                        minecraft.gameDirectory.toPath(),
                        selectedBenchmarkScenario,
                        environment
                ) && minecraft.player != null) {
                    minecraft.player.sendSystemMessage(Component.translatable(
                            "screen.fiximizer.benchmark.start_failed",
                            "engine is busy or the selected scenario is invalid"
                    ));
                }
            } catch (RuntimeException exception) {
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(Component.translatable(
                            "screen.fiximizer.benchmark.start_failed",
                            exception.getClass().getSimpleName()
                    ));
                }
            }
        }
        benchmarkRunButton.setMessage(benchmarkRunLabel());
    }

    private Component benchmarkScenarioLabel() {
        return Component.translatable(
                "screen.fiximizer.benchmark.scenario_select",
                Component.translatable(selectedBenchmarkScenario.translationKey())
        );
    }

    private Component benchmarkRunLabel() {
        BenchmarkEngine.State state = BenchmarkEngine.snapshot().state();
        return Component.translatable(state == BenchmarkEngine.State.WARMING_UP
                || state == BenchmarkEngine.State.RECORDING
                ? "screen.fiximizer.benchmark.cancel"
                : "screen.fiximizer.benchmark.start");
    }

    private void copyBenchmarkRunPath() {
        BenchmarkEngine.Snapshot snapshot = BenchmarkEngine.snapshot();
        if (snapshot.savedFile().isBlank()) {
            return;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(snapshot.savedFile());
        benchmarkPathButton.setMessage(Component.translatable("screen.fiximizer.benchmark.path_copied"));
    }

    private void drawServer(GuiGraphics graphics) {
        drawSectionTitle(graphics, "screen.fiximizer.server_title", panelTop + 58);
        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.server_intro"),
                contentLeft,
                panelTop + 80,
                contentWidth - 8,
                0xFFB9C9DC);

        if (!FiximizerConfig.ENABLE_SERVER_TICK_PROFILING.get()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.server_profiler_disabled"),
                    contentLeft,
                    panelTop + 158,
                    0xFFFFB85C);
            return;
        }

        drawSectionTitle(graphics,
                "screen.fiximizer.server_tick_title",
                panelTop + 151);

        RollingDurationWindow.Snapshot snapshot = ServerTickProfiler.snapshot();
        if (!snapshot.hasSamples()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.server_profiler_waiting"),
                    contentLeft,
                    panelTop + 174,
                    0xFF9FB3C8);
            return;
        }

        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.server_stats",
                        snapshot.sampleCount(),
                        String.format(Locale.ROOT, "%.2f", snapshot.averageMillis()),
                        String.format(Locale.ROOT, "%.2f", snapshot.p50Millis())),
                contentLeft,
                panelTop + 174,
                0xFFFFFFFF);

        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.server_percentiles",
                        String.format(Locale.ROOT, "%.2f", snapshot.p95Millis()),
                        String.format(Locale.ROOT, "%.2f", snapshot.p99Millis()),
                        String.format(Locale.ROOT, "%.2f", snapshot.peakMillis())),
                contentLeft,
                panelTop + 192,
                0xFFB9C9DC);

        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.server_stalls",
                        snapshot.over50Millis(),
                        snapshot.over100Millis()),
                contentLeft,
                panelTop + 210,
                0xFFB9C9DC);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.server_budget"),
                contentLeft,
                panelTop + 239,
                contentWidth - 8,
                0xFF9FB3C8);
    }

    private void drawFrames(GuiGraphics graphics) {
        drawSectionTitle(graphics, "screen.fiximizer.frames_title", panelTop + 58);
        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.frames_intro"),
                contentLeft,
                panelTop + 80,
                contentWidth - 8,
                0xFFB9C9DC);

        drawSectionTitle(graphics, "screen.fiximizer.frame_render_title", panelTop + 151);
        drawTimingRows(
                graphics,
                ClientRuntimeProfiler.renderSnapshot(),
                panelTop + 170,
                "screen.fiximizer.frame_waiting",
                "screen.fiximizer.frame_stats",
                "screen.fiximizer.frame_percentiles",
                "screen.fiximizer.frame_slow",
                false
        );

        drawSectionTitle(graphics, "screen.fiximizer.frame_tick_title", panelTop + 224);
        drawTimingRows(
                graphics,
                ClientRuntimeProfiler.clientTickSnapshot(),
                panelTop + 243,
                "screen.fiximizer.tick_waiting",
                "screen.fiximizer.tick_stats",
                "screen.fiximizer.tick_percentiles",
                "screen.fiximizer.tick_slow",
                true
        );
    }

    private void drawTimingRows(
            GuiGraphics graphics,
            RollingDurationWindow.Snapshot snapshot,
            int y,
            String waitingKey,
            String statsKey,
            String percentilesKey,
            String slowKey,
            boolean clientTick
    ) {
        if (!FiximizerConfig.ENABLE_FRAME_TIME_PROFILING.get()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.frame_profiler_disabled"),
                    contentLeft, y, 0xFFFFB85C);
            return;
        }
        if (!snapshot.hasSamples()) {
            graphics.drawString(this.font,
                    Component.translatable(waitingKey),
                    contentLeft, y, 0xFF9FB3C8);
            return;
        }

        graphics.drawString(this.font,
                Component.translatable(statsKey,
                        snapshot.sampleCount(),
                        String.format(Locale.ROOT, "%.2f", snapshot.averageMillis()),
                        String.format(Locale.ROOT, "%.2f", snapshot.p50Millis())),
                contentLeft, y, 0xFFFFFFFF);
        y += 15;
        graphics.drawString(this.font,
                Component.translatable(percentilesKey,
                        String.format(Locale.ROOT, "%.2f", snapshot.p95Millis()),
                        String.format(Locale.ROOT, "%.2f", snapshot.p99Millis()),
                        String.format(Locale.ROOT, "%.2f", snapshot.peakMillis())),
                contentLeft, y, 0xFFB9C9DC);
        y += 15;

        int firstSlowCount = clientTick ? snapshot.over50Millis() : snapshot.over16_67Millis();
        int secondSlowCount = clientTick ? snapshot.over100Millis() : snapshot.over33_33Millis();
        graphics.drawString(this.font,
                Component.translatable(slowKey, firstSlowCount, secondSlowCount),
                contentLeft, y, 0xFFB9C9DC);
    }

    private void drawPerformance(GuiGraphics graphics) {
        drawSectionTitle(graphics, "screen.fiximizer.performance_title", panelTop + 60);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.performance_intro"),
                contentLeft,
                panelTop + 82,
                contentWidth - 8,
                0xFFB9C9DC);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.selected_profile",
                        Component.translatable(OptimizationProfileManager.selected().translationKey())),
                contentLeft,
                panelTop + 160,
                contentWidth - 8,
                0xFFFFFFFF);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.effective_profile",
                        Component.translatable(OptimizationProfileManager.current().translationKey())),
                contentLeft,
                panelTop + 179,
                contentWidth - 8,
                MemoryPressureController.active() ? 0xFFFFB85C : 0xFF9BD9B0);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.memory_guard_help",
                        FiximizerConfig.MEMORY_PRESSURE_THRESHOLD.get(),
                        FiximizerConfig.MEMORY_RECOVERY_THRESHOLD.get()),
                contentLeft,
                panelTop + 219,
                contentWidth - 8,
                0xFF9FB3C8);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.memory_guard_reason",
                        MemoryPressureController.reason()),
                contentLeft,
                panelTop + 248,
                contentWidth - 8,
                0xFF9FB3C8);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.scheduler_reason."
                        + AdaptiveTaskScheduler.snapshot().scalingReason()),
                contentLeft,
                panelTop + 280,
                contentWidth - 8,
                0xFF9FB3C8);
    }

    private void drawIntegrations(GuiGraphics graphics) {
        drawSectionTitle(graphics, "screen.fiximizer.integrations_title", panelTop + 60);
        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.integrations_intro"),
                contentLeft,
                panelTop + 82,
                contentWidth - 8,
                0xFFB9C9DC);

        int y = panelTop + 136;
        List<IntegrationStatus> statuses = ModIntegrationManager.statuses();

        if (!FiximizerConfig.DETECT_COMPATIBLE_MODS.get()) {
            drawWrapped(graphics,
                    Component.translatable("screen.fiximizer.integrations_detection_disabled"),
                    contentLeft,
                    y,
                    contentWidth - 8,
                    0xFFFFB85C);
            return;
        }
        if (!ModIntegrationManager.isInitialized()) {
            drawWrapped(graphics,
                    Component.translatable("screen.fiximizer.integrations_waiting"),
                    contentLeft,
                    y,
                    contentWidth - 8,
                    0xFFFFC078);
            return;
        }
        if (statuses.isEmpty()) {
            drawWrapped(graphics,
                    Component.translatable("screen.fiximizer.integrations_detection_disabled"),
                    contentLeft,
                    y,
                    contentWidth - 8,
                    0xFFFFB85C);
            return;
        }

        for (IntegrationStatus status : statuses) {
            Component state;
            int color;
            if (!status.detected()) {
                state = Component.translatable("screen.fiximizer.not_installed");
                color = 0xFF8998AA;
            } else if (status.active()) {
                state = Component.translatable("screen.fiximizer.installed_enabled",
                        status.detectedModId(), status.version());
                color = 0xFF9BD9B0;
            } else {
                state = Component.translatable("screen.fiximizer.installed_disabled",
                        status.detectedModId(), status.version());
                color = 0xFFFFB85C;
            }

            graphics.fill(contentLeft, y - 3, contentLeft + 340, y + 20, 0xFF202E40);
            graphics.fill(contentLeft, y - 3, contentLeft + 3, y + 20,
                    status.active() ? 0xFF68CB91 : status.detected() ? 0xFFE5AD54 : 0xFF536176);
            drawWrapped(graphics,
                    Component.translatable("screen.fiximizer.integration_status",
                            status.displayName(), state),
                    contentLeft + 10,
                    y + 3,
                    205,
                    color);
            y += 27;
        }

    }

    private void drawPhysics(GuiGraphics graphics) {
        drawSectionTitle(graphics, "screen.fiximizer.physics_title", panelTop + 58);
        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.physics_intro"),
                contentLeft,
                panelTop + 80,
                contentWidth - 8,
                0xFFB9C9DC);

        List<IntegrationStatus> statuses = ModIntegrationManager.statuses();
        IntegrationStatus createStatus = findIntegrationStatus(statuses, "create");
        IntegrationStatus sableStatus = findIntegrationStatus(statuses, "sable");

        int y = panelTop + 126;
        drawSectionTitle(graphics, "screen.fiximizer.create_profiler_title", y);
        y += 18;
        drawCreateProfiler(graphics, createStatus, y);

        y = panelTop + 217;
        drawSectionTitle(graphics, "screen.fiximizer.sable_profiler_title", y);
        y += 18;
        drawSableProfiler(graphics, sableStatus, y);
    }

    private IntegrationStatus findIntegrationStatus(List<IntegrationStatus> statuses, String modId) {
        String displayName = "create".equals(modId) ? "Create" : "Sable";
        return statuses.stream()
                .filter(status -> modId.equals(status.detectedModId())
                        || displayName.equals(status.displayName()))
                .findFirst()
                .orElse(null);
    }

    private void drawCreateProfiler(GuiGraphics graphics, IntegrationStatus status, int y) {
        if (status == null) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.profiler_detection_unavailable"),
                    contentLeft, y, 0xFFFFB85C);
            return;
        }
        if (!status.detected()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.profiler_mod_missing"),
                    contentLeft, y, 0xFF8998AA);
            return;
        }
        if (!status.active()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.create_profiler_inactive"),
                    contentLeft, y, 0xFFFFB85C);
            return;
        }

        CreateContraptionProfiler.Snapshot snapshot = CreateContraptionProfiler.latest();
        if (!snapshot.hasSamples()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.create_profiler_waiting"),
                    contentLeft, y, 0xFF9FB3C8);
            return;
        }
        if (snapshot.contraptionEntityTicks() == 0L) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.create_profiler_no_entities",
                            snapshot.sampledServerTicks()),
                    contentLeft, y, 0xFF9FB3C8);
            return;
        }

        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.create_profiler_stats",
                        snapshot.contraptionEntityTicks(),
                        String.format(Locale.ROOT, "%.3f", snapshot.averageMillisPerServerTick())),
                contentLeft, y, 0xFFFFFFFF);
        y += 15;
        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.create_profiler_timing",
                        String.format(Locale.ROOT, "%.3f", snapshot.averageMillisPerEntityTick()),
                        String.format(Locale.ROOT, "%.3f", snapshot.peakEntityTickMillis())),
                contentLeft, y, 0xFFB9C9DC);
        y += 15;
        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.create_profiler_top",
                        snapshot.topEntityType(),
                        String.format(Locale.ROOT, "%.1f", snapshot.topEntityTotalMillis())),
                contentLeft, y, 0xFF9BD9B0);
    }

    private void drawSableProfiler(GuiGraphics graphics, IntegrationStatus status, int y) {
        if (status == null) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.profiler_detection_unavailable"),
                    contentLeft, y, 0xFFFFB85C);
            return;
        }
        if (!status.detected()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.profiler_mod_missing"),
                    contentLeft, y, 0xFF8998AA);
            return;
        }
        if (!status.active()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.sable_profiler_inactive"),
                    contentLeft, y, 0xFFFFB85C);
            return;
        }
        if (!SablePhysicsProfiler.available()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.sable_profiler_api_unavailable"),
                    contentLeft, y, 0xFFFFB85C);
            return;
        }

        RollingPhysicsWindow.Snapshot snapshot = SablePhysicsProfiler.latest();
        if (!snapshot.hasSamples()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.sable_profiler_waiting"),
                    contentLeft, y, 0xFF9FB3C8);
            return;
        }

        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.sable_profiler_stats",
                        snapshot.physicsSteps(),
                        String.format(Locale.ROOT, "%.3f", snapshot.averageSubstepMillis())),
                contentLeft, y, 0xFFFFFFFF);
        y += 15;
        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.sable_profiler_window",
                        String.format(Locale.ROOT, "%.1f", snapshot.totalWindowMillis()),
                        String.format(Locale.ROOT, "%.3f", snapshot.peakSubstepMillis()),
                        snapshot.dimensionCount()),
                contentLeft, y, 0xFFB9C9DC);
        y += 15;
        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.sable_profiler_top",
                        snapshot.topDimension(),
                        String.format(Locale.ROOT, "%.1f", snapshot.topDimensionMillis())),
                contentLeft, y, 0xFF9BD9B0);
    }

    private void drawJvm(GuiGraphics graphics) {
        JvmArgumentAdvisor.Recommendation recommendation = JvmArgumentAdvisor.recommend();
        drawSectionTitle(graphics, "screen.fiximizer.jvm_title", panelTop + 60);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.jvm_intro"),
                contentLeft,
                panelTop + 82,
                contentWidth - 8,
                0xFFB9C9DC);

        drawValue(graphics, "screen.fiximizer.jvm_ram",
                DiagnosticsService.formatBytes(recommendation.physicalMemoryBytes()),
                panelTop + 126);
        drawValue(graphics, "screen.fiximizer.jvm_mods",
                recommendation.loadedModCount(),
                panelTop + 148);

        graphics.fill(contentLeft, panelTop + 174, contentLeft + 340, panelTop + 191, 0xFF101A28);
        drawWrapped(graphics, Component.literal(recommendation.arguments()),
                contentLeft + 8, panelTop + 178, 324, 0xFF8FD8E8);

        drawWrapped(graphics, Component.literal(recommendation.note()),
                contentLeft,
                panelTop + 229,
                contentWidth - 8,
                0xFF9FB3C8);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.jvm_restart_note"),
                contentLeft,
                panelTop + 259,
                contentWidth - 8,
                0xFFFFC078);
    }


    private void drawMemory(GuiGraphics graphics) {
        drawSectionTitle(graphics, "screen.fiximizer.memory.title", panelTop + 60);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.memory.intro"),
                contentLeft,
                panelTop + 82,
                contentWidth - 8,
                0xFFB9C9DC);

        RuntimeMemoryCapabilities.Snapshot runtime = memoryCapabilities;
        if (runtime == null) {
            runtime = RuntimeMemoryCapabilities.current();
            memoryCapabilities = runtime;
        }
        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.memory.runtime", runtime.javaMajor()),
                contentLeft, panelTop + 111, 0xFF8FD8E8);

        String runtimeStrategyKey;
        if (runtime.compactObjectHeadersEnabled()) {
            runtimeStrategyKey = "screen.fiximizer.memory.compact_active";
        } else if (runtime.shouldRecommendCompactObjectHeaders()) {
            runtimeStrategyKey = "screen.fiximizer.memory.compact_recommended";
        } else if (runtime.compactObjectHeadersClassLimitReached()) {
            runtimeStrategyKey = "screen.fiximizer.memory.compact_class_limit";
        } else if (!runtime.compactObjectHeadersSupported()) {
            runtimeStrategyKey = "screen.fiximizer.memory.compatible_fallback";
        } else {
            runtimeStrategyKey = "screen.fiximizer.memory.compact_default";
        }
        drawWrapped(graphics,
                Component.translatable(runtimeStrategyKey),
                contentLeft,
                panelTop + 126,
                contentWidth - 8,
                0xFF9BD9B0);

        StringDeduplicator.Snapshot stats = memoryStats;
        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.memory.stats_hits",
                        stats.hits(), stats.registrations()),
                contentLeft, panelTop + 154, 0xFFFFFFFF);
        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.memory.stats_evictions",
                        stats.staleReuses(), stats.collisionEvictions(), stats.contentionFallbacks()),
                contentLeft, panelTop + 169, 0xFF9FB3C8);
        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.memory.stats_capacity",
                        stats.occupiedSlots(), stats.capacity()),
                contentLeft, panelTop + 184, 0xFF9FB3C8);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.memory.scope"),
                contentLeft,
                panelTop + 204,
                contentWidth - 8,
                0xFF9FB3C8);

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.memory.g1_note"),
                contentLeft,
                panelTop + 234,
                contentWidth - 8,
                0xFFFFC078);
    }

    private void drawGpu(GuiGraphics graphics) {
        drawSectionTitle(graphics, "screen.fiximizer.gpu_title", panelTop + 58);
        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.gpu_intro"),
                contentLeft,
                panelTop + 80,
                contentWidth - 8,
                0xFFB9C9DC);

        int y = panelTop + 130;
        if (!OpenClService.checked()) {
            drawWrapped(graphics,
                    Component.translatable("screen.fiximizer.gpu_waiting"),
                    contentLeft, y, contentWidth - 8, 0xFFFFC078);
            y += 22;
        } else if (OpenClService.devices().isEmpty()) {
            drawWrapped(graphics,
                    Component.literal(OpenClService.status()),
                    contentLeft, y, contentWidth - 8, 0xFFFFB85C);
            y += 22;
        } else {
            OpenClService.DeviceHandle device = OpenClService.primaryDevice();
            if (device != null) {
                drawWrapped(graphics,
                        Component.translatable("screen.fiximizer.gpu_device",
                                device.name(),
                                DiagnosticsService.formatBytes(device.globalMemoryBytes())),
                        contentLeft, y, contentWidth - 8, 0xFFFFFFFF);
                y += 20;
            }
        }

        List<GpuReading> readings = GpuTelemetryService.latest();
        if (!readings.isEmpty()) {
            GpuReading reading = readings.get(0);
            drawWrapped(graphics,
                    Component.translatable("screen.fiximizer.gpu_vram",
                            DiagnosticsService.formatBytes(reading.vramUsedBytes()),
                            DiagnosticsService.formatBytes(reading.vramTotalBytes())),
                    contentLeft, y, contentWidth - 8, 0xFFFFFFFF);
            y += 20;
        }

        GpuBenchmarkResult benchmark = GpuBenchmarkService.latest();
        if (GpuBenchmarkService.running()) {
            drawWrapped(graphics,
                    Component.translatable("screen.fiximizer.gpu_test_running"),
                    contentLeft, y, contentWidth - 8, 0xFFFFC078);
            y += 20;
        } else if (benchmark.passed()) {
            drawWrapped(graphics,
                    Component.translatable("screen.fiximizer.gpu_test_result",
                            String.format(Locale.ROOT, "%.2f", benchmark.uploadMillis()),
                            String.format(Locale.ROOT, "%.2f", benchmark.kernelMillis()),
                            String.format(Locale.ROOT, "%.2f", benchmark.downloadMillis())),
                    contentLeft, y, contentWidth - 8, 0xFF9BD9B0);
            y += 20;
            drawWrapped(graphics,
                    Component.translatable("screen.fiximizer.gpu_test_total",
                            String.format(Locale.ROOT, "%.2f", benchmark.gpuTotalMillis()),
                            String.format(Locale.ROOT, "%.2f", benchmark.cpuMillis()),
                            String.format(Locale.ROOT, "%.2f", benchmark.endToEndSpeedup())),
                    contentLeft, y, contentWidth - 8, 0xFFFFFFFF);
            y += 22;
            drawWrapped(graphics,
                    Component.literal(benchmark.message()),
                    contentLeft, y, contentWidth - 8,
                    benchmark.endToEndSpeedup() > 1.05 ? 0xFF9BD9B0 : 0xFFFFC078);
            y += 30;
        } else {
            drawWrapped(graphics,
                    Component.literal(benchmark.message()),
                    contentLeft, y, contentWidth - 8, 0xFFFFC078);
            y += 24;
        }

        long spillBudget = GpuSpillService.currentBudgetBytes();
        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.vram_spill",
                        DiagnosticsService.formatBytes(spillBudget),
                        DiagnosticsService.formatBytes(GpuSpillService.storedBytes()),
                        GpuSpillService.blockCount()),
                contentLeft, y, contentWidth - 8,
                spillBudget > 0L ? 0xFF9BD9B0 : 0xFF9FB3C8);
        y += 22;

        drawWrapped(graphics,
                Component.translatable("screen.fiximizer.vram_spill_note"),
                contentLeft, y, contentWidth - 8, 0xFF9FB3C8);
    }

    private void drawSectionTitle(GuiGraphics graphics, String key, int y) {
        graphics.drawString(this.font, Component.translatable(key), contentLeft, y, 0xFF8FD8E8);
    }

    private void drawValue(GuiGraphics graphics, String key, Object first, int y) {
        drawWrapped(graphics, Component.translatable(key, first), contentLeft, y,
                contentWidth - 8, 0xFFFFFFFF);
    }

    private void drawValue(GuiGraphics graphics, String key, Object first, Object second, int y) {
        drawWrapped(graphics, Component.translatable(key, first, second), contentLeft, y,
                contentWidth - 8, 0xFFFFFFFF);
    }

    private void drawValue(
            GuiGraphics graphics,
            String key,
            Object first,
            Object second,
            Object third,
            int y
    ) {
        drawWrapped(graphics, Component.translatable(key, first, second, third), contentLeft, y,
                contentWidth - 8, 0xFFFFFFFF);
    }

    private void drawValue(
            GuiGraphics graphics,
            String key,
            Object first,
            Object second,
            Object third,
            Object fourth,
            int y
    ) {
        drawWrapped(graphics, Component.translatable(key, first, second, third, fourth),
                contentLeft, y, contentWidth - 8, 0xFFFFFFFF);
    }

    private void drawWrapped(
            GuiGraphics graphics,
            Component component,
            int x,
            int y,
            int maxWidth,
            int color
    ) {
        List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(component, maxWidth);
        int lineY = y;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            graphics.drawString(this.font, line, x, lineY, color);
            lineY += 10;
        }
    }

    private String gpuLabel() {
        List<GpuReading> readings = GpuTelemetryService.latest();
        if (readings.isEmpty()) {
            return GpuTelemetryService.status();
        }

        GpuReading reading = readings.get(0);
        String utilization = reading.hasUtilization()
                ? String.format(Locale.ROOT, "%.0f%%", reading.utilizationPercent())
                : "n/a";
        return reading.name() + " " + utilization;
    }

    private String openClLabel() {
        if (!OptimizationProfileManager.current().openClAllowed()) {
            return "disabled by effective profile";
        }
        if (!FiximizerConfig.ENABLE_OPENCL_PROBE.get()) {
            return "disabled in config";
        }
        if (!OpenClService.checked()) {
            return "checking...";
        }
        if (OpenClService.devices().isEmpty()) {
            return OpenClService.status();
        }
        return OpenClService.devices().getFirst().name()
                + " (" + OpenClService.devices().size() + " GPU device(s))";
    }

    private String recorderLabel() {
        String manualStatus = switch (FlightRecorderService.state()) {
            case IDLE -> FlightRecorderService.message();
            case RECORDING -> FlightRecorderService.message() + " (" + FlightRecorderService.elapsedSeconds() + "s)";
            case SAVING -> FlightRecorderService.message();
            case FAILED -> FlightRecorderService.message();
        };
        return manualStatus + " | ring " + Component.translatable(FlightRecorderService.rollingActive()
                ? "screen.fiximizer.enabled" : "screen.fiximizer.disabled").getString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}

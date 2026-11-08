package com.makakaiglitch.fiximizer.client;

import com.makakaiglitch.fiximizer.compat.CompatibilityDetector;
import com.makakaiglitch.fiximizer.compat.IntegrationStatus;
import com.makakaiglitch.fiximizer.compat.create.CreateContraptionProfiler;
import com.makakaiglitch.fiximizer.compat.sable.RollingPhysicsWindow;
import com.makakaiglitch.fiximizer.compat.sable.SablePhysicsProfiler;
import com.makakaiglitch.fiximizer.compat.ModIntegrationManager;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsService;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsSnapshot;
import com.makakaiglitch.fiximizer.diagnostics.RollingDurationWindow;
import com.makakaiglitch.fiximizer.diagnostics.JvmArgumentAdvisor;
import com.makakaiglitch.fiximizer.gpu.GpuBenchmarkResult;
import com.makakaiglitch.fiximizer.gpu.GpuBenchmarkService;
import com.makakaiglitch.fiximizer.gpu.GpuMemoryBudget;
import com.makakaiglitch.fiximizer.gpu.GpuReading;
import com.makakaiglitch.fiximizer.gpu.GpuSpillService;
import com.makakaiglitch.fiximizer.gpu.GpuTelemetryService;
import com.makakaiglitch.fiximizer.gpu.OpenClService;
import com.makakaiglitch.fiximizer.profiler.FlightRecorderService;
import com.makakaiglitch.fiximizer.scheduler.AdaptiveTaskScheduler;
import com.makakaiglitch.fiximizer.scheduler.MemoryPressureController;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfile;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfileManager;
import com.makakaiglitch.fiximizer.scheduler.SchedulerSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
        FRAMES
    }

    private final Screen parent;
    private Tab selectedTab = Tab.OVERVIEW;

    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Button> integrationButtons = new ArrayList<>();

    private Button refreshButton;
    private Button profileButton;
    private Button memoryGuardButton;
    private Button startProfileButton;
    private Button stopProfileButton;
    private Button copyJvmButton;
    private Button saveJvmButton;
    private Button detectModsButton;
    private Button gpuTestButton;
    private Button gpuSpillButton;
    private Button gpuSpillLimitButton;
    private Button frameProfilerButton;

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
        ).bounds(navX, navY + 28, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_integrations"),
                button -> selectTab(Tab.INTEGRATIONS)
        ).bounds(navX, navY + 56, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_physics"),
                button -> selectTab(Tab.PHYSICS)
        ).bounds(navX, navY + 84, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_jvm"),
                button -> selectTab(Tab.JVM)
        ).bounds(navX, navY + 112, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_gpu"),
                button -> selectTab(Tab.GPU)
        ).bounds(navX, navY + 140, navWidth, 22).build()));

        tabButtons.add(addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.tab_frames"),
                button -> selectTab(Tab.FRAMES)
        ).bounds(navX, navY + 168, navWidth, 22).build()));

        refreshButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.refresh"),
                button -> {
                    DiagnosticsService.sampleNow();
                    ModIntegrationManager.refresh();
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
        ).bounds(panelLeft + 456, panelTop + 312, 88, 20).build());

        updateVisibility();
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

        refreshButton.visible = overview;
        startProfileButton.visible = overview;
        stopProfileButton.visible = overview;
        profileButton.visible = performance;
        memoryGuardButton.visible = performance;
        detectModsButton.visible = integrations;
        copyJvmButton.visible = jvm;
        saveJvmButton.visible = jvm;
        gpuTestButton.visible = gpu;
        frameProfilerButton.visible = frames;
        gpuSpillButton.visible = gpu;
        gpuSpillLimitButton.visible = gpu;
        gpuTestButton.active = !GpuBenchmarkService.running()
                && FiximizerConfig.ENABLE_GPU_BENCHMARK.get();

        for (Button button : integrationButtons) {
            button.visible = integrations;
        }

        FlightRecorderService.State recorderState = FlightRecorderService.state();
        startProfileButton.active = recorderState == FlightRecorderService.State.IDLE
                || recorderState == FlightRecorderService.State.FAILED;
        stopProfileButton.active = recorderState == FlightRecorderService.State.RECORDING;

        updateIntegrationButtons();
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
        graphics.fill(panelLeft + 150, panelTop + 48, panelLeft + 151, panelTop + 296, 0xFF34445C);

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
        }
    }

    private void drawOverview(GuiGraphics graphics) {
        DiagnosticsSnapshot snapshot = DiagnosticsService.latest();
        int y = panelTop + 56;

        drawSectionTitle(graphics, "screen.fiximizer.overview_title", y);
        y += 24;

        drawValue(graphics, "screen.fiximizer.heap",
                DiagnosticsService.formatBytes(snapshot.usedHeapBytes()),
                DiagnosticsService.formatBytes(snapshot.maxHeapBytes()),
                String.format(Locale.ROOT, "%.1f", snapshot.maxHeapUsagePercent()),
                y);
        y += 18;

        drawValue(graphics, "screen.fiximizer.cpu",
                DiagnosticsService.formatPercent(snapshot.systemCpuLoad()),
                DiagnosticsService.formatPercent(snapshot.processCpuLoad()),
                y);
        y += 21;

        drawValue(graphics, "screen.fiximizer.threads",
                snapshot.activeThreadCount(),
                snapshot.loadedModCount(),
                y);
        y += 21;

        drawValue(graphics, "screen.fiximizer.profile_effective",
                Component.translatable(OptimizationProfileManager.current().translationKey()),
                y);
        y += 21;

        if (MemoryPressureController.active()) {
            drawWrapped(graphics,
                    Component.translatable("screen.fiximizer.memory_override",
                            String.format(Locale.ROOT, "%.1f", MemoryPressureController.heapUsagePercent())),
                    contentLeft,
                    y,
                    contentWidth - 8,
                    0xFFFFB85C);
            y += 24;
        } else {
            drawValue(graphics, "screen.fiximizer.memory_guard_status",
                    Component.translatable("screen.fiximizer.normal"),
                    y);
            y += 18;
        }

        drawValue(graphics, "screen.fiximizer.gpu", gpuLabel(), y);
        y += 21;

        drawValue(graphics, "screen.fiximizer.opencl", openClLabel(), y);
        y += 21;

        drawValue(graphics, "screen.fiximizer.jfr", recorderLabel(), y);
        y += 21;

        drawValue(graphics, "screen.fiximizer.integrations",
                ModIntegrationManager.activeCount(),
                CompatibilityDetector.targetCount(),
                y);
        y += 16;

        SchedulerSnapshot scheduler = AdaptiveTaskScheduler.snapshot();
        drawValue(graphics, "screen.fiximizer.scheduler",
                scheduler.activeTasks(),
                scheduler.queuedTasks(),
                scheduler.targetWorkers(),
                scheduler.maximumWorkers(),
                y);
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
        return switch (FlightRecorderService.state()) {
            case IDLE -> FlightRecorderService.message();
            case RECORDING -> FlightRecorderService.message() + " (" + FlightRecorderService.elapsedSeconds() + "s)";
            case SAVING -> FlightRecorderService.message();
            case FAILED -> FlightRecorderService.message();
        };
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

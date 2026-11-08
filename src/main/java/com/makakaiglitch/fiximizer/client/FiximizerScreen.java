package com.makakaiglitch.fiximizer.client;

import com.makakaiglitch.fiximizer.compat.CompatibilityDetector;
import com.makakaiglitch.fiximizer.compat.IntegrationStatus;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsService;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsSnapshot;
import com.makakaiglitch.fiximizer.gpu.GpuReading;
import com.makakaiglitch.fiximizer.gpu.GpuTelemetryService;
import com.makakaiglitch.fiximizer.gpu.OpenClService;
import com.makakaiglitch.fiximizer.profiler.FlightRecorderService;
import com.makakaiglitch.fiximizer.profiler.ProfileSectionSnapshot;
import com.makakaiglitch.fiximizer.profiler.SectionProfiler;
import com.makakaiglitch.fiximizer.scheduler.AdaptiveTaskScheduler;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfileManager;
import com.makakaiglitch.fiximizer.scheduler.SchedulerSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class FiximizerScreen extends Screen {
    private final Screen parent;
    private Button profileButton;
    private Button startProfileButton;
    private Button stopProfileButton;

    public FiximizerScreen(Screen parent) {
        super(Component.translatable("screen.fiximizer.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int buttonY = this.height - 28;

        profileButton = this.addRenderableWidget(Button.builder(
                profileButtonLabel(),
                button -> cycleOptimizationProfile()
        ).bounds(centerX - 150, 43, 300, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.refresh"),
                button -> DiagnosticsService.sampleNow()
        ).bounds(centerX - 174, buttonY, 84, 20).build());

        startProfileButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.profile_start"),
                button -> FlightRecorderService.start()
        ).bounds(centerX - 86, buttonY, 84, 20).build());

        stopProfileButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.profile_stop"),
                button -> FlightRecorderService.stopAndSave(Minecraft.getInstance().gameDirectory.toPath())
        ).bounds(centerX + 2, buttonY, 84, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                button -> this.onClose()
        ).bounds(centerX + 90, buttonY, 84, 20).build());
    }

    private void cycleOptimizationProfile() {
        Minecraft minecraft = Minecraft.getInstance();
        Path configDirectory = minecraft.gameDirectory.toPath().resolve("config");

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

    private Component profileButtonLabel() {
        return Component.translatable(
                "screen.fiximizer.profile_button",
                Component.translatable(OptimizationProfileManager.current().translationKey())
        );
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        FlightRecorderService.State recorderState = FlightRecorderService.state();
        startProfileButton.active = recorderState == FlightRecorderService.State.IDLE
                || recorderState == FlightRecorderService.State.FAILED;
        stopProfileButton.active = recorderState == FlightRecorderService.State.RECORDING;

        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 14, 0xFFFFFF);
        graphics.drawCenteredString(
                this.font,
                Component.translatable("screen.fiximizer.subtitle"),
                centerX,
                29,
                0xA0A0A0
        );
        graphics.drawCenteredString(
                this.font,
                Component.translatable(OptimizationProfileManager.current().descriptionKey()),
                centerX,
                67,
                0xC0C0C0
        );

        DiagnosticsSnapshot snapshot = DiagnosticsService.latest();
        int left = centerX - 190;
        int y = 86;

        if (!snapshot.hasSample()) {
            drawLine(graphics, "screen.fiximizer.waiting_for_sample", left, y, 0xE0C060);
            y += 13;
        } else {
            drawLine(graphics, "screen.fiximizer.heap_used", left, y, 0xFFFFFF,
                    DiagnosticsService.formatBytes(snapshot.usedHeapBytes()),
                    DiagnosticsService.formatBytes(snapshot.maxHeapBytes()),
                    String.format(Locale.ROOT, "%.1f", snapshot.maxHeapUsagePercent()));
            y += 13;

            drawLine(graphics, "screen.fiximizer.heap_committed", left, y, 0xFFFFFF,
                    DiagnosticsService.formatBytes(snapshot.committedHeapBytes()));
            y += 13;

            drawLine(graphics, "screen.fiximizer.runtime", left, y, 0xFFFFFF,
                    snapshot.availableProcessors(), snapshot.loadedModCount());
            y += 13;

            drawLine(graphics, "screen.fiximizer.cpu", left, y, 0xFFFFFF,
                    DiagnosticsService.formatPercent(snapshot.systemCpuLoad()),
                    DiagnosticsService.formatPercent(snapshot.processCpuLoad()));
            y += 13;

            drawLine(graphics, "screen.fiximizer.threads", left, y, 0xFFFFFF,
                    snapshot.activeThreadCount());
            y += 14;
        }

        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.jfr", recorderLabel()),
                left, y, recorderState == FlightRecorderService.State.RECORDING ? 0x80D080 : 0xC0C0C0);
        y += 13;

        SchedulerSnapshot scheduler = AdaptiveTaskScheduler.snapshot();
        drawLine(graphics, "screen.fiximizer.scheduler", left, y, 0xFFFFFF,
                scheduler.activeTasks(), scheduler.queuedTasks(),
                scheduler.targetWorkers(), scheduler.maximumWorkers());
        y += 14;

        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.gpu", gpuLabel()),
                left, y, 0xFFFFFF);
        y += 13;

        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.opencl", openClLabel()),
                left, y, 0xFFFFFF);
        y += 14;

        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.hot_sections"),
                left, y, 0x80C8FF);
        y += 12;

        List<ProfileSectionSnapshot> sections = SectionProfiler.topSections(3);
        if (sections.isEmpty()) {
            drawLine(graphics, "screen.fiximizer.no_sections", left, y, 0xA0A0A0);
            y += 12;
        } else {
            for (ProfileSectionSnapshot section : sections) {
                drawLine(graphics, "screen.fiximizer.section", left, y, 0xFFFFFF,
                        section.name(),
                        String.format(Locale.ROOT, "%.1f", section.totalMillis()),
                        section.calls());
                y += 12;
            }
        }

        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.compatibility_header"),
                left, y, 0x80C8FF);
        y += 12;

        if (!FiximizerConfig.DETECT_COMPATIBLE_MODS.get()) {
            drawLine(graphics, "screen.fiximizer.compatibility_disabled", left, y, 0xA0A0A0);
        } else {
            List<IntegrationStatus> targets = CompatibilityDetector.detect();
            for (IntegrationStatus target : targets) {
                Component status = Component.translatable(
                        target.detected()
                                ? "screen.fiximizer.detected"
                                : "screen.fiximizer.not_detected"
                );
                int color = target.detected() ? 0x80D080 : 0xA0A0A0;
                graphics.drawString(this.font,
                        Component.literal(target.displayName() + ": ").append(status),
                        left, y, color);
                y += 12;
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String recorderLabel() {
        return switch (FlightRecorderService.state()) {
            case IDLE -> FlightRecorderService.message();
            case RECORDING -> FlightRecorderService.message() + " (" + FlightRecorderService.elapsedSeconds() + "s)";
            case SAVING -> FlightRecorderService.message();
            case FAILED -> FlightRecorderService.message();
        };
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
            return "disabled by selected profile";
        }
        if (!FiximizerConfig.ENABLE_OPENCL_PROBE.get()) {
            return "disabled";
        }
        if (!OpenClService.checked()) {
            return "checking...";
        }
        if (OpenClService.devices().isEmpty()) {
            return OpenClService.status();
        }

        return OpenClService.devices().get(0).name()
                + " (" + OpenClService.devices().size() + " GPU device(s))";
    }

    private void drawLine(
            GuiGraphics graphics,
            String translationKey,
            int x,
            int y,
            int color,
            Object... arguments
    ) {
        graphics.drawString(this.font, Component.translatable(translationKey, arguments), x, y, color);
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

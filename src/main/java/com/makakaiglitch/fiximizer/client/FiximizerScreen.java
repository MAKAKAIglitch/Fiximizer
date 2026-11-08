package com.makakaiglitch.fiximizer.client;

import com.makakaiglitch.fiximizer.compat.CompatibilityDetector;
import com.makakaiglitch.fiximizer.compat.IntegrationStatus;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsService;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

public final class FiximizerScreen extends Screen {
    private final Screen parent;

    public FiximizerScreen(Screen parent) {
        super(Component.translatable("screen.fiximizer.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonY = this.height - 28;

        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.fiximizer.refresh"),
                button -> DiagnosticsService.sampleNow()
        ).bounds(this.width / 2 - 102, buttonY, 100, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                button -> this.onClose()
        ).bounds(this.width / 2 + 2, buttonY, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 18, 0xFFFFFF);
        graphics.drawCenteredString(
                this.font,
                Component.translatable("screen.fiximizer.subtitle"),
                centerX,
                34,
                0xA0A0A0
        );

        DiagnosticsSnapshot snapshot = DiagnosticsService.latest();
        int left = centerX - 140;
        int y = 58;

        if (!snapshot.hasSample()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.waiting_for_sample"),
                    left, y, 0xE0C060);
            y += 16;
        } else {
            graphics.drawString(this.font, Component.translatable(
                    "screen.fiximizer.heap_used",
                    DiagnosticsService.formatBytes(snapshot.usedHeapBytes()),
                    DiagnosticsService.formatBytes(snapshot.maxHeapBytes()),
                    String.format(Locale.ROOT, "%.1f", snapshot.maxHeapUsagePercent())
            ), left, y, 0xFFFFFF);
            y += 16;

            graphics.drawString(this.font, Component.translatable(
                    "screen.fiximizer.heap_committed",
                    DiagnosticsService.formatBytes(snapshot.committedHeapBytes())
            ), left, y, 0xFFFFFF);
            y += 16;

            String load = snapshot.systemLoadAverage() < 0.0
                    ? Component.translatable("screen.fiximizer.unavailable").getString()
                    : String.format(Locale.ROOT, "%.2f", snapshot.systemLoadAverage());

            graphics.drawString(this.font, Component.translatable(
                    "screen.fiximizer.runtime",
                    snapshot.availableProcessors(),
                    snapshot.loadedModCount(),
                    load
            ), left, y, 0xFFFFFF);
            y += 20;
        }

        graphics.drawString(this.font,
                Component.translatable("screen.fiximizer.compatibility_header"),
                left, y, 0x80C8FF);
        y += 15;

        if (!FiximizerConfig.DETECT_COMPATIBLE_MODS.get()) {
            graphics.drawString(this.font,
                    Component.translatable("screen.fiximizer.compatibility_disabled"),
                    left, y, 0xA0A0A0);
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
                y += 13;
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}

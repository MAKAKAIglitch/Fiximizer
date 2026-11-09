package com.makakaiglitch.fiximizer.startup;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.compat.ModIntegrationManager;
import com.makakaiglitch.fiximizer.compat.sable.SablePhysicsProfiler;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.network.NetworkBackendSelector;
import com.makakaiglitch.fiximizer.network.NetworkCompatibilityDetector;
import com.makakaiglitch.fiximizer.diagnostics.StallWatchdog;
import com.makakaiglitch.fiximizer.gpu.GpuTelemetryService;
import com.makakaiglitch.fiximizer.gpu.OpenClService;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfileManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Path;

/**
 * Coordinates startup work owned by Fiximizer.
 *
 * This class never changes Minecraft/NeoForge lifecycle ordering, skips another
 * mod's callbacks, or moves Minecraft-owned work to a different thread. It only
 * delays optional Fiximizer diagnostics until the client has been stable.
 */
public final class StartupOptimizer {
    private static final int REQUIRED_STABLE_CLIENT_TICKS = 40;
    private static final StableClientStartupGate STARTUP_GATE =
            new StableClientStartupGate(REQUIRED_STABLE_CLIENT_TICKS);

    private static boolean compatibilityScanSucceeded;

    private StartupOptimizer() {
    }

    /**
     * Called from ClientTickEvent.Post on the client thread.
     */
    public static void onClientTick(Minecraft minecraft) {
        boolean titleScreenVisible = minecraft.screen instanceof TitleScreen;
        if (titleScreenVisible) {
            StartupProfiler.mark(StartupTimeline.Stage.FIRST_TITLE_SCREEN_TICK);
        }

        boolean clientReady = minecraft.level != null || titleScreenVisible;
        if (!STARTUP_GATE.onClientTick(clientReady)) {
            return;
        }

        // The gate latches before optional services run so a faulty integration
        // cannot make startup initialization repeat on later client ticks.
        int stableClientTicks = STARTUP_GATE.stableTicks();
        StartupProfiler.mark(StartupTimeline.Stage.STABLE_CLIENT_READY);

        StartupProfiler.mark(StartupTimeline.Stage.OPTIONAL_MOD_SCAN_STARTED);
        boolean scanCallCompleted = runOptional(
                "optional mod compatibility scan",
                ModIntegrationManager::refresh
        );
        compatibilityScanSucceeded = scanCallCompleted && ModIntegrationManager.lastRefreshSucceeded();
        StartupProfiler.mark(StartupTimeline.Stage.OPTIONAL_MOD_SCAN_COMPLETED);

        runOptional("Sable integration registration", SablePhysicsProfiler::registerIfLoaded);

        Path gameDirectory = minecraft.gameDirectory.toPath();
        runOptional("stall watchdog", () -> StallWatchdog.start(gameDirectory));
        runOptional("network compatibility detection", NetworkCompatibilityDetector::refresh);
        if (FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()) {
            runOptional("network statistics backend", NetworkBackendSelector::initializeAsync);
        }

        if (FiximizerConfig.ENABLE_GPU_TELEMETRY.get()) {
            runOptional("GPU telemetry", GpuTelemetryService::start);
        }

        if (FiximizerConfig.ENABLE_OPENCL_PROBE.get()
                && OptimizationProfileManager.current().openClAllowed()) {
            runOptional("OpenCL device probe", OpenClService::probeAsync);
        }

        StartupProfiler.mark(StartupTimeline.Stage.OPTIONAL_SERVICES_SCHEDULED);

        if (FiximizerConfig.SHOW_STARTUP_SUMMARY.get()) {
            Fiximizer.LOGGER.info(
                    "Detected {} enabled optional integration target(s).",
                    ModIntegrationManager.activeCount()
            );
        }
        Fiximizer.LOGGER.info(
                "Deferred startup services scheduled after {} stable client ticks.",
                stableClientTicks
        );
    }

    public static boolean completed() {
        return STARTUP_GATE.completed();
    }

    public static boolean compatibilityScanSucceeded() {
        return compatibilityScanSucceeded;
    }

    private static boolean runOptional(String component, Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException | LinkageError exception) {
            Fiximizer.LOGGER.warn(
                    "Optional startup component '{}' could not be initialized; continuing without it.",
                    component,
                    exception
            );
            return false;
        }
    }
}

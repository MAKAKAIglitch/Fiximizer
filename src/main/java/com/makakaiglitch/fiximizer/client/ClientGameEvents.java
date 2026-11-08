package com.makakaiglitch.fiximizer.client;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.compat.sable.SablePhysicsProfiler;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsService;
import com.makakaiglitch.fiximizer.diagnostics.StallWatchdog;
import com.makakaiglitch.fiximizer.gpu.GpuTelemetryService;
import com.makakaiglitch.fiximizer.gpu.OpenClService;
import com.makakaiglitch.fiximizer.profiler.FlightRecorderService;
import com.makakaiglitch.fiximizer.network.NetworkBackendSelector;
import com.makakaiglitch.fiximizer.network.NetworkCompatibilityDetector;
import com.makakaiglitch.fiximizer.network.NetworkTelemetryService;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfileManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client interactions and deliberately deferred optional startup work.
 *
 * Expensive hardware probes and the mod/JAR attribution index are started
 * only after the title screen or a loaded world has been ticking for a short
 * while. This keeps the critical mod-loader and initial client startup path
 * free of optional diagnostics work.
 */
@EventBusSubscriber(modid = Fiximizer.MOD_ID, value = Dist.CLIENT)
public final class ClientGameEvents {
    private static final int DEFERRED_STARTUP_DELAY_TICKS = 40;

    private static int stableClientTicks;
    private static boolean deferredStartupComplete;

    private ClientGameEvents() {
    }

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("fiximizer")
                        .executes(context -> openDashboard())
                        .then(Commands.literal("open")
                                .executes(context -> openDashboard()))
                        .then(Commands.literal("menu")
                                .executes(context -> openDashboard()))
        );
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        startDeferredServicesWhenReady(minecraft);
        NetworkTelemetryService.tick();

        if (deferredStartupComplete) {
            DiagnosticsService.tick();
        }

        if (FlightRecorderService.state() == FlightRecorderService.State.RECORDING) {
            FlightRecorderService.tick(
                    minecraft.gameDirectory.toPath(),
                    FiximizerConfig.MAX_PROFILE_SECONDS.get()
            );
        }

        while (KeyBindings.OPEN_DASHBOARD.consumeClick()) {
            openDashboard();
        }
    }

    private static int openDashboard() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof FiximizerScreen)) {
            minecraft.setScreen(new FiximizerScreen(minecraft.screen));
        }
        return 1;
    }

    private static void startDeferredServicesWhenReady(Minecraft minecraft) {
        if (deferredStartupComplete) {
            return;
        }

        // Do not compete with the resource-pack/loading screens. Count only
        // stable title-screen or gameplay ticks, then initialize optional
        // monitoring once per client process.
        boolean clientReady = minecraft.level != null || minecraft.screen instanceof TitleScreen;
        if (!clientReady) {
            stableClientTicks = 0;
            return;
        }

        stableClientTicks++;
        if (stableClientTicks < DEFERRED_STARTUP_DELAY_TICKS) {
            return;
        }

        deferredStartupComplete = true;
        SablePhysicsProfiler.registerIfLoaded();
        StallWatchdog.start(minecraft.gameDirectory.toPath());
        NetworkCompatibilityDetector.refresh();
        if (FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()) {
            NetworkBackendSelector.initializeAsync();
        }

        if (FiximizerConfig.ENABLE_GPU_TELEMETRY.get()) {
            GpuTelemetryService.start();
        }
        if (FiximizerConfig.ENABLE_OPENCL_PROBE.get()
                && OptimizationProfileManager.current().openClAllowed()) {
            OpenClService.probeAsync();
        }

        Fiximizer.LOGGER.info(
                "Deferred diagnostics started after {} stable client ticks.",
                stableClientTicks
        );
    }
}
package com.makakaiglitch.fiximizer.client;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsService;
import com.makakaiglitch.fiximizer.profiler.FlightRecorderService;
import com.makakaiglitch.fiximizer.network.NetworkTelemetryService;
import com.makakaiglitch.fiximizer.startup.StartupOptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client interactions. StartupOptimizer owns the one-shot, deferred startup
 * work so optional diagnostics do not compete with the loader or first screen.
 */
@EventBusSubscriber(modid = Fiximizer.MOD_ID, value = Dist.CLIENT)
public final class ClientGameEvents {
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
        StartupOptimizer.onClientTick(minecraft);
        NetworkTelemetryService.tick();

        if (StartupOptimizer.completed()) {
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
}

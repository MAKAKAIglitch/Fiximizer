package com.makakaiglitch.fiximizer.client;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsService;
import com.makakaiglitch.fiximizer.profiler.FlightRecorderService;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = Fiximizer.MOD_ID, value = Dist.CLIENT)
public final class ClientGameEvents {
    private ClientGameEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        DiagnosticsService.tick();

        if (FlightRecorderService.state() == FlightRecorderService.State.RECORDING) {
            Minecraft minecraft = Minecraft.getInstance();
            FlightRecorderService.tick(
                    minecraft.gameDirectory.toPath(),
                    FiximizerConfig.MAX_PROFILE_SECONDS.get()
            );
        }

        while (KeyBindings.OPEN_DASHBOARD.consumeClick()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (!(minecraft.screen instanceof FiximizerScreen)) {
                minecraft.setScreen(new FiximizerScreen(minecraft.screen));
            }
        }
    }
}

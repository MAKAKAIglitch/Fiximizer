package com.makakaiglitch.fiximizer.client;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsService;
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

        while (KeyBindings.OPEN_DASHBOARD.consumeClick()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (!(minecraft.screen instanceof FiximizerScreen)) {
                minecraft.setScreen(new FiximizerScreen(minecraft.screen));
            }
        }
    }
}

package com.makakaiglitch.fiximizer.network;

import com.makakaiglitch.fiximizer.Fiximizer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid = Fiximizer.MOD_ID, value = Dist.CLIENT)
public final class NetworkClientEvents {
    private NetworkClientEvents() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        NetworkTelemetryService.onLogin(event.getConnection());
        NetworkSocketTuning.onLogin(event.getConnection());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        NetworkTelemetryService.onLogout(event.getConnection());
        NetworkSocketTuning.onLogout(event.getConnection());
    }
}

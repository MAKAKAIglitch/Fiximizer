package com.makakaiglitch.fiximizer.client;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.scheduler.MemoryPressureController;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfileManager;
import com.makakaiglitch.fiximizer.startup.StartupProfiler;
import com.makakaiglitch.fiximizer.startup.StartupTimeline.Stage;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(
        modid = Fiximizer.MOD_ID,
        bus = EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT
)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KeyBindings.OPEN_DASHBOARD);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        StartupProfiler.mark(Stage.CLIENT_SETUP_STARTED);
        event.enqueueWork(() -> {
            OptimizationProfileManager.load(
                    Minecraft.getInstance().gameDirectory.toPath().resolve("config")
            );
            MemoryPressureController.reset();
            StartupProfiler.mark(Stage.CLIENT_SETUP_COMPLETED);

            Fiximizer.LOGGER.info("Gameplay optimization profile: {}",
                    OptimizationProfileManager.selected().id());
        });
    }
}

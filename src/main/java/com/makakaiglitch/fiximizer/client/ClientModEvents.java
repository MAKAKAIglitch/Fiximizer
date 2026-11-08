package com.makakaiglitch.fiximizer.client;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.compat.CompatibilityDetector;
import com.makakaiglitch.fiximizer.compat.IntegrationStatus;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsService;
import com.makakaiglitch.fiximizer.gpu.GpuTelemetryService;
import com.makakaiglitch.fiximizer.gpu.OpenClService;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfileManager;
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
        event.enqueueWork(() -> {
            OptimizationProfileManager.load(
                    Minecraft.getInstance().gameDirectory.toPath().resolve("config")
            );
            Fiximizer.LOGGER.info("Gameplay optimization profile: {}",
                    OptimizationProfileManager.current().id());
            DiagnosticsService.sampleNow();

            if (FiximizerConfig.SHOW_STARTUP_SUMMARY.get()) {
                long detected = CompatibilityDetector.detect().stream()
                        .filter(IntegrationStatus::detected)
                        .count();
                Fiximizer.LOGGER.info("Compatibility scan: {}/{} supported targets detected.",
                        detected, CompatibilityDetector.targetCount());
            }

            if (FiximizerConfig.ENABLE_GPU_TELEMETRY.get()) {
                GpuTelemetryService.start();
            }
            if (FiximizerConfig.ENABLE_OPENCL_PROBE.get()
                    && OptimizationProfileManager.current().openClAllowed()) {
                OpenClService.probeAsync();
            }
        });
    }
}

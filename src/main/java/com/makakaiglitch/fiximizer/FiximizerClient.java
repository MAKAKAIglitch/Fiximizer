package com.makakaiglitch.fiximizer;

import com.makakaiglitch.fiximizer.compat.CompatibilityDetector;
import com.makakaiglitch.fiximizer.compat.IntegrationStatus;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = Fiximizer.MOD_ID, dist = Dist.CLIENT)
public final class FiximizerClient {
    public FiximizerClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, FiximizerConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        if (FiximizerConfig.SHOW_STARTUP_SUMMARY.get()) {
            long detected = CompatibilityDetector.detect().stream()
                    .filter(IntegrationStatus::detected)
                    .count();
            Fiximizer.LOGGER.info("Compatibility scan: {}/{} supported targets detected.",
                    detected, CompatibilityDetector.targetCount());
        }

        Fiximizer.LOGGER.info("Client dashboard key: F8 (customizable in Controls).");
    }
}

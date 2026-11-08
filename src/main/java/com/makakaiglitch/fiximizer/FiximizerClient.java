package com.makakaiglitch.fiximizer;

import com.makakaiglitch.fiximizer.client.FiximizerScreen;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = Fiximizer.MOD_ID, dist = Dist.CLIENT)
public final class FiximizerClient {
    public FiximizerClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, FiximizerConfig.SPEC);

        // NeoForge and Better ModList both use this extension point.
        // Returning our own screen provides the same dashboard via the mod list Config button.
        container.registerExtensionPoint(
                IConfigScreenFactory.class,
                (modContainer, modListScreen) -> new FiximizerScreen(modListScreen)
        );

        Fiximizer.LOGGER.info("Fiximizer menu registered in the mod list. F8 opens the same dashboard.");
    }
}

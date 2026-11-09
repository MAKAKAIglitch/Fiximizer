package com.makakaiglitch.fiximizer;

import com.makakaiglitch.fiximizer.startup.StartupProfiler;
import com.makakaiglitch.fiximizer.startup.StartupTimeline.Stage;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Fiximizer.MOD_ID)
public final class Fiximizer {
    public static final String MOD_ID = "fiximizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Fiximizer() {
        StartupProfiler.mark(Stage.FIXIMIZER_MOD_CONSTRUCTED);
        LOGGER.info("Fiximizer is loading for Minecraft 1.21.1 / NeoForge.");
    }
}

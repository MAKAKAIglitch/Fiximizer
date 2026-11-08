package com.makakaiglitch.fiximizer.compat.create;

import com.makakaiglitch.fiximizer.Fiximizer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Client-distribution subscriber that can observe the integrated server in
 * single-player. Remote dedicated servers are intentionally not instrumented.
 */
@EventBusSubscriber(modid = Fiximizer.MOD_ID, value = Dist.CLIENT)
public final class CreateProfilerEvents {
    private CreateProfilerEvents() {
    }

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        CreateContraptionProfiler.beginServerTick();
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        CreateContraptionProfiler.endServerTick();
    }

    @SubscribeEvent
    public static void onEntityTickPre(EntityTickEvent.Pre event) {
        CreateContraptionProfiler.beginEntityTick(event.getEntity());
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        CreateContraptionProfiler.endEntityTick(event.getEntity());
    }
}

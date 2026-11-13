package com.makakaiglitch.fiximizer.ai;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import net.minecraft.world.entity.Mob;

/**
 * Runtime controls for the integrated-server AI throttle.
 * A command override lasts for the current game session; config is used otherwise.
 */
public final class AiThrottleController {
    private static volatile int runtimeInterval = -1;

    private AiThrottleController() {
    }

    public static int effectiveInterval() {
        int override = runtimeInterval;
        int configured = override >= 0
                ? override
                : FiximizerConfig.AI_THROTTLE_INTERVAL.get();
        return AiThrottlePolicy.normalizeInterval(configured);
    }

    public static boolean shouldRunFullCheck(Mob mob) {
        return AiThrottlePolicy.shouldRunFullCheck(
                mob.tickCount,
                mob.getId(),
                effectiveInterval()
        );
    }

    public static void setRuntimeInterval(int interval) {
        runtimeInterval = AiThrottlePolicy.normalizeInterval(interval);
    }

    public static void clearRuntimeOverride() {
        runtimeInterval = -1;
    }
}

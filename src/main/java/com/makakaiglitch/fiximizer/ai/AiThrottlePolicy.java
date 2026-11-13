package com.makakaiglitch.fiximizer.ai;

/**
 * Pure scheduling policy for full mob goal-selector checks.
 * The vanilla selector still updates running goals on every intervening tick.
 */
public final class AiThrottlePolicy {
    private AiThrottlePolicy() {
    }

    public static int normalizeInterval(int interval) {
        return interval == 4 || interval == 8 ? interval : 2;
    }

    /**
     * Called only at vanilla full-check call sites (normally every second tick).
     * Keeps vanilla startup checks and staggers longer intervals by entity ID.
     */
    public static boolean shouldRunFullCheck(long tickCount, int entityId, int interval) {
        int normalized = normalizeInterval(interval);
        return normalized == 2
                || tickCount <= 1L
                || Math.floorMod(tickCount + (long) entityId, normalized) == 0L;
    }
}

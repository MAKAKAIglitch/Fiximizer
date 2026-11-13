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
     * Called only at vanilla full-check call sites (every second entity tick).
     * Converts the entity tick into a full-check slot before applying the
     * interval, so entity-ID staggering cannot exclude half of the mobs.
     * Keeps vanilla startup checks and staggers longer intervals by entity ID.
     */
    public static boolean shouldRunFullCheck(long tickCount, int entityId, int interval) {
        int normalized = normalizeInterval(interval);
        if (normalized == 2 || tickCount <= 1L) {
            return true;
        }

        long fullCheckSlot = tickCount / 2L;
        int fullCheckInterval = normalized / 2;
        return Math.floorMod(fullCheckSlot + (long) entityId, fullCheckInterval) == 0L;
    }
}

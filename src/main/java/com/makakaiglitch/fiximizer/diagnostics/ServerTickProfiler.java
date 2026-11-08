package com.makakaiglitch.fiximizer.diagnostics;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;

/**
 * Measures complete logical-server tick wall-clock duration on the integrated
 * single-player server. The event bridge runs on the server thread, so window
 * mutation and reset are serialized with normal server ticking.
 */
public final class ServerTickProfiler {
    private static final RollingDurationWindow WINDOW =
            new RollingDurationWindow(RollingDurationWindow.DEFAULT_CAPACITY);

    private static boolean lastKnownEnabled = true;
    private static long tickStartedAtNanos;

    private ServerTickProfiler() {
    }

    public static void beginTick() {
        synchronizeEnabledState();
        if (lastKnownEnabled) {
            tickStartedAtNanos = System.nanoTime();
        }
    }

    public static void endTick() {
        long startedAt = tickStartedAtNanos;
        tickStartedAtNanos = 0L;

        if (startedAt == 0L || !lastKnownEnabled) {
            return;
        }

        WINDOW.record(Math.max(0L, System.nanoTime() - startedAt));
    }

    /**
     * Called only from server tick callbacks. This keeps reset and recording
     * on the same thread even when the setting is changed from the client UI.
     */
    private static void synchronizeEnabledState() {
        boolean enabled = FiximizerConfig.ENABLE_SERVER_TICK_PROFILING.get();
        if (enabled == lastKnownEnabled) {
            return;
        }

        lastKnownEnabled = enabled;
        tickStartedAtNanos = 0L;
        WINDOW.reset();
    }

    public static boolean enabled() {
        return FiximizerConfig.ENABLE_SERVER_TICK_PROFILING.get();
    }

    public static RollingDurationWindow.Snapshot snapshot() {
        return WINDOW.snapshot();
    }
}

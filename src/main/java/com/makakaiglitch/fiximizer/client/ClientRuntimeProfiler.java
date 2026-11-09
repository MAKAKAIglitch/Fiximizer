package com.makakaiglitch.fiximizer.client;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.benchmark.BenchmarkEngine;
import com.makakaiglitch.fiximizer.diagnostics.RollingDurationWindow;
import com.makakaiglitch.fiximizer.diagnostics.StallWatchdog;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Passive client workload telemetry.
 * RenderFrameEvent surrounds GameRenderer.render(); ClientTickEvent surrounds
 * the client tick. Timings are wall-clock durations, not GPU execution time.
 */
@EventBusSubscriber(modid = Fiximizer.MOD_ID, value = Dist.CLIENT)
public final class ClientRuntimeProfiler {
    private static final RollingDurationWindow RENDER_WINDOW =
            new RollingDurationWindow(RollingDurationWindow.DEFAULT_CAPACITY);
    private static final RollingDurationWindow CLIENT_TICK_WINDOW =
            new RollingDurationWindow(RollingDurationWindow.DEFAULT_CAPACITY);

    // These events and the dashboard run on the client thread.
    private static long renderStartedAtNanos;
    private static long clientTickStartedAtNanos;
    private static boolean lastKnownEnabled = true;

    private ClientRuntimeProfiler() {
    }

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        StallWatchdog.beginRenderFrame();
        synchronizeEnabledState();
        if (lastKnownEnabled || BenchmarkEngine.isCollecting()) {
            renderStartedAtNanos = System.nanoTime();
        }
    }

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        StallWatchdog.endRenderFrame();
        synchronizeEnabledState();
        long startedAt = renderStartedAtNanos;
        renderStartedAtNanos = 0L;
        if (startedAt != 0L) {
            long duration = Math.max(0L, System.nanoTime() - startedAt);
            if (lastKnownEnabled) {
                RENDER_WINDOW.record(duration);
            }
            BenchmarkEngine.recordRenderSample(duration);
        }
    }

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        StallWatchdog.beginClientTick();
        synchronizeEnabledState();
        if (lastKnownEnabled || BenchmarkEngine.isCollecting()) {
            clientTickStartedAtNanos = System.nanoTime();
        }
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        StallWatchdog.endClientTick();
        synchronizeEnabledState();
        long startedAt = clientTickStartedAtNanos;
        clientTickStartedAtNanos = 0L;
        if (startedAt != 0L) {
            long duration = Math.max(0L, System.nanoTime() - startedAt);
            if (lastKnownEnabled) {
                CLIENT_TICK_WINDOW.record(duration);
            }
            BenchmarkEngine.recordClientTickSample(duration);
        }
    }

    /**
     * Called by the dashboard after its toggle changes. Disabling clears all
     * telemetry so old samples are not presented as current measurements.
     */
    public static void synchronizeEnabledState() {
        boolean enabled = FiximizerConfig.ENABLE_FRAME_TIME_PROFILING.get();
        if (enabled == lastKnownEnabled) {
            return;
        }

        lastKnownEnabled = enabled;
        renderStartedAtNanos = 0L;
        clientTickStartedAtNanos = 0L;
        if (!enabled) {
            RENDER_WINDOW.reset();
            CLIENT_TICK_WINDOW.reset();
        }
    }

    public static RollingDurationWindow.Snapshot renderSnapshot() {
        return RENDER_WINDOW.snapshot();
    }

    public static RollingDurationWindow.Snapshot clientTickSnapshot() {
        return CLIENT_TICK_WINDOW.snapshot();
    }
}

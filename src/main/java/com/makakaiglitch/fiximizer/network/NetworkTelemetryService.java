package com.makakaiglitch.fiximizer.network;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.Connection;

import java.util.Arrays;

/** Low-overhead client-side traffic and server-reported latency telemetry. */
public final class NetworkTelemetryService {
    private static final String HANDLER_NAME = "fiximizer_network_telemetry";
    private static final int PING_WINDOW_SIZE = 64;
    private static final long PING_SAMPLE_INTERVAL_NANOS = 1_000_000_000L;
    private static final long[] PING_SAMPLES = new long[PING_WINDOW_SIZE];

    private static volatile Connection activeConnection;
    private static volatile Channel activeChannel;
    private static volatile NetworkTrafficHandler trafficHandler;
    private static int pingSampleCount;
    private static int pingSampleCursor;
    private static long lastPingSampleNanos;
    private static volatile int serverReportedPingMillis = -1;
    private static volatile NetworkTelemetrySnapshot cachedSnapshot =
            NetworkTelemetrySnapshot.idle("Waiting for a multiplayer connection");
    private static long lastSnapshotRefreshNanos;

    private NetworkTelemetryService() {
    }

    public static void onLogin(Connection connection) {
        clearLatencyHistory();
        activeConnection = connection;
        if (FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()) installHandler(connection);
    }

    public static void onLogout(Connection connection) {
        Connection current = activeConnection;
        if (connection == null || current == connection) {
            activeConnection = null;
            uninstallHandler();
            clearLatencyHistory();
        }
    }

    public static void tick() {
        if (!FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()) {
            if (activeChannel != null || trafficHandler != null) {
                uninstallHandler();
            }
            return;
        }

        Connection connection = activeConnection;
        if (connection != null) {
            installHandler(connection);
            if (!connection.isConnected()) {
                activeConnection = null;
                uninstallHandler();
            }
        }
        sampleServerReportedPing();
    }

    public static void refreshHandler() {
        Connection connection = activeConnection;
        if (connection == null) return;
        if (FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()) installHandler(connection);
        else uninstallHandler();
    }

    private static void installHandler(Connection connection) {
        if (connection == null || !FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()) return;

        final Channel channel;
        try {
            channel = connection.channel();
        } catch (RuntimeException exception) {
            return;
        }
        if (channel == null || !channel.isOpen()) return;

        NetworkTrafficHandler currentHandler = trafficHandler;
        if (activeChannel == channel && currentHandler != null
                && channel.pipeline().get(HANDLER_NAME) == currentHandler) return;

        channel.eventLoop().execute(() -> {
            if (!channel.isActive()) return;
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) instanceof NetworkTrafficHandler existing) {
                activeChannel = channel;
                trafficHandler = existing;
                return;
            }
            NetworkTrafficHandler handler = new NetworkTrafficHandler();
            pipeline.addFirst(HANDLER_NAME, handler);
            activeChannel = channel;
            trafficHandler = handler;
        });
    }

    private static void uninstallHandler() {
        Channel channel = activeChannel;
        NetworkTrafficHandler handler = trafficHandler;
        activeChannel = null;
        trafficHandler = null;
        if (channel == null || handler == null) return;

        try {
            channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(HANDLER_NAME) == handler) {
                    channel.pipeline().remove(HANDLER_NAME);
                }
            });
        } catch (RuntimeException ignored) {
            // A closing channel no longer needs the observer.
        }
    }

    private static void sampleServerReportedPing() {
        long now = System.nanoTime();
        if (now - lastPingSampleNanos < PING_SAMPLE_INTERVAL_NANOS) return;
        lastPingSampleNanos = now;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            serverReportedPingMillis = -1;
            return;
        }

        PlayerInfo info = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
        if (info == null) {
            serverReportedPingMillis = -1;
            return;
        }

        int ping = Math.max(0, info.getLatency());
        serverReportedPingMillis = ping;
        PING_SAMPLES[pingSampleCursor] = ping;
        pingSampleCursor = (pingSampleCursor + 1) % PING_WINDOW_SIZE;
        if (pingSampleCount < PING_WINDOW_SIZE) pingSampleCount++;
    }

    private static void clearLatencyHistory() {
        Arrays.fill(PING_SAMPLES, 0L);
        pingSampleCount = 0;
        pingSampleCursor = 0;
        lastPingSampleNanos = 0L;
        serverReportedPingMillis = -1;
        cachedSnapshot = NetworkTelemetrySnapshot.idle("Waiting for a multiplayer connection");
        lastSnapshotRefreshNanos = 0L;
    }

    public static synchronized NetworkTelemetrySnapshot snapshot() {
        long now = System.nanoTime();
        if (now - lastSnapshotRefreshNanos < PING_SAMPLE_INTERVAL_NANOS) {
            return cachedSnapshot;
        }
        lastSnapshotRefreshNanos = now;

        NetworkTrafficHandler handler = trafficHandler;
        Channel channel = activeChannel;
        boolean connected = channel != null && channel.isActive() && handler != null;
        if (!FiximizerConfig.ENABLE_NETWORK_TELEMETRY.get()) {
            cachedSnapshot = NetworkTelemetrySnapshot.idle("Network telemetry disabled");
            return cachedSnapshot;
        }
        if (!connected) {
            cachedSnapshot = NetworkTelemetrySnapshot.idle("Waiting for a multiplayer connection");
            return cachedSnapshot;
        }

        long[] samples = copyPingSamples();
        long[] percentiles = new long[3];
        boolean hasPingSamples = samples.length > 0
                && NetworkBackendSelector.summarize(samples, percentiles);
        long jitter = calculateJitter(samples);
        cachedSnapshot = new NetworkTelemetrySnapshot(
                true, handler.inboundBytes(), handler.outboundBytes(),
                handler.inboundFrames(), handler.outboundFrames(),
                serverReportedPingMillis,
                hasPingSamples ? percentiles[0] : -1L,
                hasPingSamples ? percentiles[1] : -1L,
                jitter, "Passive Netty byte observer"
        );
        return cachedSnapshot;
    }

    private static long[] copyPingSamples() {
        int count = pingSampleCount;
        long[] samples = new long[count];
        int start = (pingSampleCursor - count + PING_WINDOW_SIZE) % PING_WINDOW_SIZE;
        for (int i = 0; i < count; i++) {
            samples[i] = PING_SAMPLES[(start + i) % PING_WINDOW_SIZE];
        }
        return samples;
    }

    private static long calculateJitter(long[] samples) {
        if (samples.length < 2) return -1L;
        long total = 0L;
        for (int i = 1; i < samples.length; i++) {
            total += Math.abs(samples[i] - samples[i - 1]);
        }
        return total / (samples.length - 1L);
    }
}

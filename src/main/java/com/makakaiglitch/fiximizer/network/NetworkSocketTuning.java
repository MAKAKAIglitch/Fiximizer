package com.makakaiglitch.fiximizer.network;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import net.minecraft.network.Connection;

/**
 * Applies one narrowly scoped client TCP policy.
 *
 * TCP_NODELAY is only changed when the channel exposes the option, its current
 * value is false, and no known Netty stack optimizer is installed. The
 * previous value is restored when the setting is disabled or the connection
 * ends. No packet data or protocol behavior is modified.
 */
public final class NetworkSocketTuning {
    private static volatile Connection activeConnection;
    private static volatile Channel tunedChannel;
    private static volatile Boolean previousTcpNoDelay;
    private static volatile String status = "Waiting for a TCP connection";

    private NetworkSocketTuning() {
    }

    public static void onLogin(Connection connection) {
        activeConnection = connection;
        apply(connection);
    }

    public static void onLogout(Connection connection) {
        if (connection == null || activeConnection == connection) {
            activeConnection = null;
            restore();
            status = "Disconnected";
        }
    }

    public static void refresh() {
        if (FiximizerConfig.ENABLE_NETWORK_TCP_NODELAY.get()) {
            apply(activeConnection);
        } else {
            restore();
            status = "Disabled in settings";
        }
    }

    private static void apply(Connection connection) {
        if (!FiximizerConfig.ENABLE_NETWORK_TCP_NODELAY.get()) {
            status = "Disabled in settings";
            return;
        }
        if (connection == null) {
            status = "Waiting for a TCP connection";
            return;
        }
        if (NetworkCompatibilityDetector.snapshot().externalStackOptimizer()) {
            status = "External network optimizer detected; socket option left unchanged";
            return;
        }

        final Channel channel;
        try {
            channel = connection.channel();
        } catch (RuntimeException exception) {
            status = "Connection channel is unavailable";
            return;
        }
        if (channel == null || !channel.isOpen()) {
            status = "Connection channel is closed";
            return;
        }

        channel.eventLoop().execute(() -> {
            if (!channel.isOpen()) return;
            if (tunedChannel != null && tunedChannel != channel) {
                restoreChannel(tunedChannel, previousTcpNoDelay);
                tunedChannel = null;
                previousTcpNoDelay = null;
            }

            try {
                Boolean current = channel.config().getOption(ChannelOption.TCP_NODELAY);
                if (current == null) {
                    status = "Transport does not expose TCP_NODELAY";
                    return;
                }
                if (Boolean.TRUE.equals(current)) {
                    tunedChannel = channel;
                    previousTcpNoDelay = null;
                    status = "TCP_NODELAY is already enabled by the connection";
                    return;
                }

                if (channel.config().setOption(ChannelOption.TCP_NODELAY, true)) {
                    tunedChannel = channel;
                    previousTcpNoDelay = false;
                    status = "Enabled TCP_NODELAY for this connection";
                } else {
                    status = "Transport rejected TCP_NODELAY";
                }
            } catch (RuntimeException exception) {
                status = "TCP_NODELAY is unavailable on this transport";
            }
        });
    }

    public static void restore() {
        Channel channel = tunedChannel;
        Boolean previous = previousTcpNoDelay;
        tunedChannel = null;
        previousTcpNoDelay = null;
        if (channel != null && previous != null) {
            restoreChannel(channel, previous);
        }
    }

    private static void restoreChannel(Channel channel, Boolean previous) {
        if (channel == null || previous == null) return;
        try {
            channel.eventLoop().execute(() -> {
                if (!channel.isOpen()) return;
                try {
                    // Restore only while the value is still the one Fiximizer set.
                    if (Boolean.TRUE.equals(channel.config().getOption(ChannelOption.TCP_NODELAY))) {
                        channel.config().setOption(ChannelOption.TCP_NODELAY, previous);
                    }
                } catch (RuntimeException ignored) {
                    // A closing or non-TCP channel no longer needs restoration.
                }
            });
        } catch (RuntimeException ignored) {
            // The event loop is already shutting down.
        }
    }

    public static String status() {
        return status;
    }
}

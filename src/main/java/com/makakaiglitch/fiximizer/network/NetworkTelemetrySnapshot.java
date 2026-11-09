package com.makakaiglitch.fiximizer.network;

public record NetworkTelemetrySnapshot(
        boolean connected,
        long inboundBytes,
        long outboundBytes,
        long inboundFrames,
        long outboundFrames,
        int serverReportedPingMillis,
        long pingP50Millis,
        long pingP95Millis,
        long jitterMillis,
        String status
) {
    public static NetworkTelemetrySnapshot idle(String status) {
        return new NetworkTelemetrySnapshot(false, 0L, 0L, 0L, 0L, -1, -1L, -1L, -1L, status);
    }
}

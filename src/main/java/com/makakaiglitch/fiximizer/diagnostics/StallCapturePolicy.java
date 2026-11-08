package com.makakaiglitch.fiximizer.diagnostics;

/** Pure timing policy used by the watchdog and its unit tests. */
final class StallCapturePolicy {
    private StallCapturePolicy() {
    }

    static boolean shouldCapture(
            long elapsedNanos,
            long nowNanos,
            long thresholdNanos,
            long lastCaptureNanos,
            long repeatIntervalNanos,
            int capturedCount,
            int maximumCaptures
    ) {
        if (maximumCaptures < 1 || capturedCount >= maximumCaptures) return false;
        if (elapsedNanos < Math.max(0L, thresholdNanos)) return false;
        if (lastCaptureNanos == 0L) return true;
        return nowNanos - lastCaptureNanos >= Math.max(0L, repeatIntervalNanos);
    }
}

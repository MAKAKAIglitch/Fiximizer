package com.makakaiglitch.fiximizer.gpu;

public record GpuReading(
        String name,
        String vendor,
        double utilizationPercent,
        long vramUsedBytes,
        long vramTotalBytes,
        double temperatureCelsius,
        double powerWatts
) {
    public boolean hasUtilization() {
        return Double.isFinite(utilizationPercent) && utilizationPercent >= 0.0;
    }
}

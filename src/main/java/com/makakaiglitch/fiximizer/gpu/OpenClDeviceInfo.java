package com.makakaiglitch.fiximizer.gpu;

public record OpenClDeviceInfo(
        String platform,
        String name,
        String vendor,
        String version,
        long globalMemoryBytes
) {
}

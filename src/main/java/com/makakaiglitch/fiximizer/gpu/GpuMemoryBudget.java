package com.makakaiglitch.fiximizer.gpu;

/**
 * Conservative VRAM budget calculation. It never assumes all reported free
 * VRAM belongs to Fiximizer: a fixed and proportional graphics reserve is kept.
 */
public final class GpuMemoryBudget {
    public static final long GIB = 1024L * 1024L * 1024L;
    private static final long MINIMUM_GRAPHICS_RESERVE = 1536L * 1024L * 1024L;
    private static final double PROPORTIONAL_GRAPHICS_RESERVE = 0.20;

    private GpuMemoryBudget() {
    }

    public static long safeSpillBudget(
            long totalVramBytes,
            long usedVramBytes,
            long configuredLimitBytes
    ) {
        if (totalVramBytes <= 0L || usedVramBytes < 0L || configuredLimitBytes <= 0L) {
            return 0L;
        }

        long freeBytes = Math.max(0L, totalVramBytes - Math.min(totalVramBytes, usedVramBytes));
        long reserveBytes = Math.max(
                MINIMUM_GRAPHICS_RESERVE,
                (long) (totalVramBytes * PROPORTIONAL_GRAPHICS_RESERVE)
        );
        long availableForSpill = Math.max(0L, freeBytes - reserveBytes);

        return Math.min(configuredLimitBytes, availableForSpill);
    }
}

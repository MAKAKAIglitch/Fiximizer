package com.makakaiglitch.fiximizer.gpu;

/**
 * Describes an independent, data-parallel operation that already has a
 * compatible OpenCL kernel. Existing Minecraft/mod code is not auto-converted.
 */
public record ComputeWorkload(
        String name,
        long operationCount,
        long inputBytes,
        long outputBytes,
        boolean hasOpenClKernel
) {
    public long transferBytes() {
        return Math.max(0L, inputBytes) + Math.max(0L, outputBytes);
    }
}

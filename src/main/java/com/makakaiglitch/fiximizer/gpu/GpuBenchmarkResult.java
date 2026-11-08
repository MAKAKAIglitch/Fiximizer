package com.makakaiglitch.fiximizer.gpu;

public record GpuBenchmarkResult(
        boolean passed,
        String deviceName,
        long testedBufferBytes,
        double uploadMillis,
        double kernelMillis,
        double downloadMillis,
        double gpuTotalMillis,
        double cpuMillis,
        double endToEndSpeedup,
        String message,
        long completedAtMillis
) {
    public static GpuBenchmarkResult notRun(String message) {
        return new GpuBenchmarkResult(
                false, "Not tested", 0L, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, message, 0L
        );
    }
}

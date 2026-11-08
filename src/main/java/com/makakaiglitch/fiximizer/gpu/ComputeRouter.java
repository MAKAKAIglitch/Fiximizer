package com.makakaiglitch.fiximizer.gpu;

import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsSnapshot;

import java.util.List;

/**
 * Conservative policy for deciding whether a future Fiximizer-owned kernel
 * should run on CPU or OpenCL. The decision alone does not execute a kernel.
 */
public final class ComputeRouter {
    private static final long MIN_OPERATIONS_FOR_GPU = 250_000L;
    private static final long MIN_TRANSFER_BYTES_FOR_GPU = 2L * 1024L * 1024L;
    private static final double CPU_BUSY_THRESHOLD = 0.75;
    private static final double GPU_BUSY_THRESHOLD = 75.0;

    private ComputeRouter() {
    }

    public static ComputeDecision decide(
            ComputeWorkload workload,
            DiagnosticsSnapshot cpu,
            List<GpuReading> gpuReadings,
            List<OpenClDeviceInfo> openClDevices
    ) {
        if (workload == null || !workload.hasOpenClKernel()) {
            return cpu("No tested OpenCL kernel is registered for this workload.");
        }
        if (openClDevices == null || openClDevices.isEmpty()) {
            return cpu("No usable OpenCL GPU device was detected.");
        }
        if (workload.operationCount() < MIN_OPERATIONS_FOR_GPU) {
            return cpu("Workload is too small to amortize GPU dispatch overhead.");
        }
        if (workload.transferBytes() < MIN_TRANSFER_BYTES_FOR_GPU) {
            return cpu("Data transfer volume is too small for GPU offload.");
        }
        if (cpu == null || cpu.systemCpuLoad() < CPU_BUSY_THRESHOLD) {
            return cpu("CPU is not busy enough to justify GPU offload.");
        }

        double gpuLoad = gpuReadings == null
                ? -1.0
                : gpuReadings.stream()
                    .filter(GpuReading::hasUtilization)
                    .mapToDouble(GpuReading::utilizationPercent)
                    .max()
                    .orElse(-1.0);

        if (gpuLoad < 0.0) {
            return cpu("GPU utilization is unavailable; keeping the safe CPU path.");
        }
        if (gpuLoad >= GPU_BUSY_THRESHOLD) {
            return cpu("GPU is already busy with rendering or other workloads.");
        }

        return new ComputeDecision(
                ComputeDecision.Target.GPU,
                "CPU is busy, GPU has headroom, and the workload has a tested OpenCL kernel."
        );
    }

    private static ComputeDecision cpu(String reason) {
        return new ComputeDecision(ComputeDecision.Target.CPU, reason);
    }
}

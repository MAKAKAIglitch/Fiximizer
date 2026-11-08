package com.makakaiglitch.fiximizer.gpu;

import com.makakaiglitch.fiximizer.diagnostics.DiagnosticsSnapshot;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfile;
import com.makakaiglitch.fiximizer.scheduler.OptimizationProfileManager;

import java.util.List;

/**
 * Conservative policy for deciding whether a Fiximizer-owned workload with
 * a tested OpenCL kernel should run on CPU or GPU.
 *
 * The decision alone does not execute a kernel and third-party mod code is
 * never converted or moved automatically.
 */
public final class ComputeRouter {
    private ComputeRouter() {
    }

    public static ComputeDecision decide(
            ComputeWorkload workload,
            DiagnosticsSnapshot cpu,
            List<GpuReading> gpuReadings,
            List<OpenClDeviceInfo> openClDevices
    ) {
        OptimizationProfile profile = OptimizationProfileManager.current();

        if (!profile.openClAllowed()) {
            return cpu("OpenCL offload is disabled by the " + profile.id() + " profile.");
        }
        if (workload == null || !workload.hasOpenClKernel()) {
            return cpu("No tested OpenCL kernel is registered for this workload.");
        }
        if (openClDevices == null || openClDevices.isEmpty()) {
            return cpu("No usable OpenCL GPU device was detected.");
        }
        if (workload.operationCount() < profile.minimumGpuOperations()) {
            return cpu("This profile keeps workloads below its GPU operation threshold on CPU.");
        }
        if (workload.transferBytes() < profile.minimumGpuTransferBytes()) {
            return cpu("This profile keeps small data transfers on CPU.");
        }
        if (cpu == null
                || !Double.isFinite(cpu.systemCpuLoad())
                || cpu.systemCpuLoad() < profile.openClCpuThreshold()) {
            return cpu("CPU load is below the current profile's GPU offload threshold.");
        }

        double gpuLoad = gpuReadings == null
                ? -1.0
                : gpuReadings.stream()
                    .filter(GpuReading::hasUtilization)
                    .mapToDouble(GpuReading::utilizationPercent)
                    .max()
                    .orElse(-1.0);

        if (!Double.isFinite(gpuLoad) || gpuLoad < 0.0) {
            return cpu("GPU utilization is unavailable; keeping the safe CPU path.");
        }
        if (gpuLoad >= profile.maximumGpuLoadForOffload()) {
            return cpu("GPU load is above the current profile's offload limit.");
        }

        return new ComputeDecision(
                ComputeDecision.Target.GPU,
                "The selected profile allows offload; CPU is busy, GPU has headroom, and a tested kernel exists."
        );
    }

    private static ComputeDecision cpu(String reason) {
        return new ComputeDecision(ComputeDecision.Target.CPU, reason);
    }
}

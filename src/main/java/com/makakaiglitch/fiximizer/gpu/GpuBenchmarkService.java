package com.makakaiglitch.fiximizer.gpu;

import com.makakaiglitch.fiximizer.Fiximizer;
import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opencl.CL10.CL_BUILD_PROGRAM_FAILURE;
import static org.lwjgl.opencl.CL10.CL_MEM_READ_ONLY;
import static org.lwjgl.opencl.CL10.CL_MEM_WRITE_ONLY;
import static org.lwjgl.opencl.CL10.CL_PROGRAM_BUILD_LOG;
import static org.lwjgl.opencl.CL10.CL_SUCCESS;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * A manual, bounded end-to-end OpenCL test. It never runs on the render thread.
 * The test measures transfers separately from kernel execution and validates
 * every output value against a CPU result before GPU offload can be trusted.
 */
public final class GpuBenchmarkService {
    private static final int ELEMENT_COUNT = 1_048_576;
    private static final long BUFFER_BYTES = (long) ELEMENT_COUNT * Float.BYTES;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static volatile GpuBenchmarkResult latest =
            GpuBenchmarkResult.notRun("Тест ещё не запускался");

    private GpuBenchmarkService() {
    }

    public static boolean runAsync(Path gameDirectory) {
        if (!FiximizerConfig.ENABLE_GPU_BENCHMARK.get()
                || !RUNNING.compareAndSet(false, true)) {
            return false;
        }

        latest = GpuBenchmarkResult.notRun("Подготовка OpenCL-теста...");
        Thread thread = new Thread(() -> {
            try {
                latest = runTest(gameDirectory);
            } catch (Exception | LinkageError exception) {
                String message = safeMessage(exception);
                latest = GpuBenchmarkResult.notRun("GPU test failed: " + message);
                Fiximizer.LOGGER.warn("Fiximizer OpenCL self-test failed; automatic GPU offload stays disabled.",
                        exception);
            } finally {
                RUNNING.set(false);
            }
        }, "Fiximizer-GPU-Benchmark");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static GpuBenchmarkResult runTest(Path gameDirectory) throws IOException {
        if (!OpenClService.checked()) {
            OpenClService.probeAsync();
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (!OpenClService.checked() && System.nanoTime() < deadline) {
                sleepBriefly();
            }
        }

        OpenClService.DeviceHandle device = OpenClService.primaryDevice();
        if (device == null) {
            throw new IllegalStateException(
                    "OpenCL GPU not found. Check the graphics driver and OpenCL runtime."
            );
        }
        if (device.maximumAllocationBytes() > 0L && device.maximumAllocationBytes() < BUFFER_BYTES) {
            throw new IllegalStateException("GPU maximum single allocation is smaller than the test buffer.");
        }

        FloatBuffer inputA = null;
        FloatBuffer inputB = null;
        FloatBuffer cpuOutput = null;
        FloatBuffer gpuOutput = null;

        long inputABuffer = 0L;
        long inputBBuffer = 0L;
        long outputBuffer = 0L;
        long program = 0L;
        long kernel = 0L;

        double uploadMillis = 0.0;
        double kernelMillis = 0.0;
        double downloadMillis = 0.0;
        double cpuMillis = 0.0;

        try (OpenClSession session = OpenClSession.open();
             MemoryStack stack = stackPush()) {
            inputA = memAllocFloat(ELEMENT_COUNT);
            inputB = memAllocFloat(ELEMENT_COUNT);
            cpuOutput = memAllocFloat(ELEMENT_COUNT);
            gpuOutput = memAllocFloat(ELEMENT_COUNT);

            for (int i = 0; i < ELEMENT_COUNT; i++) {
                inputA.put(i, i * 0.125f);
                inputB.put(i, (i % 97) * 0.5f);
            }

            // Warm up the CPU path before collecting its baseline.
            for (int pass = 0; pass < 3; pass++) {
                runCpuVectorAdd(inputA, inputB, cpuOutput);
            }
            long cpuStart = System.nanoTime();
            for (int pass = 0; pass < 5; pass++) {
                runCpuVectorAdd(inputA, inputB, cpuOutput);
            }
            cpuMillis = elapsedMillis(cpuStart) / 5.0;

            var error = stack.mallocInt(1);
            inputABuffer = createBuffer(session.context(), CL_MEM_READ_ONLY, error);
            inputBBuffer = createBuffer(session.context(), CL_MEM_READ_ONLY, error);
            outputBuffer = createBuffer(session.context(), CL_MEM_WRITE_ONLY, error);

            String source = """
                    __kernel void fiximizer_vector_add(
                        __global const float* a,
                        __global const float* b,
                        __global float* output
                    ) {
                        size_t index = get_global_id(0);
                        output[index] = a[index] + b[index];
                    }
                    """;

            error.put(0, 0);
            program = CL10.clCreateProgramWithSource(session.context(), source, error);
            checkHandle(program, error.get(0), "OpenCL program creation");

            PointerBuffer deviceList = stack.mallocPointer(1);
            deviceList.put(session.devicePointer()).flip();
            int buildResult = CL10.clBuildProgram(program, deviceList, (CharSequence) null, null, 0L);
            if (buildResult != CL_SUCCESS) {
                throw new IllegalStateException(
                        "OpenCL kernel compilation failed (" + buildResult + "): "
                                + readBuildLog(program, session.devicePointer(), stack)
                );
            }

            error.put(0, 0);
            kernel = CL10.clCreateKernel(program, "fiximizer_vector_add", error);
            checkHandle(kernel, error.get(0), "OpenCL kernel creation");

            PointerBuffer arg = stack.mallocPointer(1);
            arg.put(0, inputABuffer);
            check(CL10.clSetKernelArg(kernel, 0, arg), "Set kernel argument A");
            arg.put(0, inputBBuffer);
            check(CL10.clSetKernelArg(kernel, 1, arg), "Set kernel argument B");
            arg.put(0, outputBuffer);
            check(CL10.clSetKernelArg(kernel, 2, arg), "Set kernel argument output");

            long uploadStart = System.nanoTime();
            inputA.position(0).limit(ELEMENT_COUNT);
            inputB.position(0).limit(ELEMENT_COUNT);
            check(CL10.clEnqueueWriteBuffer(
                    session.queue(), inputABuffer, true, 0L, inputA, null, null
            ), "Upload input A");
            check(CL10.clEnqueueWriteBuffer(
                    session.queue(), inputBBuffer, true, 0L, inputB, null, null
            ), "Upload input B");
            check(CL10.clFinish(session.queue()), "Finish input transfers");
            uploadMillis = elapsedMillis(uploadStart);

            PointerBuffer globalSize = stack.mallocPointer(1);
            PointerBuffer localSize = stack.mallocPointer(1);
            globalSize.put(0, ELEMENT_COUNT);
            localSize.put(0, 256L);

            // Warm up driver/JIT compilation and kernel scheduling outside the timed section.
            for (int pass = 0; pass < 2; pass++) {
                enqueueVectorAdd(session.queue(), kernel, globalSize, localSize);
            }
            check(CL10.clFinish(session.queue()), "Finish GPU warm-up kernels");

            long kernelStart = System.nanoTime();
            for (int pass = 0; pass < 5; pass++) {
                enqueueVectorAdd(session.queue(), kernel, globalSize, localSize);
            }
            check(CL10.clFinish(session.queue()), "Finish GPU kernels");
            kernelMillis = elapsedMillis(kernelStart) / 5.0;

            long downloadStart = System.nanoTime();
            gpuOutput.position(0).limit(ELEMENT_COUNT);
            check(CL10.clEnqueueReadBuffer(
                    session.queue(), outputBuffer, true, 0L, gpuOutput, null, null
            ), "Read back GPU result");
            check(CL10.clFinish(session.queue()), "Finish result download");
            downloadMillis = elapsedMillis(downloadStart);

            for (int i = 0; i < ELEMENT_COUNT; i++) {
                float expected = cpuOutput.get(i);
                float actual = gpuOutput.get(i);
                if (!Float.isFinite(actual) || Math.abs(expected - actual) > 0.0001f) {
                    throw new IllegalStateException(
                            "GPU result validation failed at element " + i
                                    + " (expected " + expected + ", got " + actual + ")."
                    );
                }
            }
        } finally {
            if (kernel != 0L) CL10.clReleaseKernel(kernel);
            if (program != 0L) CL10.clReleaseProgram(program);
            if (outputBuffer != 0L) CL10.clReleaseMemObject(outputBuffer);
            if (inputBBuffer != 0L) CL10.clReleaseMemObject(inputBBuffer);
            if (inputABuffer != 0L) CL10.clReleaseMemObject(inputABuffer);

            if (gpuOutput != null) memFree(gpuOutput);
            if (cpuOutput != null) memFree(cpuOutput);
            if (inputB != null) memFree(inputB);
            if (inputA != null) memFree(inputA);
        }

        double gpuTotalMillis = uploadMillis + kernelMillis + downloadMillis;
        double speedup = gpuTotalMillis <= 0.0 ? 0.0 : cpuMillis / gpuTotalMillis;
        String message = speedup > 1.05
                ? "GPU result verified; end-to-end test is faster than the CPU baseline."
                : "GPU result verified, but transfer + kernel time did not beat the CPU baseline.";

        GpuBenchmarkResult result = new GpuBenchmarkResult(
                true,
                device.name(),
                BUFFER_BYTES * 3L,
                uploadMillis,
                kernelMillis,
                downloadMillis,
                gpuTotalMillis,
                cpuMillis,
                speedup,
                message,
                System.currentTimeMillis()
        );

        writeReport(gameDirectory, result);
        Fiximizer.LOGGER.info(
                "OpenCL self-test passed on {}: upload={}ms kernel={}ms download={}ms total={}ms CPU={}ms speedup={}x",
                device.name(),
                format(uploadMillis),
                format(kernelMillis),
                format(downloadMillis),
                format(gpuTotalMillis),
                format(cpuMillis),
                format(speedup)
        );
        return result;
    }

    private static void runCpuVectorAdd(
            FloatBuffer inputA,
            FloatBuffer inputB,
            FloatBuffer output
    ) {
        for (int i = 0; i < ELEMENT_COUNT; i++) {
            output.put(i, inputA.get(i) + inputB.get(i));
        }
    }

    private static void enqueueVectorAdd(
            long queue,
            long kernel,
            PointerBuffer globalSize,
            PointerBuffer localSize
    ) {
        check(CL10.clEnqueueNDRangeKernel(
                queue,
                kernel,
                1,
                null,
                globalSize,
                localSize,
                null,
                null
        ), "Enqueue vector-add kernel");
    }

    private static long createBuffer(long context, long flags, java.nio.IntBuffer error) {
        error.put(0, 0);
        long buffer = CL10.clCreateBuffer(context, flags, BUFFER_BYTES, error);
        checkHandle(buffer, error.get(0), "GPU buffer allocation");
        return buffer;
    }

    private static String readBuildLog(long program, long device, MemoryStack stack) {
        PointerBuffer size = stack.mallocPointer(1);
        if (CL10.clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG,
                (ByteBuffer) null, size) != CL_SUCCESS) {
            return "Build log is unavailable";
        }

        long length = size.get(0);
        if (length <= 1L || length > 16_384L) {
            return "Build log size was not usable";
        }

        ByteBuffer buffer = stack.malloc((int) length);
        if (CL10.clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG,
                buffer, null) != CL_SUCCESS) {
            return "Unable to read build log";
        }
        return memUTF8(buffer);
    }

    private static void checkHandle(long handle, int error, String operation) {
        if (handle == 0L || error != CL_SUCCESS) {
            throw new IllegalStateException(operation + " failed (" + error + ").");
        }
    }

    private static void check(int error, String operation) {
        if (error != CL_SUCCESS) {
            throw new IllegalStateException(operation + " failed (" + error + ").");
        }
    }

    private static double elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(100L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GPU test was interrupted.", exception);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private static void writeReport(Path gameDirectory, GpuBenchmarkResult result) throws IOException {
        Files.createDirectories(gameDirectory);
        Path report = gameDirectory.resolve("fiximizer-gpu-benchmark.txt");

        String body = """
                Fiximizer OpenCL GPU benchmark
                ==============================

                Device: %s
                Verified output: %s
                GPU buffers allocated: %s
                Upload time: %s ms
                Kernel execution: %s ms
                Download time: %s ms
                GPU end-to-end: %s ms
                CPU baseline: %s ms
                CPU / GPU end-to-end ratio: %s x

                %s

                This test uses a 1,048,576-element vector-add workload. It proves
                that OpenCL buffer allocation, host-to-device transfer, kernel
                execution, device-to-host transfer and result validation work.
                It does not imply that every Minecraft workload benefits from
                GPU offload. The full transfer cost is included in the ratio.
                """.formatted(
                result.deviceName(),
                result.passed(),
                formatBytes(result.testedBufferBytes()),
                format(result.uploadMillis()),
                format(result.kernelMillis()),
                format(result.downloadMillis()),
                format(result.gpuTotalMillis()),
                format(result.cpuMillis()),
                format(result.endToEndSpeedup()),
                result.message()
        );

        Files.writeString(report, body, StandardCharsets.UTF_8);
    }

    private static String formatBytes(long bytes) {
        return String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0));
    }

    public static boolean running() {
        return RUNNING.get();
    }

    public static GpuBenchmarkResult latest() {
        return latest;
    }
}

package com.makakaiglitch.fiximizer.gpu;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opencl.CL10.CL_CONTEXT_PLATFORM;
import static org.lwjgl.opencl.CL10.CL_QUEUE_PROFILING_ENABLE;
import static org.lwjgl.opencl.CL10.CL_SUCCESS;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Small, explicitly owned OpenCL context + command queue. All native resources
 * are released deterministically by close().
 */
final class OpenClSession implements AutoCloseable {
    private final long context;
    private final long queue;
    private final OpenClService.DeviceHandle device;

    private OpenClSession(
            long context,
            long queue,
            OpenClService.DeviceHandle device
    ) {
        this.context = context;
        this.queue = queue;
        this.device = device;
    }

    static OpenClSession open() {
        OpenClService.DeviceHandle device = OpenClService.primaryDevice();
        if (device == null) {
            throw new IllegalStateException("No OpenCL GPU device is available.");
        }

        try (MemoryStack stack = stackPush()) {
            PointerBuffer properties = stack.mallocPointer(3);
            properties.put((long) CL_CONTEXT_PLATFORM)
                    .put(device.platformPointer())
                    .put(0L)
                    .flip();

            PointerBuffer devices = stack.mallocPointer(1);
            devices.put(device.devicePointer()).flip();

            var error = stack.mallocInt(1);
            long context = CL10.clCreateContext(properties, devices, null, 0L, error);
            int contextError = error.get(0);
            if (context == 0L || contextError != CL_SUCCESS) {
                throw new IllegalStateException("OpenCL context creation failed: " + contextError);
            }

            error.put(0, 0);
            long queue = CL10.clCreateCommandQueue(
                    context,
                    device.devicePointer(),
                    CL_QUEUE_PROFILING_ENABLE,
                    error
            );
            int queueError = error.get(0);
            if (queue == 0L || queueError != CL_SUCCESS) {
                CL10.clReleaseContext(context);
                throw new IllegalStateException("OpenCL command queue creation failed: " + queueError);
            }

            return new OpenClSession(context, queue, device);
        }
    }

    long context() {
        return context;
    }

    long queue() {
        return queue;
    }

    long devicePointer() {
        return device.devicePointer();
    }

    OpenClService.DeviceHandle device() {
        return device;
    }

    @Override
    public void close() {
        CL10.clFinish(queue);
        CL10.clReleaseCommandQueue(queue);
        CL10.clReleaseContext(context);
    }
}

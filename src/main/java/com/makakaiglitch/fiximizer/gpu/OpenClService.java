package com.makakaiglitch.fiximizer.gpu;

import com.makakaiglitch.fiximizer.Fiximizer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opencl.CL10.CL_DEVICE_GLOBAL_MEM_SIZE;
import static org.lwjgl.opencl.CL10.CL_DEVICE_NAME;
import static org.lwjgl.opencl.CL10.CL_DEVICE_TYPE_GPU;
import static org.lwjgl.opencl.CL10.CL_DEVICE_VENDOR;
import static org.lwjgl.opencl.CL10.CL_DEVICE_VERSION;
import static org.lwjgl.opencl.CL10.CL_PLATFORM_NAME;
import static org.lwjgl.opencl.CL10.CL_SUCCESS;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memUTF8;

public final class OpenClService {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile boolean checked;
    private static volatile List<OpenClDeviceInfo> devices = List.of();
    private static volatile String status = "Not checked";

    private OpenClService() {
    }

    public static void probeAsync() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(OpenClService::probe, "Fiximizer-OpenCL-Probe");
        thread.setDaemon(true);
        thread.start();
    }

    private static void probe() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer platformCount = stack.mallocInt(1);
            int result = CL10.clGetPlatformIDs((PointerBuffer) null, platformCount);

            if (result != CL_SUCCESS) {
                setUnavailable("OpenCL platform query failed (" + result + ")");
                return;
            }

            int numberOfPlatforms = platformCount.get(0);
            if (numberOfPlatforms <= 0) {
                setUnavailable("No OpenCL platforms found");
                return;
            }

            PointerBuffer platforms = stack.mallocPointer(numberOfPlatforms);
            result = CL10.clGetPlatformIDs(platforms, (IntBuffer) null);
            if (result != CL_SUCCESS) {
                setUnavailable("Unable to enumerate OpenCL platforms (" + result + ")");
                return;
            }

            List<OpenClDeviceInfo> found = new ArrayList<>();
            for (int platformIndex = 0; platformIndex < numberOfPlatforms; platformIndex++) {
                long platform = platforms.get(platformIndex);
                String platformName = readPlatformString(stack, platform, CL_PLATFORM_NAME);

                IntBuffer deviceCount = stack.mallocInt(1);
                int deviceResult = CL10.clGetDeviceIDs(
                        platform,
                        CL_DEVICE_TYPE_GPU,
                        (PointerBuffer) null,
                        deviceCount
                );

                if (deviceResult == -1) {
                    continue;
                }
                if (deviceResult != CL_SUCCESS) {
                    continue;
                }

                int numberOfDevices = deviceCount.get(0);
                if (numberOfDevices <= 0) {
                    continue;
                }

                PointerBuffer platformDevices = stack.mallocPointer(numberOfDevices);
                deviceResult = CL10.clGetDeviceIDs(
                        platform,
                        CL_DEVICE_TYPE_GPU,
                        platformDevices,
                        (IntBuffer) null
                );
                if (deviceResult != CL_SUCCESS) {
                    continue;
                }

                for (int deviceIndex = 0; deviceIndex < numberOfDevices; deviceIndex++) {
                    long device = platformDevices.get(deviceIndex);
                    String name = readDeviceString(stack, device, CL_DEVICE_NAME);
                    String vendor = readDeviceString(stack, device, CL_DEVICE_VENDOR);
                    String version = readDeviceString(stack, device, CL_DEVICE_VERSION);

                    LongBuffer memory = stack.mallocLong(1);
                    long memoryBytes = -1L;
                    if (CL10.clGetDeviceInfo(device, CL_DEVICE_GLOBAL_MEM_SIZE, memory, null) == CL_SUCCESS) {
                        memoryBytes = memory.get(0);
                    }

                    found.add(new OpenClDeviceInfo(
                            platformName,
                            name,
                            vendor,
                            version,
                            memoryBytes
                    ));
                }
            }

            devices = List.copyOf(found);
            checked = true;
            status = found.isEmpty()
                    ? "OpenCL runtime found, but no GPU device is exposed"
                    : "OpenCL GPU device(s): " + found.size();

            Fiximizer.LOGGER.info("{}", status);
        } catch (LinkageError | RuntimeException exception) {
            setUnavailable("OpenCL unavailable: " + safeMessage(exception));
            Fiximizer.LOGGER.info("OpenCL probe skipped: {}", safeMessage(exception));
        }
    }

    private static String readPlatformString(MemoryStack stack, long platform, int parameter) {
        ByteBuffer buffer = stack.malloc(512);
        int result = CL10.clGetPlatformInfo(platform, parameter, buffer, null);
        return result == CL_SUCCESS ? memUTF8(buffer) : "Unknown OpenCL platform";
    }

    private static String readDeviceString(MemoryStack stack, long device, int parameter) {
        ByteBuffer buffer = stack.malloc(512);
        int result = CL10.clGetDeviceInfo(device, parameter, buffer, null);
        return result == CL_SUCCESS ? memUTF8(buffer) : "Unknown OpenCL device";
    }

    private static void setUnavailable(String reason) {
        checked = true;
        devices = List.of();
        status = reason;
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return value.length() > 100 ? value.substring(0, 100) : value;
    }

    public static boolean checked() {
        return checked;
    }

    public static List<OpenClDeviceInfo> devices() {
        return devices;
    }

    public static String status() {
        return status;
    }
}

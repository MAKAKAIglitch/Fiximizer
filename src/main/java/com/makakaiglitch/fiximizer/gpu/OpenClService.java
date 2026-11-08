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
import static org.lwjgl.opencl.CL10.CL_DEVICE_MAX_MEM_ALLOC_SIZE;
import static org.lwjgl.opencl.CL10.CL_DEVICE_NAME;
import static org.lwjgl.opencl.CL10.CL_DEVICE_TYPE_GPU;
import static org.lwjgl.opencl.CL10.CL_DEVICE_VENDOR;
import static org.lwjgl.opencl.CL10.CL_DEVICE_VERSION;
import static org.lwjgl.opencl.CL10.CL_PLATFORM_NAME;
import static org.lwjgl.opencl.CL10.CL_SUCCESS;
import static org.lwjgl.system.MemoryStack.stackPush;

public final class OpenClService {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile boolean checked;
    private static volatile List<OpenClDeviceInfo> devices = List.of();
    private static volatile DeviceHandle primaryDevice;
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
            DeviceHandle firstDevice = null;

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

                if (deviceResult == -1 || deviceResult != CL_SUCCESS) {
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

                    long memoryBytes = readDeviceLong(stack, device, CL_DEVICE_GLOBAL_MEM_SIZE);
                    long maximumAllocationBytes = readDeviceLong(stack, device, CL_DEVICE_MAX_MEM_ALLOC_SIZE);

                    found.add(new OpenClDeviceInfo(
                            platformName,
                            name,
                            vendor,
                            version,
                            memoryBytes
                    ));

                    if (firstDevice == null) {
                        firstDevice = new DeviceHandle(
                                platform,
                                device,
                                platformName,
                                name,
                                vendor,
                                version,
                                memoryBytes,
                                maximumAllocationBytes
                        );
                    }
                }
            }

            devices = List.copyOf(found);
            primaryDevice = firstDevice;
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

    private static long readDeviceLong(MemoryStack stack, long device, int parameter) {
        LongBuffer value = stack.mallocLong(1);
        return CL10.clGetDeviceInfo(device, parameter, value, null) == CL_SUCCESS
                ? value.get(0)
                : -1L;
    }

    private static String readPlatformString(MemoryStack stack, long platform, int parameter) {
        ByteBuffer buffer = stack.malloc(512);
        PointerBuffer writtenBytes = stack.mallocPointer(1);
        int result = CL10.clGetPlatformInfo(platform, parameter, buffer, writtenBytes);
        if (result != CL_SUCCESS) {
            return "Unknown OpenCL platform";
        }

        long byteLength = writtenBytes.get(0);
        if (byteLength <= 0L || byteLength > buffer.capacity()) {
            return "Unknown OpenCL platform";
        }

        String value = OpenClText.decode(buffer, (int) byteLength);
        return value.isBlank() ? "Unknown OpenCL platform" : value;
    }

    private static String readDeviceString(MemoryStack stack, long device, int parameter) {
        ByteBuffer buffer = stack.malloc(512);
        PointerBuffer writtenBytes = stack.mallocPointer(1);
        int result = CL10.clGetDeviceInfo(device, parameter, buffer, writtenBytes);
        if (result != CL_SUCCESS) {
            return "Unknown OpenCL device";
        }

        long byteLength = writtenBytes.get(0);
        if (byteLength <= 0L || byteLength > buffer.capacity()) {
            return "Unknown OpenCL device";
        }

        String value = OpenClText.decode(buffer, (int) byteLength);
        return value.isBlank() ? "Unknown OpenCL device" : value;
    }

    private static void setUnavailable(String reason) {
        checked = true;
        devices = List.of();
        primaryDevice = null;
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

    public static DeviceHandle primaryDevice() {
        return primaryDevice;
    }

    public static String status() {
        return status;
    }

    public record DeviceHandle(
            long platformPointer,
            long devicePointer,
            String platform,
            String name,
            String vendor,
            String version,
            long globalMemoryBytes,
            long maximumAllocationBytes
    ) {
    }
}

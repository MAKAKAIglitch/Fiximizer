package com.makakaiglitch.fiximizer.gpu;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.scheduler.MemoryPressureController;
import org.lwjgl.opencl.CL10;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded VRAM store for disposable, explicitly managed Fiximizer cache blocks.
 *
 * A successful store does not free the caller's source buffer: the owning
 * integration must release or reuse that source only after it has safely
 * recorded the returned handle. This API cannot page arbitrary JVM objects.
 */
public final class GpuSpillService {
    private static final Map<Long, Entry> ENTRIES = new LinkedHashMap<>(16, 0.75f, true);

    private static OpenClSession session;
    private static long nextHandle = 1L;
    private static long storedBytes;
    private static boolean shutdownHookRegistered;

    private GpuSpillService() {
    }

    /**
     * Stores one disposable block in VRAM. Only works after a successful
     * OpenCL self-test and while Fiximizer's memory guard is active.
     */
    public static synchronized Optional<SpillHandle> store(ByteBuffer source) {
        if (source == null || !source.hasRemaining()
                || !FiximizerConfig.ENABLE_GPU_SPILL.get()
                || !MemoryPressureController.active()
                || !GpuBenchmarkService.latest().passed()) {
            return Optional.empty();
        }

        long byteCount = source.remaining();
        long budget = currentBudgetBytes();
        if (budget <= 0L || byteCount > budget || byteCount > Integer.MAX_VALUE) {
            return Optional.empty();
        }

        ByteBuffer transfer = null;
        boolean allocatedTransfer = false;
        long memoryObject = 0L;
        try {
            ensureSession();
            evictUntilFits(byteCount, budget);

            if (storedBytes + byteCount > budget) {
                return Optional.empty();
            }

            memoryObject = CL10.clCreateBuffer(
                    session.context(),
                    CL10.CL_MEM_READ_WRITE,
                    byteCount,
                    (java.nio.IntBuffer) null
            );
            if (memoryObject == 0L) {
                return Optional.empty();
            }

            ByteBuffer input = source.duplicate();
            if (input.isDirect()) {
                transfer = input;
            } else {
                transfer = MemoryUtil.memAlloc((int) byteCount);
                allocatedTransfer = true;
                transfer.put(input).flip();
            }

            int writeResult = CL10.clEnqueueWriteBuffer(
                    session.queue(),
                    memoryObject,
                    true,
                    0L,
                    transfer,
                    null,
                    null
            );
            if (writeResult != CL10.CL_SUCCESS
                    || CL10.clFinish(session.queue()) != CL10.CL_SUCCESS) {
                CL10.clReleaseMemObject(memoryObject);
                return Optional.empty();
            }

            long id = nextHandle++;
            ENTRIES.put(id, new Entry(memoryObject, byteCount));
            storedBytes += byteCount;
            memoryObject = 0L;

            return Optional.of(new SpillHandle(id, byteCount));
        } catch (LinkageError | RuntimeException exception) {
            return Optional.empty();
        } finally {
            if (allocatedTransfer && transfer != null) {
                MemoryUtil.memFree(transfer);
            }
            if (memoryObject != 0L) {
                CL10.clReleaseMemObject(memoryObject);
            }
        }
    }

    /**
     * Restores one block into a direct ByteBuffer and removes it from VRAM.
     * The returned buffer is owned by the caller and must be freed with
     * MemoryUtil.memFree after use.
     */
    public static synchronized Optional<ByteBuffer> restore(SpillHandle handle) {
        if (handle == null) {
            return Optional.empty();
        }

        Entry entry = ENTRIES.get(handle.id());
        if (entry == null || entry.bytes() != handle.bytes()) {
            return Optional.empty();
        }

        ByteBuffer restored = null;
        try {
            if (session == null) {
                ensureSession();
            }

            restored = MemoryUtil.memAlloc(Math.toIntExact(entry.bytes()));
            int readResult = CL10.clEnqueueReadBuffer(
                    session.queue(),
                    entry.memoryObject(),
                    true,
                    0L,
                    restored,
                    null,
                    null
            );
            if (readResult != CL10.CL_SUCCESS
                    || CL10.clFinish(session.queue()) != CL10.CL_SUCCESS) {
                MemoryUtil.memFree(restored);
                return Optional.empty();
            }

            restored.position(0).limit(Math.toIntExact(entry.bytes()));
            removeEntry(handle.id(), entry);
            return Optional.of(restored);
        } catch (LinkageError | RuntimeException exception) {
            if (restored != null) {
                MemoryUtil.memFree(restored);
            }
            return Optional.empty();
        }
    }

    public static synchronized boolean discard(SpillHandle handle) {
        if (handle == null) {
            return false;
        }
        Entry removed = ENTRIES.remove(handle.id());
        if (removed == null) {
            return false;
        }
        releaseEntry(removed);
        storedBytes -= removed.bytes();
        return true;
    }

    public static synchronized void clear() {
        for (Entry entry : ENTRIES.values()) {
            releaseEntry(entry);
        }
        ENTRIES.clear();
        storedBytes = 0L;
    }

    public static synchronized void shutdown() {
        clear();
        if (session != null) {
            session.close();
            session = null;
        }
    }

    public static synchronized long currentBudgetBytes() {
        if (!FiximizerConfig.ENABLE_GPU_SPILL.get()
                || !GpuBenchmarkService.latest().passed()
                || !MemoryPressureController.active()) {
            return 0L;
        }

        long configuredLimit = FiximizerConfig.MAX_GPU_SPILL_MIB.get()
                * 1024L * 1024L;

        return GpuTelemetryService.latest().stream()
                .filter(reading -> reading.vramTotalBytes() > 0L && reading.vramUsedBytes() >= 0L)
                .findFirst()
                .map(reading -> GpuMemoryBudget.safeSpillBudget(
                        reading.vramTotalBytes(),
                        reading.vramUsedBytes(),
                        configuredLimit
                ))
                .orElse(0L);
    }

    public static synchronized long storedBytes() {
        return storedBytes;
    }

    public static synchronized int blockCount() {
        return ENTRIES.size();
    }

    public static synchronized boolean available() {
        return currentBudgetBytes() > 0L;
    }

    private static void ensureSession() {
        if (session == null) {
            session = OpenClSession.open();
            if (!shutdownHookRegistered) {
                Runtime.getRuntime().addShutdownHook(
                        new Thread(GpuSpillService::shutdown, "Fiximizer-GPU-Spill-Shutdown")
                );
                shutdownHookRegistered = true;
            }
        }
    }

    private static void evictUntilFits(long requiredBytes, long budget) {
        Iterator<Map.Entry<Long, Entry>> iterator = ENTRIES.entrySet().iterator();
        while (storedBytes + requiredBytes > budget && iterator.hasNext()) {
            Map.Entry<Long, Entry> oldest = iterator.next();
            Entry entry = oldest.getValue();
            iterator.remove();
            releaseEntry(entry);
            storedBytes -= entry.bytes();
        }
    }

    private static void removeEntry(long id, Entry entry) {
        ENTRIES.remove(id);
        releaseEntry(entry);
        storedBytes -= entry.bytes();
    }

    private static void releaseEntry(Entry entry) {
        try {
            CL10.clReleaseMemObject(entry.memoryObject());
        } catch (LinkageError | RuntimeException ignored) {
            // Best-effort release if the device/driver was reset.
        }
    }

    private record Entry(long memoryObject, long bytes) {
    }

    public record SpillHandle(long id, long bytes) {
    }
}

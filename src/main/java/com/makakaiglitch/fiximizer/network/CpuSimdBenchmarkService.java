package com.makakaiglitch.fiximizer.network;

import com.makakaiglitch.fiximizer.Fiximizer;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manual microbenchmark for runtime-dispatched AVX2/FMA. It only processes
 * private arrays created by this service; it never touches Minecraft state.
 */
public final class CpuSimdBenchmarkService {
    private static final int SIMD_AVX2 = 1;
    private static final int SIMD_FMA = 1 << 1;
    private static final int ELEMENTS = 16_384;
    private static final int ITERATIONS = 512;
    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURED_ROUNDS = 9;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    public enum State {
        NOT_RUN("screen.fiximizer.simd_state_not_run"),
        RUNNING("screen.fiximizer.simd_state_running"),
        COMPLETE("screen.fiximizer.simd_state_complete"),
        UNAVAILABLE("screen.fiximizer.simd_state_unavailable"),
        FAILED("screen.fiximizer.simd_state_failed");

        private final String translationKey;

        State(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    public record Snapshot(
            State state,
            int featureFlags,
            String pathLabel,
            long scalarMedianNanos,
            long dispatchedMedianNanos,
            double speedup,
            boolean correct,
            String details
    ) {
        public boolean avx2Supported() {
            return (featureFlags & SIMD_AVX2) != 0;
        }

        public boolean fmaSupported() {
            return (featureFlags & SIMD_FMA) != 0;
        }
    }

    private static final Snapshot NOT_RUN = new Snapshot(
            State.NOT_RUN, 0, "", -1L, -1L, 0.0d, false, "");
    private static volatile Snapshot latest = NOT_RUN;

    private CpuSimdBenchmarkService() {
    }

    public static boolean runAsync() {
        if (!RUNNING.compareAndSet(false, true)) return false;
        latest = new Snapshot(State.RUNNING, 0, "", -1L, -1L, 0.0d, false, "");

        Thread worker = new Thread(CpuSimdBenchmarkService::runBenchmark,
                "Fiximizer-CPU-SIMD-Benchmark");
        worker.setDaemon(true);
        try {
            worker.start();
            return true;
        } catch (RuntimeException | Error failure) {
            latest = new Snapshot(State.FAILED, 0, "", -1L, -1L, 0.0d, false,
                    safeMessage(failure));
            RUNNING.set(false);
            return false;
        }
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static Snapshot snapshot() {
        return latest;
    }

    private static void runBenchmark() {
        try {
            RustNetworkBackend backend = RustNetworkBackend.load();
            if (backend.simdAbiVersion() != 1) {
                latest = unavailable("The bundled native library does not expose the SIMD test ABI.");
                return;
            }

            int featureFlags = backend.simdFeatureFlags();
            if (featureFlags < 0) {
                latest = unavailable("The native library could not report CPU feature support.");
                return;
            }

            float[][] inputs = createInputs();
            float[] scalarOutput = new float[ELEMENTS];
            float[] dispatchedOutput = new float[ELEMENTS];

            for (int i = 0; i < WARMUP_ROUNDS; i++) {
                if (!backend.computeSimdScalar(inputs[0], inputs[1], inputs[2],
                        scalarOutput, ITERATIONS)) {
                    latest = failed(featureFlags, "The scalar reference calculation failed.");
                    return;
                }
                int path = backend.computeSimdAuto(inputs[0], inputs[1], inputs[2],
                        dispatchedOutput, ITERATIONS);
                if (path < 0 || !matches(scalarOutput, dispatchedOutput)) {
                    latest = failed(featureFlags, "The dispatched calculation failed validation.");
                    return;
                }
            }

            long[] scalarRuns = new long[MEASURED_ROUNDS];
            long[] dispatchedRuns = new long[MEASURED_ROUNDS];
            int selectedPath = -1;

            for (int i = 0; i < MEASURED_ROUNDS; i++) {
                if ((i & 1) == 0) {
                    scalarRuns[i] = timeScalar(backend, inputs, scalarOutput);
                    long start = System.nanoTime();
                    selectedPath = backend.computeSimdAuto(
                            inputs[0], inputs[1], inputs[2], dispatchedOutput, ITERATIONS);
                    dispatchedRuns[i] = System.nanoTime() - start;
                } else {
                    long start = System.nanoTime();
                    selectedPath = backend.computeSimdAuto(
                            inputs[0], inputs[1], inputs[2], dispatchedOutput, ITERATIONS);
                    dispatchedRuns[i] = System.nanoTime() - start;
                    scalarRuns[i] = timeScalar(backend, inputs, scalarOutput);
                }

                if (selectedPath < 0 || !matches(scalarOutput, dispatchedOutput)) {
                    latest = failed(featureFlags, "The dispatched calculation failed validation.");
                    return;
                }
            }

            long scalarMedian = median(scalarRuns);
            long dispatchedMedian = median(dispatchedRuns);
            double speedup = dispatchedMedian > 0L
                    ? scalarMedian / (double) dispatchedMedian
                    : 0.0d;
            String pathLabel = switch (selectedPath) {
                case 2 -> "AVX2 + FMA";
                case 1 -> "AVX2 (multiply + add)";
                default -> "Portable scalar fallback";
            };

            latest = new Snapshot(State.COMPLETE, featureFlags, pathLabel,
                    scalarMedian, dispatchedMedian, speedup, true, "");
            Fiximizer.LOGGER.info(
                    "CPU SIMD microbenchmark: path={}, AVX2={}, FMA={}, scalarMedian={} ns, dispatchedMedian={} ns, speedup={}x",
                    pathLabel, (featureFlags & SIMD_AVX2) != 0, (featureFlags & SIMD_FMA) != 0,
                    scalarMedian, dispatchedMedian, String.format(java.util.Locale.ROOT, "%.3f", speedup));
        } catch (IOException | RuntimeException | LinkageError failure) {
            latest = unavailable(safeMessage(failure));
            Fiximizer.LOGGER.info("CPU SIMD microbenchmark unavailable: {}", safeMessage(failure));
        } finally {
            RUNNING.set(false);
        }
    }

    private static float[][] createInputs() {
        float[] initial = new float[ELEMENTS];
        float[] multiplier = new float[ELEMENTS];
        float[] addend = new float[ELEMENTS];
        for (int i = 0; i < ELEMENTS; i++) {
            initial[i] = 0.5f + (i % 101) * 0.001f;
            multiplier[i] = 0.9999f + (i % 23) * 0.000001f;
            addend[i] = 0.0001f + (i % 13) * 0.000002f;
        }
        return new float[][]{initial, multiplier, addend};
    }

    private static long timeScalar(RustNetworkBackend backend, float[][] inputs,
                                   float[] output) {
        long start = System.nanoTime();
        if (!backend.computeSimdScalar(inputs[0], inputs[1], inputs[2], output, ITERATIONS)) {
            throw new IllegalStateException("The scalar reference calculation failed.");
        }
        return System.nanoTime() - start;
    }

    private static boolean matches(float[] expected, float[] actual) {
        if (expected.length != actual.length) return false;
        for (int i = 0; i < expected.length; i++) {
            float left = expected[i];
            float right = actual[i];
            if (!Float.isFinite(left) || !Float.isFinite(right)) return false;
            float tolerance = 1.0e-4f * Math.max(1.0f, Math.abs(left));
            if (Math.abs(left - right) > tolerance) return false;
        }
        return true;
    }

    private static long median(long[] values) {
        long[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static Snapshot failed(int flags, String details) {
        return new Snapshot(State.FAILED, flags, "", -1L, -1L, 0.0d, false, details);
    }

    private static Snapshot unavailable(String details) {
        return new Snapshot(State.UNAVAILABLE, 0, "", -1L, -1L, 0.0d, false, details);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}

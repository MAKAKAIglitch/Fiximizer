package com.makakaiglitch.fiximizer.network;

import com.sun.jna.Library;
import com.sun.jna.Native;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Optional Rust batch-statistics backend loaded from a platform-specific jar resource. */
final class RustNetworkBackend {
    private static final int EXPECTED_ABI = 1;

    private final NativeApi api;

    private RustNetworkBackend(NativeApi api) {
        this.api = api;
    }

    static RustNetworkBackend load() throws IOException {
        String platform = platformId();
        String filename = libraryFilename();
        String resource = "/assets/fiximizer/native/" + platform + "/" + filename;

        try (InputStream input = RustNetworkBackend.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("No bundled Rust backend for " + platform);

            Path extracted = Files.createTempFile("fiximizer-network-", nativeSuffix());
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
            extracted.toFile().deleteOnExit();

            NativeApi api = Native.load(extracted.toAbsolutePath().toString(), NativeApi.class);
            if (api.fiximizer_network_backend_abi() != EXPECTED_ABI) {
                throw new IOException("Rust backend ABI mismatch");
            }
            return new RustNetworkBackend(api);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError error) {
            throw new IOException("Unable to load the native Rust backend", error);
        }
    }

    boolean summarize(long[] samples, long[] output) {
        if (!NetworkStatistics.valid(samples, output)) return false;
        try {
            return api.fiximizer_network_summarize(samples, samples.length, output) == 0;
        } catch (LinkageError | RuntimeException exception) {
            return false;
        }
    }

    int simdAbiVersion() {
        try {
            return api.fiximizer_simd_backend_abi();
        } catch (LinkageError | RuntimeException exception) {
            return -1;
        }
    }

    int simdFeatureFlags() {
        try {
            return api.fiximizer_simd_feature_flags();
        } catch (LinkageError | RuntimeException exception) {
            return -1;
        }
    }

    boolean computeSimdScalar(float[] initial, float[] multiplier, float[] addend,
                              float[] output, int iterations) {
        if (!validSimdArrays(initial, multiplier, addend, output, iterations)) return false;
        try {
            return api.fiximizer_simd_compute_scalar(
                    initial, multiplier, addend, output, initial.length, iterations) == 0;
        } catch (LinkageError | RuntimeException exception) {
            return false;
        }
    }

    int computeSimdAuto(float[] initial, float[] multiplier, float[] addend,
                        float[] output, int iterations) {
        if (!validSimdArrays(initial, multiplier, addend, output, iterations)) return -1;
        try {
            return api.fiximizer_simd_compute_auto(
                    initial, multiplier, addend, output, initial.length, iterations);
        } catch (LinkageError | RuntimeException exception) {
            return -1;
        }
    }

    private static boolean validSimdArrays(float[] initial, float[] multiplier,
                                           float[] addend, float[] output, int iterations) {
        return initial != null
                && multiplier != null
                && addend != null
                && output != null
                && initial.length > 0
                && initial.length == multiplier.length
                && initial.length == addend.length
                && initial.length == output.length
                && iterations > 0;
    }

    private static String platformId() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (os.contains("win") && x64) return "windows-x86_64";
        if (os.contains("linux") && x64) return "linux-x86_64";
        if (os.contains("mac") && arm64) return "macos-aarch64";
        if (os.contains("mac") && x64) return "macos-x86_64";
        throw new IOException("Unsupported native platform: " + os + " / " + arch);
    }

    private static String libraryFilename() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "fiximizer_network_native.dll";
        if (os.contains("mac")) return "libfiximizer_network_native.dylib";
        return "libfiximizer_network_native.so";
    }

    private static String nativeSuffix() {
        String filename = libraryFilename();
        return filename.substring(filename.lastIndexOf('.'));
    }

    private interface NativeApi extends Library {
        int fiximizer_network_backend_abi();
        int fiximizer_network_summarize(long[] samples, int length, long[] output);
        int fiximizer_simd_backend_abi();
        int fiximizer_simd_feature_flags();
        int fiximizer_simd_compute_scalar(float[] initial, float[] multiplier, float[] addend,
                                          float[] output, int length, int iterations);
        int fiximizer_simd_compute_auto(float[] initial, float[] multiplier, float[] addend,
                                        float[] output, int length, int iterations);
    }
}

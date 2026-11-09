package com.makakaiglitch.fiximizer.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Local, bounded persistence for benchmark runs and per-scenario baselines. */
public final class BenchmarkRepository {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String DIRECTORY = "logs/fiximizer/benchmarks";
    private static final int MAX_RUNS_RETAINED = 40;

    private BenchmarkRepository() {
    }

    public static Path directory(Path gameDirectory) {
        return gameDirectory.resolve(DIRECTORY);
    }

    public static Path saveRun(Path gameDirectory, BenchmarkRun run) throws IOException {
        requireValidRunObject(run);
        Path directory = directory(gameDirectory);
        Files.createDirectories(directory);
        String safeId = safeFilePart(run.id());
        Path output = directory.resolve("run-" + safeId + ".json");
        writeAtomically(output, GSON.toJson(run));
        pruneRuns(directory);
        return output;
    }

    public static Path saveBaseline(Path gameDirectory, BenchmarkRun run) throws IOException {
        requireValidRunObject(run);
        if (!run.valid()) {
            throw new IOException("An invalid or incomplete run cannot be saved as a baseline.");
        }
        Path directory = directory(gameDirectory);
        Files.createDirectories(directory);
        Path output = baselinePath(directory, run);
        writeAtomically(output, GSON.toJson(run));
        return output;
    }

    public static Optional<BenchmarkRun> loadBaseline(Path gameDirectory, BenchmarkRun candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        Path path = baselinePath(directory(gameDirectory), candidate);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            BenchmarkRun run = read(path);
            if (run.schemaVersion() != BenchmarkRun.CURRENT_SCHEMA_VERSION
                    || !run.valid()
                    || run.scenario() != candidate.scenario()
                    || !run.compatibilityFingerprint().equals(candidate.compatibilityFingerprint())) {
                return Optional.empty();
            }
            return Optional.of(run);
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Import a user-selected JSON run as a local baseline after schema validation. */
    public static Path importBaseline(Path sourceFile, Path gameDirectory) throws IOException {
        BenchmarkRun imported = read(sourceFile);
        requireValidRunObject(imported);
        if (!imported.valid()
                || imported.schemaVersion() != BenchmarkRun.CURRENT_SCHEMA_VERSION) {
            throw new IOException("The selected file is not a valid supported benchmark run.");
        }
        Path directory = directory(gameDirectory);
        Files.createDirectories(directory);
        Path output = baselinePath(directory, imported);
        writeAtomically(output, GSON.toJson(imported));
        return output;
    }

    /** Export a saved run or baseline to a user-selected destination file. */
    public static Path exportJson(Path sourceFile, Path destinationFile) throws IOException {
        BenchmarkRun run = read(sourceFile);
        requireValidRunObject(run);
        Path destination = destinationFile.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("The export destination has no parent directory.");
        }
        Files.createDirectories(parent);
        writeAtomically(destination, GSON.toJson(run));
        return destination;
    }

    public static BenchmarkRun read(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        BenchmarkRun run = GSON.fromJson(json, BenchmarkRun.class);
        requireValidRunObject(run);
        return run;
    }

    private static Path baselinePath(Path directory, BenchmarkRun run) {
        String scenario = run.scenario().name().toLowerCase(Locale.ROOT);
        String fingerprint = run.compatibilityFingerprint().substring(0, 16);
        return directory.resolve("baseline-" + scenario + "-" + fingerprint + ".json");
    }

    private static void requireValidRunObject(BenchmarkRun run) throws IOException {
        if (run == null || run.id() == null || run.id().isBlank()
                || run.scenario() == null || run.environment() == null
                || run.renderCall() == null || run.clientTick() == null
                || run.integratedServerTick() == null) {
            throw new IOException("The benchmark document is missing required fields.");
        }
    }

    private static String safeFilePart(String value) throws IOException {
        String safe = value.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) {
            throw new IOException("The benchmark run ID is not a valid file name.");
        }
        return safe;
    }

    private static void writeAtomically(Path output, String content) throws IOException {
        Path directory = output.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "benchmark-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void pruneRuns(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> runs = files
                    .filter(path -> path.getFileName().toString().startsWith("run-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingLong(BenchmarkRepository::modifiedAt).reversed())
                    .toList();
            for (int index = MAX_RUNS_RETAINED; index < runs.size(); index++) {
                try {
                    Files.deleteIfExists(runs.get(index));
                } catch (IOException ignored) {
                    // Retention is best effort and must not invalidate a completed run.
                }
            }
        } catch (IOException ignored) {
            // A retention scan failure does not invalidate a completed run.
        }
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }
}

package com.makakaiglitch.fiximizer.scheduler;

import com.makakaiglitch.fiximizer.Fiximizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Persists the user-selected profile separately from a temporary automatic
 * Memory Saver override. Automatic switching never overwrites user preference.
 */
public final class OptimizationProfileManager {
    private static final String CONFIG_FILE = "fiximizer-performance.properties";
    private static volatile OptimizationProfile selected = OptimizationProfile.SMOOTHNESS;
    private static volatile boolean memoryPressure;
    private static volatile String status = "Default profile";

    private OptimizationProfileManager() {
    }

    public static synchronized void load(Path configDirectory) {
        Path configFile = configDirectory.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(configFile)) {
            selected = OptimizationProfile.SMOOTHNESS;
            status = "Using default profile";
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configFile)) {
            properties.load(input);
            selected = OptimizationProfile.fromId(
                    properties.getProperty("profile", OptimizationProfile.SMOOTHNESS.id())
            );
            status = "Loaded " + selected.id();
        } catch (IOException | RuntimeException exception) {
            selected = OptimizationProfile.SMOOTHNESS;
            status = "Could not read profile settings: " + safeMessage(exception);
            Fiximizer.LOGGER.warn("Unable to read Fiximizer profile settings; using Smoothness.", exception);
        }
    }

    public static synchronized boolean select(
            OptimizationProfile profile,
            Path configDirectory
    ) {
        if (profile == null) {
            return false;
        }

        Path temporaryFile = null;
        try {
            Files.createDirectories(configDirectory);

            Properties properties = new Properties();
            properties.setProperty("profile", profile.id());

            temporaryFile = Files.createTempFile(configDirectory, "fiximizer-profile-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporaryFile)) {
                properties.store(output, "Fiximizer user-selected gameplay profile");
            }

            Path target = configDirectory.resolve(CONFIG_FILE);
            try {
                Files.move(
                        temporaryFile,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, target, StandardCopyOption.REPLACE_EXISTING);
            }

            selected = profile;
            status = memoryPressure
                    ? "Saved " + profile.id() + "; Memory Saver remains temporarily active"
                    : "Saved " + profile.id();
            return true;
        } catch (IOException | RuntimeException exception) {
            status = "Could not save profile: " + safeMessage(exception);
            Fiximizer.LOGGER.warn("Unable to save Fiximizer profile settings.", exception);
            return false;
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup for the temporary settings file.
                }
            }
        }
    }

    public static synchronized boolean cycle(Path configDirectory) {
        return select(selected.next(), configDirectory);
    }

    public static synchronized void setMemoryPressure(boolean active) {
        if (memoryPressure == active) {
            return;
        }
        memoryPressure = active;
        if (active) {
            status = "Automatic Memory Saver is temporarily overriding " + selected.id();
        } else {
            status = "Restored selected profile: " + selected.id();
        }
    }

    /** Returns the effective profile currently used by the scheduler and GPU policy. */
    public static OptimizationProfile current() {
        return memoryPressure ? OptimizationProfile.MEMORY_SAVER : selected;
    }

    public static OptimizationProfile selected() {
        return selected;
    }

    public static boolean memoryPressureOverride() {
        return memoryPressure;
    }

    public static String status() {
        return status;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 100 ? message.substring(0, 100) : message;
    }
}

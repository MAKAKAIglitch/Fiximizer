package com.makakaiglitch.fiximizer.compat;

import com.makakaiglitch.fiximizer.Fiximizer;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central gate for optional mod integrations.
 *
 * A future patch, mixin registration or adapter must be entered through
 * runForInstalledMod(), so absent mods are never referenced or modified.
 */
public final class ModIntegrationManager {
    private static volatile List<IntegrationStatus> statuses = List.of();
    private static volatile Set<String> activeModIds = Set.of();
    private static volatile boolean initialized;
    private static volatile boolean lastRefreshSucceeded;

    private ModIntegrationManager() {
    }

    /**
     * Called after the client reaches a stable title screen/world, or directly
     * when the user explicitly requests a refresh from the dashboard.
     *
     * A failed optional scan is isolated: it clears stale integration state,
     * records a failed result, and never prevents Minecraft from continuing.
     */
    public static synchronized void refresh() {
        initialized = false;
        lastRefreshSucceeded = false;
        statuses = List.of();
        activeModIds = Set.of();

        try {
            List<IntegrationStatus> detected = CompatibilityDetector.detect();
            Set<String> active = detected.stream()
                    .filter(IntegrationStatus::active)
                    .map(IntegrationStatus::detectedModId)
                    .collect(Collectors.toUnmodifiableSet());

            statuses = List.copyOf(detected);
            activeModIds = active;
            lastRefreshSucceeded = true;

            for (IntegrationStatus status : detected) {
                if (status.active()) {
                    Fiximizer.LOGGER.info(
                            "Optional integration enabled for detected mod: {} {}",
                            status.detectedModId(),
                            status.version()
                    );
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            // Do not keep old active IDs after a failed refresh: doing so could
            // incorrectly activate an adapter using stale compatibility data.
            statuses = List.of();
            activeModIds = Set.of();
            lastRefreshSucceeded = false;
            Fiximizer.LOGGER.warn(
                    "Optional mod compatibility detection failed; all optional integration gates remain closed.",
                    exception
            );
        } finally {
            initialized = true;
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean lastRefreshSucceeded() {
        return lastRefreshSucceeded;
    }

    /**
     * Execute an integration callback only when its exact mod ID is loaded
     * and the user has enabled that integration in Fiximizer's config.
     */
    public static boolean runForInstalledMod(String modId, java.util.function.Consumer<net.neoforged.fml.ModContainer> callback) {
        if (modId == null || !activeModIds.contains(modId)) {
            return false;
        }
        return CompatibilityDetector.runOnlyWhenInstalled(modId, callback);
    }

    public static boolean shouldActivate(boolean modInstalled, boolean integrationEnabled) {
        return modInstalled && integrationEnabled;
    }

    public static boolean isActive(String modId) {
        return modId != null && activeModIds.contains(modId);
    }

    public static List<IntegrationStatus> statuses() {
        return statuses;
    }

    public static int activeCount() {
        return activeModIds.size();
    }
}

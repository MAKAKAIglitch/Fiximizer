package com.makakaiglitch.fiximizer.compat;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import com.makakaiglitch.fiximizer.memory.MemoryOptimizer;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class CompatibilityDetector {
    private static final List<Target> TARGETS = List.of(
            new Target("Create", List.of("create"), FiximizerConfig.ENABLE_CREATE_INTEGRATION::get),
            new Target("Create Aeronautics", List.of(
                    "aeronautics_bundled",
                    "create_aeronautics",
                    "aeronautics"
            ), FiximizerConfig.ENABLE_AERONAUTICS_INTEGRATION::get),
            new Target("Sable", List.of("sable"), FiximizerConfig.ENABLE_SABLE_INTEGRATION::get),
            new Target("Distant Horizons", List.of("distanthorizons"), FiximizerConfig.ENABLE_DH_INTEGRATION::get),
            new Target("Voxy", List.of("voxy"), FiximizerConfig.ENABLE_VOXY_INTEGRATION::get)
    );

    private CompatibilityDetector() {
    }

    public static int targetCount() {
        return TARGETS.size();
    }

    public static List<IntegrationStatus> detect() {
        if (!FiximizerConfig.DETECT_COMPATIBLE_MODS.get()) {
            return List.of();
        }

        ModList modList = ModList.get();
        return TARGETS.stream()
                .map(target -> detectTarget(modList, target))
                .toList();
    }

    public static boolean isInstalled(String modId) {
        return modId != null && ModList.get().isLoaded(modId);
    }

    public static boolean runOnlyWhenInstalled(String modId, java.util.function.Consumer<ModContainer> action) {
        Optional<? extends ModContainer> container = ModList.get().getModContainerById(modId);
        if (container.isEmpty()) {
            return false;
        }

        action.accept(container.get());
        return true;
    }

    public static boolean integrationEnabled(String detectedModId) {
        if (detectedModId == null) {
            return false;
        }

        return TARGETS.stream()
                .filter(target -> target.modIds().contains(detectedModId))
                .findFirst()
                .map(target -> target.enabled().get())
                .orElse(false);
    }

    private static IntegrationStatus detectTarget(ModList modList, Target target) {
        for (String modId : target.modIds()) {
            Optional<? extends ModContainer> container = modList.getModContainerById(modId);
            if (container.isPresent()) {
                String version = container.get().getModInfo().getVersion().toString();
                return new IntegrationStatus(
                        target.displayName(),
                        MemoryOptimizer.canonicalize(modId),
                        MemoryOptimizer.canonicalize(version),
                        target.enabled().get()
                );
            }
        }

        return new IntegrationStatus(target.displayName(), null, null, target.enabled().get());
    }

    private record Target(
            String displayName,
            List<String> modIds,
            Supplier<Boolean> enabled
    ) {
    }
}

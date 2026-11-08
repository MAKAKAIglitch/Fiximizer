package com.makakaiglitch.fiximizer.compat;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import net.neoforged.fml.ModList;

import java.util.List;

public final class CompatibilityDetector {
    private static final List<Target> TARGETS = List.of(
            new Target("Create", List.of("create")),
            // The official NeoForge bundled distribution declares aeronautics_bundled.
            // Aliases keep detection tolerant of repackaged distributions.
            new Target("Create Aeronautics", List.of(
                    "aeronautics_bundled",
                    "create_aeronautics",
                    "aeronautics"
            )),
            new Target("Sable", List.of("sable")),
            new Target("Distant Horizons", List.of("distanthorizons")),
            new Target("Voxy", List.of("voxy"))
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
                .map(target -> new IntegrationStatus(
                        target.displayName(),
                        target.modIds().stream()
                                .filter(modList::isLoaded)
                                .findFirst()
                                .orElse(null)
                ))
                .toList();
    }

    private record Target(String displayName, List<String> modIds) {
    }
}

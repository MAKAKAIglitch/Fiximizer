package com.makakaiglitch.fiximizer.network;

import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/** Detects known network mods and chooses a passive coexistence policy. */
public final class NetworkCompatibilityDetector {
    private static volatile Snapshot snapshot = new Snapshot(List.of(), "Not scanned", false);

    private NetworkCompatibilityDetector() {
    }

    public static void refresh() {
        ModList mods = ModList.get();
        List<String> found = new ArrayList<>();
        boolean externalStackOptimizer = false;

        if (mods.isLoaded("krypton_fnp")) {
            found.add("Krypton Reno");
            externalStackOptimizer = true;
        }
        if (mods.isLoaded("krypton")) {
            found.add("Krypton");
            externalStackOptimizer = true;
        }
        if (mods.isLoaded("krypton_foxified")) {
            found.add("Krypton Foxified");
            externalStackOptimizer = true;
        }
        if (mods.isLoaded("connectivity")) {
            found.add("Connectivity");
            externalStackOptimizer = true;
        }
        if (mods.isLoaded("packetfixer") || mods.isLoaded("packet_fixer")) {
            found.add("Packet Fixer");
            externalStackOptimizer = true;
        }
        if (mods.isLoaded("connectionpacketfixer")) {
            found.add("Connection Packet Fixer");
            externalStackOptimizer = true;
        }
        if (mods.isLoaded("viaforge")) {
            found.add("ViaForge");
            externalStackOptimizer = true;
        }
        if (mods.isLoaded("viaversion")) {
            found.add("ViaVersion");
            externalStackOptimizer = true;
        }

        String policy = externalStackOptimizer
                ? "External networking modifier detected; Fiximizer keeps active socket tuning disabled"
                : "No known external networking modifier detected";
        if (found.contains("Krypton") && found.contains("Krypton Reno")) {
            policy = "Multiple network modifiers detected; Fiximizer remains passive";
        }
        snapshot = new Snapshot(List.copyOf(found), policy, externalStackOptimizer);
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public record Snapshot(List<String> detectedMods, String policy, boolean externalStackOptimizer) {
        public String detectedSummary() {
            return detectedMods.isEmpty() ? "None detected" : String.join(", ", detectedMods);
        }
    }
}

package com.makakaiglitch.fiximizer.benchmark;

/** User-selected repeatable workload; Fiximizer never automates gameplay. */
public enum BenchmarkScenario {
    MENU_IDLE("screen.fiximizer.benchmark.scenario.menu_idle", false),
    STABLE_SCENE("screen.fiximizer.benchmark.scenario.stable_scene", false),
    ENTITY_HEAVY("screen.fiximizer.benchmark.scenario.entity_heavy", true),
    CHUNK_TRAVERSAL("screen.fiximizer.benchmark.scenario.chunk_traversal", true),
    RESOURCE_RELOAD("screen.fiximizer.benchmark.scenario.resource_reload", false),
    INTEGRATED_SERVER_LOAD("screen.fiximizer.benchmark.scenario.integrated_server_load", true);

    private final String translationKey;
    private final boolean expectsIntegratedServer;

    BenchmarkScenario(String translationKey, boolean expectsIntegratedServer) {
        this.translationKey = translationKey;
        this.expectsIntegratedServer = expectsIntegratedServer;
    }

    public String translationKey() {
        return translationKey;
    }

    public boolean expectsIntegratedServer() {
        return expectsIntegratedServer;
    }

    public BenchmarkScenario next() {
        BenchmarkScenario[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}

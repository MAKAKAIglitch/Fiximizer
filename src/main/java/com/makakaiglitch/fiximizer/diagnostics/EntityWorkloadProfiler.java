package com.makakaiglitch.fiximizer.diagnostics;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Samples full Entity.tick() durations on every twentieth integrated-server
 * tick. This profiler is observational: it never cancels or delays entity work.
 */
public final class EntityWorkloadProfiler {
    public static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final int MAX_ENTITY_TYPES = 128;
    private static final int MAX_DISPLAY_TYPES = 8;

    private static final ThreadLocal<IdentityHashMap<Entity, Long>> START_TIMES =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private static final IdentityHashMap<EntityType<?>, RollingEntityTypeWindow> WINDOWS =
            new IdentityHashMap<>();
    private static final IdentityHashMap<EntityType<?>, MutableTickCost> CURRENT_TICK =
            new IdentityHashMap<>();

    private static boolean lastKnownEnabled = true;
    private static int serverTickCounter;
    private static int sampledServerTicks;
    private static boolean sampleThisTick;
    private static long untrackedEntityTicks;
    private static volatile Snapshot latest = Snapshot.empty();

    private EntityWorkloadProfiler() {
    }

    /**
     * Must be called from the integrated server thread before entity ticking.
     */
    public static void beginServerTick() {
        synchronizeEnabledState();
        sampleThisTick = false;

        if (!lastKnownEnabled) {
            START_TIMES.remove();
            return;
        }

        serverTickCounter++;
        sampleThisTick = serverTickCounter % SAMPLE_INTERVAL_TICKS == 0;
        if (!sampleThisTick) {
            return;
        }

        CURRENT_TICK.clear();
        START_TIMES.get().clear();
    }

    /**
     * Called from EntityTickEvent.Pre. Only the selected twentieth server tick
     * records timestamps; all other calls return immediately.
     */
    public static void beginEntityTick(Entity entity) {
        if (!sampleThisTick || !(entity.level() instanceof ServerLevel)) {
            return;
        }
        START_TIMES.get().put(entity, System.nanoTime());
    }

    /**
     * Called from EntityTickEvent.Post after Entity.tick() completed.
     */
    public static void endEntityTick(Entity entity) {
        if (!sampleThisTick || !(entity.level() instanceof ServerLevel)) {
            return;
        }

        Long startedAt = START_TIMES.get().remove(entity);
        if (startedAt == null) {
            return;
        }

        EntityType<?> type = entity.getType();
        RollingEntityTypeWindow typeWindow = WINDOWS.get(type);
        if (typeWindow == null) {
            if (WINDOWS.size() >= MAX_ENTITY_TYPES) {
                untrackedEntityTicks++;
                return;
            }

            typeWindow = new RollingEntityTypeWindow();
            int previousSamples = Math.min(sampledServerTicks, RollingEntityTypeWindow.DEFAULT_CAPACITY);
            for (int index = 0; index < previousSamples; index++) {
                typeWindow.record(0L, 0L, 0L);
            }
            WINDOWS.put(type, typeWindow);
        }

        MutableTickCost cost = CURRENT_TICK.get(type);
        if (cost == null) {
            cost = new MutableTickCost();
            CURRENT_TICK.put(type, cost);
        }

        long elapsed = Math.max(0L, System.nanoTime() - startedAt);
        cost.entityCount++;
        cost.totalNanos += elapsed;
        cost.peakEntityNanos = Math.max(cost.peakEntityNanos, elapsed);
    }

    /**
     * Must be called from the integrated server thread after entity ticking.
     */
    public static void endServerTick() {
        if (!sampleThisTick) {
            return;
        }
        sampleThisTick = false;
        START_TIMES.get().clear();

        for (Map.Entry<EntityType<?>, RollingEntityTypeWindow> entry : WINDOWS.entrySet()) {
            MutableTickCost cost = CURRENT_TICK.get(entry.getKey());
            if (cost == null) {
                entry.getValue().record(0L, 0L, 0L);
            } else {
                entry.getValue().record(cost.entityCount, cost.totalNanos, cost.peakEntityNanos);
            }
        }

        sampledServerTicks++;
        CURRENT_TICK.clear();
        latest = buildSnapshot();
    }

    /**
     * Synchronizes the profiler toggle on the server thread. Disabling clears
     * only telemetry; it does not touch entities or world state.
     */
    public static void synchronizeEnabledState() {
        boolean enabled = FiximizerConfig.ENABLE_ENTITY_WORKLOAD_PROFILING.get();
        if (enabled == lastKnownEnabled) {
            return;
        }

        lastKnownEnabled = enabled;
        reset();
    }

    public static Snapshot snapshot() {
        return latest;
    }

    private static Snapshot buildSnapshot() {
        List<TypeSnapshot> topTypes = new ArrayList<>(WINDOWS.size());
        for (Map.Entry<EntityType<?>, RollingEntityTypeWindow> entry : WINDOWS.entrySet()) {
            RollingEntityTypeWindow.Snapshot sample = entry.getValue().snapshot();
            if (!sample.hasSamples() || sample.entityTicks() == 0L) {
                continue;
            }

            String name = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entry.getKey()));
            topTypes.add(new TypeSnapshot(
                    name,
                    sample.entityTicks(),
                    sample.totalMillis(),
                    sample.averageMillisPerSampledTick(),
                    sample.p95MillisPerSampledTick(),
                    sample.peakEntityMillis(),
                    sample.averageMillisPerEntityTick()
            ));
        }

        topTypes.sort(Comparator.comparingDouble(TypeSnapshot::totalMillis).reversed());
        if (topTypes.size() > MAX_DISPLAY_TYPES) {
            topTypes = new ArrayList<>(topTypes.subList(0, MAX_DISPLAY_TYPES));
        }

        return new Snapshot(
                Math.min(sampledServerTicks, RollingEntityTypeWindow.DEFAULT_CAPACITY),
                WINDOWS.size(),
                untrackedEntityTicks,
                List.copyOf(topTypes)
        );
    }

    private static void reset() {
        serverTickCounter = 0;
        sampledServerTicks = 0;
        sampleThisTick = false;
        untrackedEntityTicks = 0L;
        CURRENT_TICK.clear();
        WINDOWS.clear();
        START_TIMES.remove();
        latest = Snapshot.empty();
    }

    public record Snapshot(
            int sampledServerTicks,
            int trackedEntityTypes,
            long untrackedEntityTicks,
            List<TypeSnapshot> topTypes
    ) {
        private static Snapshot empty() {
            return new Snapshot(0, 0, 0L, List.of());
        }

        public boolean hasSamples() {
            return sampledServerTicks > 0;
        }
    }

    public record TypeSnapshot(
            String entityType,
            long entityTicks,
            double totalMillis,
            double averageMillisPerSampledTick,
            double p95MillisPerSampledTick,
            double peakEntityMillis,
            double averageMillisPerEntityTick
    ) {
    }

    private static final class MutableTickCost {
        private long entityCount;
        private long totalNanos;
        private long peakEntityNanos;
    }
}

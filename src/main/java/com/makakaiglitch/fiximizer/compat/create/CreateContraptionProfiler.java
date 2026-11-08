package com.makakaiglitch.fiximizer.compat.create;

import com.makakaiglitch.fiximizer.compat.ModIntegrationManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;

import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Passive Create integration: measures logical-server tick time for entities
 * inheriting Create's AbstractContraptionEntity without linking against Create.
 *
 * It does not change entity behavior, scheduling, physics or tick order.
 */
public final class CreateContraptionProfiler {
    private static final String CREATE_BASE_ENTITY =
            "com.simibubi.create.content.contraptions.AbstractContraptionEntity";
    private static final int WINDOW_TICKS = 100;
    private static final int MAX_ENTITY_TYPES = 16;

    private static final RollingTickWindow WINDOW = new RollingTickWindow(WINDOW_TICKS);
    private static final ThreadLocal<IdentityHashMap<Entity, Long>> START_TIMES =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ConcurrentHashMap<String, TypeCounter> TYPE_COUNTERS = new ConcurrentHashMap<>();

    private static final ClassValue<Boolean> CREATE_CONTRAPTION_CLASS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if (CREATE_BASE_ENTITY.equals(current.getName())) {
                    return true;
                }
            }
            return false;
        }
    };

    private static int currentEntityTicks;
    private static long currentTotalNanos;
    private static long currentPeakEntityNanos;
    private static volatile Snapshot latest = Snapshot.empty();

    private CreateContraptionProfiler() {
    }

    public static void beginServerTick() {
        START_TIMES.get().clear();
        currentEntityTicks = 0;
        currentTotalNanos = 0L;
        currentPeakEntityNanos = 0L;
    }

    public static void beginEntityTick(Entity entity) {
        if (!ModIntegrationManager.isActive("create")
                || !(entity.level() instanceof ServerLevel)
                || !CREATE_CONTRAPTION_CLASS.get(entity.getClass())) {
            return;
        }

        START_TIMES.get().put(entity, System.nanoTime());
    }

    public static void endEntityTick(Entity entity) {
        Long startedAt = START_TIMES.get().remove(entity);
        if (startedAt == null) {
            return;
        }

        long elapsed = Math.max(0L, System.nanoTime() - startedAt);
        currentEntityTicks++;
        currentTotalNanos += elapsed;
        currentPeakEntityNanos = Math.max(currentPeakEntityNanos, elapsed);
        recordEntityType(entity.getClass().getSimpleName(), elapsed);
    }

    public static void endServerTick() {
        START_TIMES.get().clear();
        if (!ModIntegrationManager.isActive("create")) {
            WINDOW.reset();
            TYPE_COUNTERS.clear();
            latest = Snapshot.empty();
            return;
        }

        WINDOW.record(currentEntityTicks, currentTotalNanos, currentPeakEntityNanos);
        RollingTickWindow.Snapshot window = WINDOW.snapshot();
        TypeEntry top = topEntityType();

        latest = new Snapshot(
                window.sampledServerTicks(),
                window.contraptionEntityTicks(),
                window.averageMillisPerServerTick(),
                window.averageMillisPerEntityTick(),
                window.peakEntityTickMillis(),
                top.name(),
                top.totalNanos() / 1_000_000.0
        );
    }

    public static Snapshot latest() {
        return latest;
    }

    private static void recordEntityType(String typeName, long elapsedNanos) {
        if (typeName == null || typeName.isBlank()) {
            typeName = "Unknown";
        }
        if (typeName.length() > 64) {
            typeName = typeName.substring(0, 64);
        }

        TypeCounter counter = TYPE_COUNTERS.get(typeName);
        if (counter == null && TYPE_COUNTERS.size() < MAX_ENTITY_TYPES) {
            TypeCounter candidate = new TypeCounter();
            TypeCounter existing = TYPE_COUNTERS.putIfAbsent(typeName, candidate);
            counter = existing == null ? candidate : existing;
        }
        if (counter != null) {
            counter.record(elapsedNanos);
        }
    }

    private static TypeEntry topEntityType() {
        return TYPE_COUNTERS.entrySet().stream()
                .map(entry -> new TypeEntry(entry.getKey(), entry.getValue().totalNanos.sum()))
                .max(java.util.Comparator.comparingLong(TypeEntry::totalNanos))
                .orElse(new TypeEntry("n/a", 0L));
    }

    public record Snapshot(
            int sampledServerTicks,
            long contraptionEntityTicks,
            double averageMillisPerServerTick,
            double averageMillisPerEntityTick,
            double peakEntityTickMillis,
            String topEntityType,
            double topEntityTotalMillis
    ) {
        private static Snapshot empty() {
            return new Snapshot(0, 0L, 0.0, 0.0, 0.0, "n/a", 0.0);
        }

        public boolean hasSamples() {
            return sampledServerTicks > 0;
        }
    }

    private record TypeEntry(String name, long totalNanos) {
    }

    private static final class TypeCounter {
        private final LongAdder totalNanos = new LongAdder();

        private void record(long elapsedNanos) {
            totalNanos.add(elapsedNanos);
        }
    }
}

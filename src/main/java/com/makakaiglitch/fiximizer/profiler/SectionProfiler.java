package com.makakaiglitch.fiximizer.profiler;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead scoped profiler for work explicitly instrumented by Fiximizer.
 *
 * This does not magically attribute arbitrary third-party mod methods. Use the
 * JFR recorder for whole-JVM sampling and this class for Fiximizer-owned tasks.
 */
public final class SectionProfiler {
    private static final int MAX_SECTIONS = 128;
    private static final ConcurrentMap<String, MutableStats> SECTIONS = new ConcurrentHashMap<>();
    private static final Scope NOOP_SCOPE = () -> { };

    private SectionProfiler() {
    }

    public static Scope begin(String sectionName) {
        if (sectionName == null || sectionName.isBlank()) {
            return NOOP_SCOPE;
        }

        String name = sectionName.length() > 96 ? sectionName.substring(0, 96) : sectionName;
        MutableStats stats = SECTIONS.get(name);

        if (stats == null) {
            if (SECTIONS.size() >= MAX_SECTIONS) {
                return NOOP_SCOPE;
            }
            MutableStats candidate = new MutableStats();
            MutableStats existing = SECTIONS.putIfAbsent(name, candidate);
            stats = existing == null ? candidate : existing;
        }

        return new TimedScope(stats);
    }

    public static List<ProfileSectionSnapshot> topSections(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        return SECTIONS.entrySet().stream()
                .map(entry -> entry.getValue().snapshot(entry.getKey()))
                .sorted(Comparator.comparingLong(ProfileSectionSnapshot::totalNanos).reversed())
                .limit(limit)
                .toList();
    }

    public static int trackedSectionCount() {
        return SECTIONS.size();
    }

    public static void reset() {
        SECTIONS.clear();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final class TimedScope implements Scope {
        private final MutableStats stats;
        private final long startedAtNanos;
        private boolean closed;

        private TimedScope(MutableStats stats) {
            this.stats = stats;
            this.startedAtNanos = System.nanoTime();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            stats.record(Math.max(0L, System.nanoTime() - startedAtNanos));
        }
    }

    private static final class MutableStats {
        private final LongAdder calls = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        private void record(long elapsedNanos) {
            calls.increment();
            totalNanos.add(elapsedNanos);
            maxNanos.accumulateAndGet(elapsedNanos, Math::max);
        }

        private ProfileSectionSnapshot snapshot(String name) {
            return new ProfileSectionSnapshot(name, calls.sum(), totalNanos.sum(), maxNanos.get());
        }
    }
}

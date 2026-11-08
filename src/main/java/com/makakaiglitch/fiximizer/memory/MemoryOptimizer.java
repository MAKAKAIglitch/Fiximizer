package com.makakaiglitch.fiximizer.memory;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;

/**
 * Entry point for Fiximizer's safe, explicitly requested memory sharing.
 *
 * The current implementation canonicalizes only immutable String values
 * supplied by Fiximizer or an integration. It never walks the heap, changes
 * foreign object graphs, or schedules recurring work.
 *
 * The lookup table is initialized lazily: with the feature disabled, the
 * normal game path allocates no deduplication table and performs no hashing.
 */
public final class MemoryOptimizer {
    private static volatile StringDeduplicator strings;

    private MemoryOptimizer() {
    }

    /**
     * Canonicalize an immutable string at an ownership boundary and always
     * assign the returned value. Short strings and disabled operation bypass
     * the cache without hashing or allocating an entry.
     */
    public static String canonicalize(String value) {
        if (!FiximizerConfig.ENABLE_STRING_CANONICALIZATION.get()
                || value == null
                || value.length() < 8) {
            return value;
        }

        StringDeduplicator current = strings;
        if (current == null) {
            synchronized (MemoryOptimizer.class) {
                current = strings;
                if (current == null) {
                    current = new StringDeduplicator();
                    strings = current;
                }
            }
        }
        return current.deduplicate(value);
    }

    public static StringDeduplicator.Snapshot stringSnapshot() {
        StringDeduplicator current = strings;
        return current == null
                ? new StringDeduplicator.Snapshot(0L, 0L, 0L, 0L, 0L, 0, 0)
                : current.snapshot();
    }

    /**
     * Drops all weak cache entries when the user disables the feature.
     * Existing String references remain valid.
     */
    public static void clear() {
        synchronized (MemoryOptimizer.class) {
            StringDeduplicator current = strings;
            if (current != null) {
                current.clear();
                strings = null;
            }
        }
    }
}

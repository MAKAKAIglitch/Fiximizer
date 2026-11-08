package com.makakaiglitch.fiximizer.memory;

import com.makakaiglitch.fiximizer.config.FiximizerConfig;

/**
 * Entry point for Fiximizer's safe, explicitly requested memory sharing.
 *
 * The current implementation canonicalizes only immutable String values
 * supplied by Fiximizer or an integration. It never walks the heap, changes
 * foreign object graphs, or schedules recurring work.
 */
public final class MemoryOptimizer {
    private static final StringDeduplicator STRINGS = new StringDeduplicator();

    private MemoryOptimizer() {
    }

    /**
     * Canonicalize an immutable string at an ownership boundary and always
     * assign the returned value. The feature is disabled through config.
     */
    public static String canonicalize(String value) {
        if (!FiximizerConfig.ENABLE_STRING_CANONICALIZATION.get()) {
            return value;
        }
        return STRINGS.deduplicate(value);
    }

    public static StringDeduplicator.Snapshot stringSnapshot() {
        return STRINGS.snapshot();
    }

    /**
     * Drops the table's weak entries when the user disables the feature.
     * It does not invalidate String references already held by callers.
     */
    public static void clear() {
        STRINGS.clear();
    }
}

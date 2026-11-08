package com.makakaiglitch.fiximizer.memory;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead, bounded, weak, set-associative string canonicalization.
 *
 * This does not scan the heap or rewrite arbitrary references. Callers must
 * store the returned value. Four-way buckets bound lookup work; hash matches
 * are always followed by full String equality checks.
 */
public final class StringDeduplicator {
    private static final int WAYS = 4;
    private static final int DEFAULT_SET_COUNT = 16;
    private static final int MAX_CAS_RETRIES = 3;
    private static final int MINIMUM_STRING_LENGTH = 8;

    public static final int DEFAULT_CAPACITY = DEFAULT_SET_COUNT * WAYS;

    private final int setMask;
    private final AtomicReferenceArray<Entry> entries;
    private final AtomicInteger occupiedSlots = new AtomicInteger();

    private final LongAdder hits = new LongAdder();
    private final LongAdder registrations = new LongAdder();
    private final LongAdder staleReuses = new LongAdder();
    private final LongAdder collisionEvictions = new LongAdder();
    private final LongAdder contentionFallbacks = new LongAdder();

    public StringDeduplicator() {
        this(DEFAULT_SET_COUNT);
    }

    /**
     * @param setCount number of four-entry buckets; must be a power of two
     */
    public StringDeduplicator(int setCount) {
        if (setCount < 1 || setCount > 4096 || Integer.bitCount(setCount) != 1) {
            throw new IllegalArgumentException("setCount must be a power of two between 1 and 4096");
        }
        this.setMask = setCount - 1;
        this.entries = new AtomicReferenceArray<>(setCount * WAYS);
    }

    /**
     * Returns a previously registered equal String when it is still live.
     * Short strings bypass the table because their potential savings are too
     * small to justify a weak-reference entry.
     */
    public String deduplicate(String value) {
        if (value == null || value.length() < MINIMUM_STRING_LENGTH) {
            return value;
        }

        int hash = value.hashCode();
        int spread = hash ^ (hash >>> 16);
        int bucketStart = (spread & setMask) * WAYS;
        int preferredIndex = bucketStart + ((spread >>> 8) & (WAYS - 1));

        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            int firstEmpty = -1;
            Entry firstEmptyExpected = null;
            int firstStale = -1;
            Entry firstStaleExpected = null;
            Entry preferredExpected = null;

            for (int way = 0; way < WAYS; way++) {
                int index = bucketStart + way;
                Entry observed = entries.get(index);

                if (index == preferredIndex) {
                    preferredExpected = observed;
                }

                if (observed == null) {
                    if (firstEmpty < 0) {
                        firstEmpty = index;
                        firstEmptyExpected = null;
                    }
                    continue;
                }

                String existing = observed.value().get();
                if (existing == null) {
                    if (firstStale < 0) {
                        firstStale = index;
                        firstStaleExpected = observed;
                    }
                    continue;
                }

                if (observed.hash() == hash && existing.equals(value)) {
                    // Re-reading the exact same object is not a memory saving.
                    if (existing != value) {
                        hits.increment();
                    }
                    return existing;
                }
            }

            final int target;
            final Entry expected;
            if (firstStale >= 0) {
                target = firstStale;
                expected = firstStaleExpected;
            } else if (firstEmpty >= 0) {
                target = firstEmpty;
                expected = firstEmptyExpected;
            } else {
                target = preferredIndex;
                expected = preferredExpected;
            }

            Entry replacement = new Entry(hash, new WeakReference<>(value));

            // CAS against the exact entry observed during the scan. Reading the
            // slot again here would allow this thread to overwrite a concurrent
            // canonical registration instead of retrying and reusing it.
            if (entries.compareAndSet(target, expected, replacement)) {
                registrations.increment();
                if (expected == null) {
                    occupiedSlots.incrementAndGet();
                } else if (expected.value().get() == null) {
                    staleReuses.increment();
                } else {
                    collisionEvictions.increment();
                }
                return value;
            }
        }

        // Under contention, bypass instead of spinning or blocking the caller.
        contentionFallbacks.increment();
        return value;
    }

    /**
     * Releases all weak cache entries. Existing game references remain valid.
     */
    public void clear() {
        for (int index = 0; index < entries.length(); index++) {
            entries.set(index, null);
        }
        occupiedSlots.set(0);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                hits.sum(),
                registrations.sum(),
                staleReuses.sum(),
                collisionEvictions.sum(),
                contentionFallbacks.sum(),
                occupiedSlots.get(),
                entries.length()
        );
    }

    private record Entry(int hash, WeakReference<String> value) {
    }

    public record Snapshot(
            long hits,
            long registrations,
            long staleReuses,
            long collisionEvictions,
            long contentionFallbacks,
            int occupiedSlots,
            int capacity
    ) {
    }
}

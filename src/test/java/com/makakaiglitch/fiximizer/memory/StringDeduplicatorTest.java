package com.makakaiglitch.fiximizer.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringDeduplicatorTest {
    @Test
    void returnsTheSameLiveInstanceForEqualStrings() {
        StringDeduplicator deduplicator = new StringDeduplicator();
        String first = new String("minecraft:overworld");
        String second = new String("minecraft:overworld");

        assertFalse(first == second);
        assertSame(first, deduplicator.deduplicate(first));
        assertSame(first, deduplicator.deduplicate(second));
        assertEquals(1L, deduplicator.snapshot().hits());
    }

    @Test
    void repeatedCallsWithTheSameInstanceAreNotCountedAsMemorySavings() {
        StringDeduplicator deduplicator = new StringDeduplicator();
        String value = new String("minecraft:the_nether");

        assertSame(value, deduplicator.deduplicate(value));
        assertSame(value, deduplicator.deduplicate(value));
        assertEquals(0L, deduplicator.snapshot().hits());
        assertEquals(1L, deduplicator.snapshot().registrations());
    }

    @Test
    void bypassesShortStringsWithoutCreatingEntries() {
        StringDeduplicator deduplicator = new StringDeduplicator();
        String first = new String("short");
        String second = new String("short");

        assertSame(first, deduplicator.deduplicate(first));
        assertSame(second, deduplicator.deduplicate(second));
        assertEquals(0, deduplicator.snapshot().occupiedSlots());
        assertEquals(0L, deduplicator.snapshot().registrations());
    }

    @Test
    void verifiesFullContentWhenHashesCollide() {
        StringDeduplicator deduplicator = new StringDeduplicator();
        String first = new String("AaAaAaAa");
        String different = new String("BBBBBBBB");
        String duplicate = new String("BBBBBBBB");

        assertEquals(first.hashCode(), different.hashCode());
        assertSame(first, deduplicator.deduplicate(first));
        assertSame(different, deduplicator.deduplicate(different));
        assertSame(different, deduplicator.deduplicate(duplicate));
        assertEquals(2L, deduplicator.snapshot().registrations());
    }

    @Test
    void keepsTheTableBoundedAndEvictsOnlyItsLookupEntry() {
        StringDeduplicator deduplicator = new StringDeduplicator(1);

        for (int index = 0; index < 20; index++) {
            deduplicator.deduplicate("long-unique-value-" + index);
        }

        StringDeduplicator.Snapshot snapshot = deduplicator.snapshot();
        assertEquals(4, snapshot.capacity());
        assertTrue(snapshot.occupiedSlots() <= snapshot.capacity());
        assertTrue(snapshot.collisionEvictions() > 0L);
    }

    @Test
    void convergesConcurrentEqualValuesOnOneCanonicalInstance() throws Exception {
        int workers = 12;

        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int round = 0; round < 30; round++) {
                StringDeduplicator deduplicator = new StringDeduplicator();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> results = new ArrayList<>();

                for (int index = 0; index < workers; index++) {
                    results.add(executor.submit(() -> {
                        start.await();
                        return deduplicator.deduplicate(new String("shared:resource:identifier"));
                    }));
                }

                start.countDown();
                String canonical = results.getFirst().get();
                for (Future<String> result : results) {
                    assertSame(canonical, result.get());
                }
            }
        }
    }

    @Test
    void clearDropsEntriesWithoutChangingPreviouslyReturnedStrings() {
        StringDeduplicator deduplicator = new StringDeduplicator();
        String value = deduplicator.deduplicate(new String("persistent:resource"));
        deduplicator.clear();

        assertEquals(0, deduplicator.snapshot().occupiedSlots());
        assertSame(value, deduplicator.deduplicate(value));
        assertEquals(1, deduplicator.snapshot().occupiedSlots());
    }

    @Test
    void rejectsInvalidSetCounts() {
        assertThrows(IllegalArgumentException.class, () -> new StringDeduplicator(0));
        assertThrows(IllegalArgumentException.class, () -> new StringDeduplicator(3));
    }
}

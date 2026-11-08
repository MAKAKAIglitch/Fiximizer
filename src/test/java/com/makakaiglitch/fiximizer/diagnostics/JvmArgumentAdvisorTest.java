package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmArgumentAdvisorTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void leavesJvmArgumentsUnchangedWhenStringDeduplicationWasNotRequested() {
        JvmArgumentAdvisor.Recommendation base = JvmArgumentAdvisor.recommend(16L * GIB, 80);

        JvmArgumentAdvisor.Recommendation result =
                JvmArgumentAdvisor.withG1StringDeduplication(base, false, true);

        assertSame(base, result);
        assertFalse(result.arguments().contains("UseStringDeduplication"));
    }

    @Test
    void addsG1StringDeduplicationOnlyWhenExplicitlyRequestedAndG1WasDetected() {
        JvmArgumentAdvisor.Recommendation base = JvmArgumentAdvisor.recommend(16L * GIB, 80);

        JvmArgumentAdvisor.Recommendation result =
                JvmArgumentAdvisor.withG1StringDeduplication(base, true, true);

        assertTrue(result.arguments().startsWith(base.arguments()));
        assertTrue(result.arguments().contains("-XX:+UseStringDeduplication"));
        assertTrue(result.note().contains("may add GC CPU work"));
    }

    @Test
    void doesNotRecommendG1StringDeduplicationForAnotherCollector() {
        JvmArgumentAdvisor.Recommendation base = JvmArgumentAdvisor.recommend(16L * GIB, 80);

        JvmArgumentAdvisor.Recommendation result =
                JvmArgumentAdvisor.withG1StringDeduplication(base, true, false);

        assertSame(base.arguments(), result.arguments());
        assertFalse(result.arguments().contains("UseStringDeduplication"));
        assertTrue(result.note().contains("not G1"));
    }
}

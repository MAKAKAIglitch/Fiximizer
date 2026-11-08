package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmArgumentAdvisorTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void recommendsCompactHeadersForJava25ButNotTheJava21Fallback() {
        JvmArgumentAdvisor.Recommendation base =
                JvmArgumentAdvisor.recommend(16L * GIB, 80);

        var java25 = com.makakaiglitch.fiximizer.memory.RuntimeMemoryCapabilities.evaluate(
                25, true, false, true, 250_000);
        var java21 = com.makakaiglitch.fiximizer.memory.RuntimeMemoryCapabilities.evaluate(
                21, false, false, true, 250_000);

        JvmArgumentAdvisor.Recommendation java25Result =
                JvmArgumentAdvisor.withCompactObjectHeaders(base, java25);
        JvmArgumentAdvisor.Recommendation java21Result =
                JvmArgumentAdvisor.withCompactObjectHeaders(base, java21);

        assertTrue(java25Result.arguments().contains("-XX:+UseCompactObjectHeaders"));
        assertFalse(java21Result.arguments().contains("-XX:+UseCompactObjectHeaders"));
    }

    @Test
    void doesNotRecommendCompactHeadersWhenTheRuntimeClassLimitIsReached() {
        JvmArgumentAdvisor.Recommendation base =
                JvmArgumentAdvisor.recommend(16L * GIB, 80);
        var capabilities = com.makakaiglitch.fiximizer.memory.RuntimeMemoryCapabilities.evaluate(
                25,
                true,
                false,
                true,
                com.makakaiglitch.fiximizer.memory.RuntimeMemoryCapabilities.COMPACT_HEADERS_CLASS_LIMIT
        );

        JvmArgumentAdvisor.Recommendation result =
                JvmArgumentAdvisor.withCompactObjectHeaders(base, capabilities);

        assertFalse(result.arguments().contains("-XX:+UseCompactObjectHeaders"));
        assertTrue(result.note().contains("4,000,000"));
    }

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

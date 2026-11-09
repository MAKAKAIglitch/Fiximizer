package com.makakaiglitch.fiximizer.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeMemoryCapabilitiesTest {
    @Test
    void detectsTheActualRunningJdkAndExposedHotSpotFlag() {
        RuntimeMemoryCapabilities.Snapshot runtime = RuntimeMemoryCapabilities.current();
        int expectedMajor = RuntimeMemoryCapabilities.parseJavaMajor(
                System.getProperty("java.specification.version", ""));

        assertEquals(expectedMajor, runtime.javaMajor());
        String vmName = System.getProperty("java.vm.name", "").toLowerCase();
        if (expectedMajor >= 25
                && (vmName.contains("hotspot") || vmName.contains("openjdk"))) {
            assertTrue(runtime.compactObjectHeadersSupported(),
                    "HotSpot JDK 25+ should expose the Compact Object Headers product option");
        }
    }

    @Test
    void parsesJavaMajorVersionsUsedByRuntimeProperties() {
        assertEquals(21, RuntimeMemoryCapabilities.parseJavaMajor("21"));
        assertEquals(25, RuntimeMemoryCapabilities.parseJavaMajor("25"));
        assertEquals(25, RuntimeMemoryCapabilities.parseJavaMajor("25-ea"));
        assertEquals(8, RuntimeMemoryCapabilities.parseJavaMajor("1.8"));
        assertEquals(0, RuntimeMemoryCapabilities.parseJavaMajor(""));
        assertEquals(0, RuntimeMemoryCapabilities.parseJavaMajor("unknown"));
    }

    @Test
    void selectsCompactHeadersWhenJdk25VmExposesTheOption() {
        RuntimeMemoryCapabilities.Snapshot result =
                RuntimeMemoryCapabilities.evaluate(25, true, false, true, 250_000);

        assertTrue(result.compactObjectHeadersSupported());
        assertTrue(result.shouldRecommendCompactObjectHeaders());
        assertFalse(result.compactObjectHeadersEnabled());
        assertFalse(result.compactObjectHeadersClassLimitReached());
        assertFalse(result.usesJava21Fallback());
    }

    @Test
    void keepsJava21OnTheCompatibleFallback() {
        RuntimeMemoryCapabilities.Snapshot result =
                RuntimeMemoryCapabilities.evaluate(21, false, false, true, 250_000);

        assertFalse(result.compactObjectHeadersSupported());
        assertFalse(result.shouldRecommendCompactObjectHeaders());
        assertTrue(result.usesJava21Fallback());
        assertTrue(result.g1Available());
    }

    @Test
    void doesNotRecommendCompactHeadersWhenAlreadyEnabled() {
        RuntimeMemoryCapabilities.Snapshot result =
                RuntimeMemoryCapabilities.evaluate(25, true, true, true, 250_000);

        assertTrue(result.compactObjectHeadersSupported());
        assertTrue(result.compactObjectHeadersEnabled());
        assertFalse(result.shouldRecommendCompactObjectHeaders());
    }

    @Test
    void respectsTheFourMillionLoadedClassLimit() {
        RuntimeMemoryCapabilities.Snapshot result =
                RuntimeMemoryCapabilities.evaluate(
                        25,
                        true,
                        false,
                        true,
                        RuntimeMemoryCapabilities.COMPACT_HEADERS_CLASS_LIMIT
                );

        assertTrue(result.compactObjectHeadersClassLimitReached());
        assertFalse(result.shouldRecommendCompactObjectHeaders());
    }

    @Test
    void doesNotAssumeCompactHeadersFromVersionWhenVmDoesNotExposeTheFlag() {
        RuntimeMemoryCapabilities.Snapshot result =
                RuntimeMemoryCapabilities.evaluate(25, false, false, false, 100_000);

        assertFalse(result.compactObjectHeadersSupported());
        assertFalse(result.shouldRecommendCompactObjectHeaders());
        assertTrue(result.usesJava21Fallback());
    }

    @Test
    void staysOnTheFallbackForJdk24BecauseTheFlagWasStillExperimental() {
        RuntimeMemoryCapabilities.Snapshot result =
                RuntimeMemoryCapabilities.evaluate(24, true, false, true, 100_000);

        assertFalse(result.compactObjectHeadersSupported());
        assertFalse(result.shouldRecommendCompactObjectHeaders());
    }
}

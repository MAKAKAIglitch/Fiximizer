package com.makakaiglitch.fiximizer.scheduler;

import com.makakaiglitch.fiximizer.diagnostics.JvmArgumentAdvisor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OptimizationProfileTest {
    private static final long GIB = 1024L * 1024L * 1024L;

    @TempDir
    Path configDirectory;

    @AfterEach
    void clearTemporaryMemoryOverride() {
        OptimizationProfileManager.setMemoryPressure(false);
    }

    @Test
    void memorySaverLimitsWorkersAndDisablesGpuOffload() {
        OptimizationProfile profile = OptimizationProfile.MEMORY_SAVER;

        assertEquals(1, profile.maximumWorkers());
        assertEquals(16, profile.queueCapacity());
        assertFalse(profile.openClAllowed());
    }

    @Test
    void maximumPerformanceAllowsFourWorkersAndLargerQueue() {
        OptimizationProfile profile = OptimizationProfile.MAX_PERFORMANCE;

        assertEquals(4, profile.maximumWorkers());
        assertEquals(256, profile.queueCapacity());
        assertTrue(profile.openClAllowed());
    }

    @Test
    void latencyAndSmoothnessKeepCriticalTasksAheadOfBackgroundWork() {
        for (OptimizationProfile profile : new OptimizationProfile[]{
                OptimizationProfile.SMOOTHNESS,
                OptimizationProfile.LOW_LATENCY
        }) {
            assertTrue(profile.queueOrder(TaskPriority.CRITICAL)
                    < profile.queueOrder(TaskPriority.BACKGROUND));
            assertTrue(profile.queueOrder(TaskPriority.HIGH)
                    < profile.queueOrder(TaskPriority.NORMAL));
        }
    }

    @Test
    void selectedProfilePersistsAcrossReload() {
        assertTrue(OptimizationProfileManager.select(
                OptimizationProfile.MAX_PERFORMANCE,
                configDirectory
        ));

        OptimizationProfileManager.load(configDirectory);

        assertEquals(OptimizationProfile.MAX_PERFORMANCE, OptimizationProfileManager.selected());
        assertEquals(OptimizationProfile.MAX_PERFORMANCE, OptimizationProfileManager.current());
    }

    @Test
    void memoryPressureTemporarilyOverridesButDoesNotReplaceSelectedProfile() {
        assertTrue(OptimizationProfileManager.select(
                OptimizationProfile.MAX_PERFORMANCE,
                configDirectory
        ));

        OptimizationProfileManager.setMemoryPressure(true);

        assertEquals(OptimizationProfile.MAX_PERFORMANCE, OptimizationProfileManager.selected());
        assertEquals(OptimizationProfile.MEMORY_SAVER, OptimizationProfileManager.current());
        assertTrue(OptimizationProfileManager.memoryPressureOverride());

        OptimizationProfileManager.setMemoryPressure(false);

        assertEquals(OptimizationProfile.MAX_PERFORMANCE, OptimizationProfileManager.current());
        assertFalse(OptimizationProfileManager.memoryPressureOverride());
    }

    @Test
    void invalidProfileFallsBackToSmoothness() {
        assertEquals(
                OptimizationProfile.SMOOTHNESS,
                OptimizationProfile.fromId("not-a-real-profile")
        );
        assertEquals(
                OptimizationProfile.LOW_LATENCY,
                OptimizationProfile.fromId("LOW_LATENCY")
        );
    }

    @Test
    void jvmAdvisorProducesIntegerMiBArgumentsAndLeavesGcDefaultsAlone() {
        JvmArgumentAdvisor.Recommendation recommendation =
                JvmArgumentAdvisor.recommend(16L * GIB, 180);

        assertTrue(recommendation.arguments().matches("-Xms\\d+M -Xmx\\d+M"));
        assertFalse(recommendation.arguments().contains("UseG1GC"));
        assertFalse(recommendation.arguments().contains("System.gc"));
        assertEquals(180, recommendation.loadedModCount());
        assertTrue(recommendation.maximumHeapBytes() >= 2L * GIB);
        assertTrue(recommendation.maximumHeapBytes() <= 10L * GIB);
        assertTrue(recommendation.maximumHeapBytes() <= (long) (16L * GIB * 0.40));
    }

    @Test
    void jvmAdvisorDoesNotForceTwoGiBHeapOnSmallMemorySystems() {
        JvmArgumentAdvisor.Recommendation recommendation =
                JvmArgumentAdvisor.recommend(4L * GIB, 180);

        assertTrue(recommendation.maximumHeapBytes() < 2L * GIB);
        assertTrue(recommendation.maximumHeapBytes() <= (long) (4L * GIB * 0.40));
    }
}

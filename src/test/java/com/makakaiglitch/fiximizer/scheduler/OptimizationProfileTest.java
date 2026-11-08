package com.makakaiglitch.fiximizer.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OptimizationProfileTest {
    @TempDir
    Path configDirectory;

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

        assertEquals(OptimizationProfile.MAX_PERFORMANCE, OptimizationProfileManager.current());
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
}

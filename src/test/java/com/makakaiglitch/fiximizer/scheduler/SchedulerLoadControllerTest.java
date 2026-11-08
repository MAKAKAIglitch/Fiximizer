package com.makakaiglitch.fiximizer.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulerLoadControllerTest {
    @Test
    void scalesUpOnlyForObservedQueueDelayAndCpuHeadroom() {
        SchedulerLoadController.Decision decision = SchedulerLoadController.decide(
                true, 0.30, 1, 4, 1, 4, 180.0, 0.0, OptimizationProfile.MAX_PERFORMANCE);

        assertEquals(2, decision.targetWorkers());
    }

    @Test
    void scalesUpWhenCurrentQueueAgeIsHighEvenBeforeCompletedWaitSamplesExist() {
        SchedulerLoadController.Decision decision = SchedulerLoadController.decide(
                true, 0.30, 1, 4, 1, 4, 0.0, 240.0, OptimizationProfile.MAX_PERFORMANCE);

        assertEquals(2, decision.targetWorkers());
    }

    @Test
    void doesNotScaleUpWhenCpuIsAlreadyBusy() {
        SchedulerLoadController.Decision decision = SchedulerLoadController.decide(
                true, 0.85, 1, 4, 1, 4, 500.0, 800.0, OptimizationProfile.MAX_PERFORMANCE);

        assertEquals(1, decision.targetWorkers());
    }

    @Test
    void reducesToOneWorkerAtCriticalCpuLoad() {
        SchedulerLoadController.Decision decision = SchedulerLoadController.decide(
                true, 0.98, 4, 8, 4, 4, 800.0, 1000.0, OptimizationProfile.MAX_PERFORMANCE);

        assertEquals(1, decision.targetWorkers());
    }

    @Test
    void holdsWorkerCountWhenCpuLoadIsUnavailable() {
        SchedulerLoadController.Decision decision = SchedulerLoadController.decide(
                true, -1.0, 4, 8, 2, 4, 800.0, 1000.0, OptimizationProfile.MAX_PERFORMANCE);

        assertEquals(2, decision.targetWorkers());
    }

    @Test
    void releasesIdleWorkerCapacityGradually() {
        SchedulerLoadController.Decision decision = SchedulerLoadController.decide(
                true, 0.20, 0, 0, 4, 4, 0.0, 0.0, OptimizationProfile.MAX_PERFORMANCE);

        assertEquals(3, decision.targetWorkers());
    }

    @Test
    void manualMaximumIsHonouredWhenAdaptiveModeIsOff() {
        SchedulerLoadController.Decision decision = SchedulerLoadController.decide(
                false, 0.99, 1, 0, 1, 3, 0.0, 0.0, OptimizationProfile.BALANCED);

        assertEquals(3, decision.targetWorkers());
    }

    @Test
    void queuePressureCannotExceedProfileWorkerLimit() {
        SchedulerLoadController.Decision decision = SchedulerLoadController.decide(
                true, 0.10, 2, 12, 2, 2, 1200.0, 800.0, OptimizationProfile.SMOOTHNESS);

        assertEquals(2, decision.targetWorkers());
    }
}

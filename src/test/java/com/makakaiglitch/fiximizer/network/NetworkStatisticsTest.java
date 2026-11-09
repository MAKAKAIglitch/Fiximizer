package com.makakaiglitch.fiximizer.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkStatisticsTest {
    @Test
    void computesPercentilesAndLeavesInputUntouched() {
        long[] samples = {9, 1, 5, 3, 7};
        long[] output = new long[3];
        assertTrue(NetworkStatistics.summarizeJava(samples, output));
        assertArrayEquals(new long[]{5, 7, 7}, output);
        assertArrayEquals(new long[]{9, 1, 5, 3, 7}, samples);
    }

    @Test
    void rejectsEmptyOversizedAndInvalidOutput() {
        assertFalse(NetworkStatistics.summarizeJava(new long[0], new long[3]));
        assertFalse(NetworkStatistics.summarizeJava(new long[NetworkStatistics.MAX_SAMPLES + 1], new long[3]));
        assertFalse(NetworkStatistics.summarizeJava(new long[]{1}, new long[2]));
    }
}

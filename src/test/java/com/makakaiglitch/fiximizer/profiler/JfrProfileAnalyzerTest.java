package com.makakaiglitch.fiximizer.profiler;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrProfileAnalyzerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesAReadableAnalysisForAValidRecordingWithoutSamples() throws Exception {
        Path recordingFile = temporaryDirectory.resolve("minimal.jfr");
        try (Recording recording = new Recording()) {
            recording.start();
            recording.stop();
            recording.dump(recordingFile);
        }

        Path analysis = JfrProfileAnalyzer.analyzeAndWrite(recordingFile);

        assertTrue(Files.isRegularFile(recordingFile));
        assertTrue(Files.isRegularFile(analysis));
        String report = Files.readString(analysis);
        assertTrue(report.contains("Fiximizer — JFR profile analysis"));
        assertTrue(report.contains("CPU execution samples:"));
        assertTrue(report.contains("No Java execution samples were recorded."));
        assertFalse(report.isBlank());
    }
}

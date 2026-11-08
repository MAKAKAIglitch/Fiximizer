package com.makakaiglitch.fiximizer.profiler;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrIncidentCorrelatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesCorrelationReportForValidRecordingWithoutSamples() throws Exception {
        Path recordingFile = temporaryDirectory.resolve("incident.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.start();
            recording.stop();
            recording.dump(recordingFile);
        }

        Path reportFile = JfrIncidentCorrelator.analyzeAndWrite(
                recordingFile, -1L, "none", new StackTraceElement[0]);

        assertTrue(Files.isRegularFile(reportFile));
        String report = Files.readString(reportFile);
        assertTrue(report.contains("Fiximizer — JFR / stall correlation"));
        assertTrue(report.contains("Java thread ID=-1"));
        assertTrue(report.contains("No JFR or stack classes could be attributed"));
    }
}

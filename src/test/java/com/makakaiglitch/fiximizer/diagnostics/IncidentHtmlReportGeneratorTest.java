package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentHtmlReportGeneratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesTimelineAndOnlyLinksExistingArtifacts() throws Exception {
        IncidentTimeline timeline = new IncidentTimeline(
                "incident-123", Instant.parse("2026-11-08T10:00:00Z"), 1_000_000L, 4);
        IncidentTimeline.Snapshot snapshot = timeline.recordCapture(
                Instant.parse("2026-11-08T10:00:02Z"), 2_001_000_000L, 2_500_000_000L);
        String stem = "stall-incident-123-snapshot-01";
        Files.writeString(temporaryDirectory.resolve(stem + ".txt"), "report");

        Path htmlFile = IncidentHtmlReportGenerator.write(temporaryDirectory, snapshot);
        String html = Files.readString(htmlFile);

        assertTrue(Files.isRegularFile(htmlFile));
        assertTrue(html.contains("incident-123"));
        assertTrue(html.contains("2500 ms"));
        assertTrue(html.contains(stem + ".txt") || html.contains(">Open</a>"));
        assertFalse(html.contains(stem + ".jfr\">Open</a>"));
    }

    @Test
    void escapesIncidentIdentifiersBeforeEmbeddingThemInHtml() {
        IncidentTimeline timeline = new IncidentTimeline(
                "incident<unsafe>", Instant.EPOCH, 1L, 4);
        String html = IncidentHtmlReportGenerator.render(temporaryDirectory, timeline.snapshot());
        assertTrue(html.contains("incident&lt;unsafe&gt;"));
        assertFalse(html.contains("<title>Fiximizer incident incident<unsafe>"));
    }
}

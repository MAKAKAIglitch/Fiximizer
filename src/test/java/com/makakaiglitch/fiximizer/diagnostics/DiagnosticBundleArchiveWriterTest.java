package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticBundleArchiveWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesOnlyFlatNamedEntries() throws Exception {
        Path archive = temporaryDirectory.resolve("bundle.zip");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8));
        entries.put("startup.txt", "startup report".getBytes(StandardCharsets.UTF_8));

        DiagnosticBundleArchiveWriter.write(archive, entries);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertEquals(2, zip.size());
            assertEquals("{\"schemaVersion\":1}", new String(
                    zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8));
            assertTrue(zip.getEntry("../outside.txt") == null);
        }
    }

    @Test
    void rejectsPathTraversalNames() {
        Map<String, byte[]> entries = Map.of("../outside.txt", new byte[]{1});
        assertThrows(IOException.class,
                () -> DiagnosticBundleArchiveWriter.write(
                        temporaryDirectory.resolve("bad.zip"), entries));
    }

    @Test
    void rejectsEntryAndTotalSizeOverflow() {
        Map<String, byte[]> oversizedEntry = Map.of(
                "too-large.txt",
                new byte[DiagnosticBundleArchiveWriter.MAX_ENTRY_BYTES + 1]);
        assertThrows(IOException.class,
                () -> DiagnosticBundleArchiveWriter.write(
                        temporaryDirectory.resolve("large-entry.zip"), oversizedEntry));

        Map<String, byte[]> oversizedTotal = new LinkedHashMap<>();
        for (int index = 0; index < 5; index++) {
            oversizedTotal.put("entry-" + index + ".txt",
                    new byte[DiagnosticBundleArchiveWriter.MAX_ENTRY_BYTES]);
        }
        assertThrows(IOException.class,
                () -> DiagnosticBundleArchiveWriter.write(
                        temporaryDirectory.resolve("large-total.zip"), oversizedTotal));
    }

    @Test
    void rejectsTooManyEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index <= DiagnosticBundleArchiveWriter.MAX_ENTRIES; index++) {
            entries.put("entry-" + index + ".txt", new byte[]{1});
        }
        assertThrows(IOException.class,
                () -> DiagnosticBundleArchiveWriter.write(
                        temporaryDirectory.resolve("many.zip"), entries));
    }
}

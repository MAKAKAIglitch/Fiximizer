package com.makakaiglitch.fiximizer.diagnostics;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bounded ZIP writer for the fixed, flat diagnostic-bundle entry names. */
public final class DiagnosticBundleArchiveWriter {
    static final int MAX_ENTRIES = 8;
    static final int MAX_ENTRY_BYTES = 256 * 1024;
    static final int MAX_TOTAL_BYTES = 1024 * 1024;

    private static final Pattern SAFE_ENTRY_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private DiagnosticBundleArchiveWriter() {
    }

    public static void write(Path output, Map<String, byte[]> entries) throws IOException {
        if (output == null || entries == null || entries.isEmpty()) {
            throw new IOException("A destination and at least one archive entry are required.");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IOException("Diagnostic archive entry limit exceeded.");
        }

        long totalBytes = 0L;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            byte[] content = entry.getValue();
            if (name == null || !SAFE_ENTRY_NAME.matcher(name).matches()
                    || ".".equals(name) || "..".equals(name)) {
                throw new IOException("Unsafe diagnostic archive entry name.");
            }
            if (content == null || content.length > MAX_ENTRY_BYTES) {
                throw new IOException("Diagnostic archive entry is missing or too large.");
            }
            totalBytes += content.length;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new IOException("Diagnostic archive size limit exceeded.");
            }
        }

        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Diagnostic archive destination has no parent directory.");
        }
        Files.createDirectories(parent);

        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(
                output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE));
             ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }
}

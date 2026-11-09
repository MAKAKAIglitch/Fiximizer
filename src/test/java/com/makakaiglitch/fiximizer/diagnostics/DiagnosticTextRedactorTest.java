package com.makakaiglitch.fiximizer.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticTextRedactorTest {
    @Test
    void removesIdentityNetworkAddressesPathsAndSecrets() {
        String source = """
                ERROR player=Matvey token=super-secret-token
                uuid=123e4567-e89b-12d3-a456-426614174000
                server=play.example.net:25565 ipv4=203.0.113.42 ipv6=2001:db8::1
                path=C:\\Users\\Matvey\\AppData\\Roaming\\.minecraft\\logs
                unix=/home/matvey/.minecraft/logs
                Authorization: Bearer abcdefghijklmnopqrstuvwxyz
                """;

        var result = DiagnosticTextRedactor.redact(source, "Matvey");

        assertFalse(result.text().contains("Matvey"));
        assertFalse(result.text().contains("super-secret-token"));
        assertFalse(result.text().contains("123e4567-e89b-12d3-a456-426614174000"));
        assertFalse(result.text().contains("203.0.113.42"));
        assertFalse(result.text().contains("2001:db8::1"));
        assertFalse(result.text().contains("play.example.net"));
        assertFalse(result.text().contains("C:\\Users\\"));
        assertFalse(result.text().contains("/home/matvey"));
        assertFalse(result.text().contains("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(result.redactedOccurrences() >= 8);
    }

    @Test
    void omitsChatLinesAndPreservesUsefulDiagnosticText() {
        String source = """
                [CHAT] this is a private message
                ERROR: tick took 2500 ms
                Received chat message from another player
                09:00:00 WARN: memory pressure is high
                """;

        var result = DiagnosticTextRedactor.redact(source, "Player");

        assertFalse(result.text().contains("private message"));
        assertFalse(result.text().contains("another player"));
        assertTrue(result.text().contains("tick took 2500 ms"));
        assertTrue(result.text().contains("memory pressure is high"));
        assertTrue(result.text().contains("09:00:00"),
                "IPv6 redaction must not remove ordinary log timestamps");
        assertEquals(2, result.omittedChatLines());
    }

    @Test
    void handlesNullAndEmptyInputs() {
        assertEquals("", DiagnosticTextRedactor.redact(null, null).text());
        assertEquals("", DiagnosticTextRedactor.redact("", "").text());
    }
}

package com.makakaiglitch.fiximizer.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextSanitizerTest {
    @Test
    void removesNulAndNormalizesWhitespace() {
        assertEquals(
                "Fiximizer OpenCL GPU status",
                TextSanitizer.sanitize("  Fiximizer" + (char) 0 + " OpenCL\nGPU\tstatus  ")
        );
    }

    @Test
    void preservesUnicodeText() {
        assertEquals("Видеокарта RX 6600", TextSanitizer.sanitize("Видеокарта RX 6600"));
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertEquals("", TextSanitizer.sanitize(null));
        assertEquals("", TextSanitizer.sanitize(""));
    }
}

package com.makakaiglitch.fiximizer.gpu;

import com.makakaiglitch.fiximizer.util.TextSanitizer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Decodes an OpenCL string using the byte count returned by clGet*Info.
 *
 * Native query buffers are often larger than the actual string. Decoding the
 * entire capacity exposes uninitialized native-memory bytes (including NUL
 * characters) in the UI and reports.
 */
public final class OpenClText {
    private OpenClText() {
    }

    public static String decode(ByteBuffer source, int byteLength) {
        if (source == null || byteLength <= 0 || byteLength > source.capacity()) {
            return "";
        }

        ByteBuffer bytes = source.duplicate();
        bytes.position(0);

        int actualLength = byteLength;
        for (int index = 0; index < byteLength; index++) {
            if (bytes.get(index) == 0) {
                actualLength = index;
                break;
            }
        }

        bytes.limit(actualLength);
        return TextSanitizer.sanitize(StandardCharsets.UTF_8.decode(bytes).toString());
    }
}

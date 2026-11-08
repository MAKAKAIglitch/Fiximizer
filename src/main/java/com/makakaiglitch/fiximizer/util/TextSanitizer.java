package com.makakaiglitch.fiximizer.util;

/**
 * Removes native control characters and normalizes whitespace before
 * externally supplied diagnostic strings are rendered in the game UI.
 */
public final class TextSanitizer {
    private TextSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        StringBuilder sanitized = new StringBuilder(value.length());
        boolean pendingSpace = false;

        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                if (!sanitized.isEmpty()) {
                    pendingSpace = true;
                }
                continue;
            }

            if (pendingSpace) {
                sanitized.append(' ');
                pendingSpace = false;
            }
            sanitized.appendCodePoint(codePoint);
        }

        return sanitized.toString();
    }
}

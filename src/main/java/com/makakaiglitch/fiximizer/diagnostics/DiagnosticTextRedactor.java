package com.makakaiglitch.fiximizer.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative text redaction for local diagnostic exports.
 *
 * This is a defence-in-depth filter, not a guarantee that arbitrary third-party
 * log output is safe to publish. The exporter deliberately keeps its input
 * scope small and shows the included files before writing an archive.
 */
public final class DiagnosticTextRedactor {
    private static final Pattern CHAT_LINE = Pattern.compile(
            "(?i)(?:\\[CHAT\\]|\\bchat message\\b|\\breceived chat\\b)");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\\\"'<>]+");
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)\\b(token|access[_ -]?token|refresh[_ -]?token|session(?:[_ -]?id|[_ -]?token)?|"
                    + "authorization|password|passwd|api[_ -]?key|secret)(\\s*[:=]\\s*)[^\\s,;]+");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:token|key|auth|code|password)=)[^&\\s]+");
    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![0-9.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![0-9.])");
    private static final Pattern IPV6 = Pattern.compile(
            "(?i)(?<![0-9a-f:])(?=[0-9a-f:]*::)(?:[0-9a-f]{0,4}:){1,7}[0-9a-f]{0,4}(?![0-9a-f:])"
                    + "|(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![0-9a-f:])");
    private static final Pattern HOST_FIELD = Pattern.compile(
            "(?i)\\b(server(?:[_ -]?address)?|remote(?:[_ -]?address)?|host|address)"
                    + "(\\s*[=:]\\s*)([a-z0-9.-]+(?::\\d{1,5})?)");
    private static final Pattern PLAYER_LABEL = Pattern.compile(
            "(?i)\\b(player|username|profile name)(\\s*[:=]\\s*|\\s+)([A-Za-z0-9_]{3,16})\\b");
    private static final Pattern CONNECTING_HOST = Pattern.compile(
            "(?i)\\b(connecting to|resolved host|remote server)\\s+([a-z0-9.-]+(?::\\d{1,5})?)");
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?i)(?:[A-Z]:[\\\\/]|\\\\\\\\)[^\\r\\n\\t\\\"<>]*");
    private static final Pattern UNIX_PATH = Pattern.compile(
            "(?i)(?:/home/|/users/|/root/|/mnt/|/tmp/|/var/|/opt/|/srv/|/etc/)[^\\s\\\"<>]*");

    private DiagnosticTextRedactor() {
    }

    public static RedactionResult redact(String source, String playerName) {
        if (source == null || source.isEmpty()) {
            return new RedactionResult("", 0, 0);
        }

        List<String> retainedLines = new ArrayList<>();
        int omittedChatLines = 0;
        for (String line : source.split("\\R", -1)) {
            if (CHAT_LINE.matcher(line).find()) {
                omittedChatLines++;
            } else {
                retainedLines.add(line);
            }
        }

        String value = String.join("\n", retainedLines);
        Counter counter = new Counter();

        if (playerName != null && !playerName.isBlank()) {
            value = replace(value, Pattern.compile(Pattern.quote(playerName),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), "[PLAYER]", counter);
        }
        value = replace(value, URL, "[URL]", counter);
        value = replace(value, BEARER, "Bearer [REDACTED]", counter);
        value = replace(value, TOKEN,
                matcher -> matcher.group(1) + matcher.group(2) + "[REDACTED]", counter);
        value = replace(value, QUERY_SECRET, "$1[REDACTED]", counter);
        value = replace(value, UUID, "[UUID]", counter);
        value = replace(value, IPV4, "[IP]", counter);
        value = replace(value, IPV6, "[IP]", counter);
        value = replace(value, HOST_FIELD,
                matcher -> matcher.group(1) + matcher.group(2) + "[HOST]", counter);
        value = replace(value, PLAYER_LABEL,
                matcher -> matcher.group(1) + matcher.group(2) + "[PLAYER]", counter);
        value = replace(value, CONNECTING_HOST,
                matcher -> matcher.group(1) + " [HOST]", counter);
        value = replace(value, WINDOWS_PATH, "[LOCAL_PATH]", counter);
        value = replace(value, UNIX_PATH, "[LOCAL_PATH]", counter);

        return new RedactionResult(value, counter.value, omittedChatLines);
    }

    private static String replace(
            String input,
            Pattern pattern,
            String replacement,
            Counter counter
    ) {
        return replace(input, pattern, matcher -> replacement, counter);
    }

    private static String replace(
            String input,
            Pattern pattern,
            java.util.function.Function<Matcher, String> replacement,
            Counter counter
    ) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer(input.length());
        while (matcher.find()) {
            counter.value++;
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement.apply(matcher)));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static final class Counter {
        private int value;
    }

    public record RedactionResult(String text, int redactedOccurrences, int omittedChatLines) {
    }
}

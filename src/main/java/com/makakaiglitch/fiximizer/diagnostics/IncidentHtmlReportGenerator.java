package com.makakaiglitch.fiximizer.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Writes a self-contained local HTML overview for one bounded incident timeline. */
public final class IncidentHtmlReportGenerator {
    private static final int CHART_WIDTH = 840;
    private static final int CHART_HEIGHT = 220;
    private static final int CHART_LEFT = 56;
    private static final int CHART_RIGHT = 18;
    private static final int CHART_TOP = 18;
    private static final int CHART_BOTTOM = 36;

    private IncidentHtmlReportGenerator() {
    }

    public static Path write(Path directory, IncidentTimeline.Snapshot incident) throws IOException {
        Files.createDirectories(directory);
        Path output = directory.resolve("incident-" + safeFilePart(incident.incidentId()) + ".html");
        Files.writeString(output, render(directory, incident), StandardCharsets.UTF_8);
        return output;
    }

    static String render(Path directory, IncidentTimeline.Snapshot incident) {
        StringBuilder html = new StringBuilder(16_000);
        long maximum = Math.max(1L, incident.peakDurationMillis());
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Fiximizer incident ").append(escape(incident.incidentId())).append("</title>\n")
                .append("<style>")
                .append("body{font:15px/1.55 system-ui,Segoe UI,sans-serif;max-width:1100px;margin:32px auto;padding:0 20px;color:#202630;background:#f5f7fa}")
                .append("h1,h2{line-height:1.2}h1{font-size:26px}h2{font-size:18px;margin-top:30px}")
                .append(".muted{color:#637083}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}")
                .append(".metric{background:white;border:1px solid #dce2ea;border-radius:10px;padding:14px}")
                .append(".metric strong{display:block;font-size:22px;margin-top:4px}")
                .append(".panel{background:white;border:1px solid #dce2ea;border-radius:10px;padding:16px;overflow-x:auto}")
                .append("table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:left;padding:9px 10px;border-bottom:1px solid #e5e9ef;white-space:nowrap}")
                .append("th{color:#526071;font-weight:600}a{color:#1759a6;text-decoration:none}a:hover{text-decoration:underline}")
                .append("svg{display:block;width:100%;height:auto;min-width:520px}.legend{font-size:12px;color:#637083}")
                .append("@media(prefers-color-scheme:dark){body{background:#151a22;color:#e6edf5}.muted,.legend{color:#a9b6c7}.metric,.panel{background:#202936;border-color:#3a4657}th,td{border-color:#3a4657}a{color:#8abaff}}")
                .append("</style>\n</head>\n<body>\n")
                .append("<h1>Fiximizer incident report</h1>\n")
                .append("<p class=\"muted\">Incident ID: <code>").append(escape(incident.incidentId()))
                .append("</code><br>Started: ").append(escape(incident.startedAt().toString())).append("</p>\n");

        html.append("<div class=\"grid\">");
        metric(html, "Snapshots", Integer.toString(incident.totalCaptures()));
        metric(html, "First observed duration", incident.firstDurationMillis() + " ms");
        metric(html, "Latest observed duration", incident.latestDurationMillis() + " ms");
        metric(html, "Peak in retained history", incident.peakDurationMillis() + " ms");
        metric(html, "Change since first", signed(incident.durationChangeMillis()) + " ms");
        html.append("</div>\n");

        html.append("<h2>Stall duration over time</h2><div class=\"panel\">");
        appendChart(html, incident, maximum);
        html.append("<p class=\"legend\">Measurements are watchdog observations of one active NeoForge event span, not a full per-tick trace.</p>")
                .append("</div>\n");

        html.append("<h2>Snapshots and local files</h2><div class=\"panel\"><table>")
                .append("<thead><tr><th>#</th><th>Elapsed</th><th>Duration</th><th>Report</th><th>JFR</th><th>JFR analysis</th><th>Correlation</th></tr></thead><tbody>");
        for (IncidentTimeline.Capture capture : incident.captures()) {
            String safeIncidentId = safeFilePart(incident.incidentId());
        String stem = "stall-" + safeIncidentId + "-snapshot-"
                    + String.format(Locale.ROOT, "%02d", capture.number());
            html.append("<tr><td>").append(capture.number()).append("</td><td>")
                    .append(capture.elapsedMillis()).append(" ms</td><td>")
                    .append(capture.durationMillis()).append(" ms</td>");
            appendFileCell(html, directory, stem + ".txt");
            appendFileCell(html, directory, stem + ".jfr");
            appendFileCell(html, directory, stem + "-analysis.txt");
            appendFileCell(html, directory, stem + "-correlation.txt");
            html.append("</tr>");
        }
        html.append("</tbody></table></div>\n");

        html.append("<h2>How to interpret this page</h2>")
                .append("<p class=\"muted\">This report groups repeated captures from one continuous event. A longer duration can indicate that the event remained blocked or slow, but does not identify a cause by itself. JFR CPU samples are statistical, allocation weights are estimates, and mod correlation is heuristic. Inspect the linked text/JFR reports before changing mod settings.</p>")
                .append("</body></html>");
        return html.toString();
    }

    private static void appendChart(
            StringBuilder html,
            IncidentTimeline.Snapshot incident,
            long maximumDuration
    ) {
        int plotWidth = CHART_WIDTH - CHART_LEFT - CHART_RIGHT;
        int plotHeight = CHART_HEIGHT - CHART_TOP - CHART_BOTTOM;
        int bottom = CHART_TOP + plotHeight;
        html.append("<svg viewBox=\"0 0 ").append(CHART_WIDTH).append(' ')
                .append(CHART_HEIGHT).append("\" role=\"img\" aria-label=\"Stall duration timeline\">");

        for (int band = 0; band <= 4; band++) {
            int y = CHART_TOP + (plotHeight * band / 4);
            long value = maximumDuration * (4 - band) / 4;
            html.append("<line x1=\"").append(CHART_LEFT).append("\" y1=\"").append(y)
                    .append("\" x2=\"").append(CHART_WIDTH - CHART_RIGHT).append("\" y2=\"")
                    .append(y).append("\" stroke=\"#9aa8b8\" stroke-opacity=\".28\"/>")
                    .append("<text x=\"").append(CHART_LEFT - 8).append("\" y=\"").append(y + 4)
                    .append("\" text-anchor=\"end\" font-size=\"11\" fill=\"currentColor\">")
                    .append(value).append("</text>");
        }

        StringBuilder points = new StringBuilder();
        int pointIndex = 0;
        int pointCount = incident.captures().size();
        for (IncidentTimeline.Capture capture : incident.captures()) {
            int x = pointCount <= 1
                    ? CHART_LEFT + plotWidth / 2
                    : CHART_LEFT + (int) Math.round(
                            capture.elapsedMillis() * 1.0 / Math.max(1L,
                                    incident.captures().getLast().elapsedMillis()) * plotWidth);
            int y = bottom - (int) Math.round(
                    capture.durationMillis() * 1.0 / maximumDuration * plotHeight);
            if (pointIndex > 0) {
                points.append(' ');
            }
            points.append(x).append(',').append(y);
            html.append("<circle cx=\"").append(x).append("\" cy=\"").append(y)
                    .append("\" r=\"5\" fill=\"#2473c5\"><title>Capture ")
                    .append(capture.number()).append(": ").append(capture.durationMillis())
                    .append(" ms</title></circle>");
            pointIndex++;
        }

        html.append("<polyline points=\"").append(points)
                .append("\" fill=\"none\" stroke=\"#2473c5\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>")
                .append("<text x=\"").append(CHART_LEFT).append("\" y=\"")
                .append(CHART_HEIGHT - 8).append("\" font-size=\"11\" fill=\"currentColor\">Incident start</text>")
                .append("<text x=\"").append(CHART_WIDTH - CHART_RIGHT).append("\" y=\"")
                .append(CHART_HEIGHT - 8).append("\" text-anchor=\"end\" font-size=\"11\" fill=\"currentColor\">Elapsed time (ms)</text>")
                .append("</svg>");
    }

    private static void metric(StringBuilder html, String label, String value) {
        html.append("<div class=\"metric\"><span class=\"muted\">")
                .append(escape(label)).append("</span><strong>")
                .append(escape(value)).append("</strong></div>");
    }

    private static void appendFileCell(StringBuilder html, Path directory, String filename) {
        Path file = directory.resolve(filename);
        if (Files.isRegularFile(file)) {
            html.append("<td><a href=\"").append(escape(filename)).append("\">Open</a></td>");
        } else {
            html.append("<td class=\"muted\">—</td>");
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String safeFilePart(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String signed(long value) {
        return value > 0L ? "+" + value : Long.toString(value);
    }
}

package com.makakaiglitch.fiximizer.startup;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

import static com.makakaiglitch.fiximizer.startup.StartupTimeline.Stage;

/**
 * Records Fiximizer lifecycle milestones without installing transformers,
 * mixins, classpath scanners, or extra runtime dependencies.
 */
public final class StartupProfiler {
    private static final StartupTimeline TIMELINE = new StartupTimeline();
    private static final AtomicLong JVM_UPTIME_AT_FIRST_TITLE = new AtomicLong(-1L);

    private StartupProfiler() {
    }

    public static void mark(Stage stage) {
        boolean recorded = TIMELINE.mark(stage, System.nanoTime());
        if (recorded && stage == Stage.FIRST_TITLE_SCREEN_TICK) {
            // Query JVM uptime only after the title screen is already visible.
            // This keeps management-bean initialization off the critical path.
            try {
                JVM_UPTIME_AT_FIRST_TITLE.compareAndSet(
                        -1L,
                        ManagementFactory.getRuntimeMXBean().getUptime()
                );
            } catch (RuntimeException ignored) {
                // Phase timings remain available even if the JVM management
                // interface is restricted by a custom runtime.
            }
        }
    }

    public static boolean wasReached(Stage stage) {
        return TIMELINE.wasReached(stage);
    }

    public static long elapsedMillis(Stage start, Stage end) {
        return TIMELINE.elapsedMillis(start, end);
    }

    public static long millisFromJvmStartToFirstTitle() {
        return JVM_UPTIME_AT_FIRST_TITLE.get();
    }

    public static long millisFromJvmStartToStableClient() {
        long uptimeAtTitle = JVM_UPTIME_AT_FIRST_TITLE.get();
        long titleToStable = elapsedMillis(
                Stage.FIRST_TITLE_SCREEN_TICK,
                Stage.STABLE_CLIENT_READY
        );
        if (uptimeAtTitle < 0L || titleToStable < 0L) {
            return -1L;
        }
        return uptimeAtTitle + titleToStable;
    }

    /**
     * Produces a privacy-conscious text report. It contains durations and
     * milestone states only: no player names, paths, server addresses or mod list.
     */
    public static String createReport() {
        StringBuilder report = new StringBuilder(512);
        report.append("Fiximizer startup report\n");
        report.append("========================\n");
        appendMetric(report, "JVM start -> first title screen", millisFromJvmStartToFirstTitle());
        appendMetric(report, "JVM start -> stable client", millisFromJvmStartToStableClient());
        appendMetric(report, "Fiximizer mod entry -> client setup complete",
                elapsedMillis(Stage.FIXIMIZER_MOD_CONSTRUCTED, Stage.CLIENT_SETUP_COMPLETED));
        appendMetric(report, "Fiximizer client setup",
                elapsedMillis(Stage.CLIENT_SETUP_STARTED, Stage.CLIENT_SETUP_COMPLETED));
        appendMetric(report, "First title screen -> stable client",
                elapsedMillis(Stage.FIRST_TITLE_SCREEN_TICK, Stage.STABLE_CLIENT_READY));
        appendMetric(report, "Optional compatibility scan",
                elapsedMillis(Stage.OPTIONAL_MOD_SCAN_STARTED, Stage.OPTIONAL_MOD_SCAN_COMPLETED));
        report.append("Optional services scheduled: ")
                .append(wasReached(Stage.OPTIONAL_SERVICES_SCHEDULED) ? "yes" : "not yet")
                .append('\n');
        report.append("\nNote: JVM-to-title includes Minecraft, NeoForge, all mods, resources and machine startup. ");
        report.append("It is not an attribution of time to individual mods.\n");
        report.append("The report intentionally excludes player data, file paths, server addresses and mod lists.\n");
        return report.toString();
    }

    private static void appendMetric(StringBuilder report, String label, long millis) {
        report.append(label).append(": ");
        if (millis < 0L) {
            report.append("not measured yet");
        } else {
            report.append(millis).append(" ms");
        }
        report.append('\n');
    }
}

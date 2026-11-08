package com.makakaiglitch.fiximizer.memory;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * Detects memory optimizations exposed by the currently running JVM.
 *
 * The detector uses actual VM-option availability rather than assuming that
 * every vendor implements every HotSpot feature for a given Java major version.
 * Results are cached for the lifetime of the game process.
 */
public final class RuntimeMemoryCapabilities {
    public static final int COMPACT_HEADERS_MIN_JAVA = 25;
    public static final int COMPACT_HEADERS_CLASS_LIMIT = 4_000_000;

    private static volatile Snapshot cached;

    private RuntimeMemoryCapabilities() {
    }

    public static Snapshot current() {
        Snapshot current = cached;
        if (current != null) {
            return current;
        }

        synchronized (RuntimeMemoryCapabilities.class) {
            current = cached;
            if (current == null) {
                current = detectNow();
                cached = current;
            }
        }
        return current;
    }

    /**
     * Pure policy method used by tests. JDK 24 is intentionally excluded:
     * Compact Object Headers were experimental there and required an extra
     * experimental-options unlock. JDK 25 promoted the flag to product status.
     */
    static Snapshot evaluate(
            int javaMajor,
            boolean compactHeadersOptionAvailable,
            boolean compactHeadersEnabled,
            boolean g1Available,
            int loadedClassCount
    ) {
        boolean supported = javaMajor >= COMPACT_HEADERS_MIN_JAVA
                && compactHeadersOptionAvailable;
        boolean classLimitReached = supported
                && loadedClassCount >= COMPACT_HEADERS_CLASS_LIMIT;

        boolean recommendCompactHeaders = supported
                && !compactHeadersEnabled
                && !classLimitReached;

        return new Snapshot(
                Math.max(0, javaMajor),
                supported,
                compactHeadersEnabled,
                g1Available,
                Math.max(0, loadedClassCount),
                classLimitReached,
                recommendCompactHeaders
        );
    }

    private static Snapshot detectNow() {
        int javaMajor = parseJavaMajor(System.getProperty("java.specification.version", ""));
        int loadedClasses = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();

        boolean g1 = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .anyMatch(bean -> bean.getName().toLowerCase(Locale.ROOT).contains("g1"));

        boolean compactAvailable = false;
        boolean compactEnabled = false;

        // This option is not present on Java 21. On HotSpot JDK 25+ it is a
        // product option; checking the VM avoids vendor/version assumptions.
        if (javaMajor >= COMPACT_HEADERS_MIN_JAVA) {
            try {
                HotSpotDiagnosticMXBean bean =
                        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
                if (bean != null) {
                    VMOption option = bean.getVMOption("UseCompactObjectHeaders");
                    compactAvailable = true;
                    compactEnabled = Boolean.parseBoolean(option.getValue());
                }
            } catch (IllegalArgumentException | UnsupportedOperationException
                     | SecurityException | LinkageError ignored) {
                // Not a supported HotSpot VM option on this runtime.
            }
        }

        return evaluate(javaMajor, compactAvailable, compactEnabled, g1, loadedClasses);
    }

    static int parseJavaMajor(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }

        String normalized = version.trim();
        if (normalized.startsWith("1.")) {
            normalized = normalized.substring(2);
        }

        int end = 0;
        while (end < normalized.length() && Character.isDigit(normalized.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }

        try {
            return Integer.parseInt(normalized.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public record Snapshot(
            int javaMajor,
            boolean compactObjectHeadersSupported,
            boolean compactObjectHeadersEnabled,
            boolean g1Available,
            int loadedClassCount,
            boolean compactObjectHeadersClassLimitReached,
            boolean shouldRecommendCompactObjectHeaders
    ) {
        public boolean isJava25OrNewer() {
            return javaMajor >= COMPACT_HEADERS_MIN_JAVA;
        }

        public boolean usesJava21Fallback() {
            return javaMajor == 21 || !compactObjectHeadersSupported;
        }
    }
}

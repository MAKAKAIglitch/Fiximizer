package com.makakaiglitch.fiximizer.diagnostics;

import java.io.IOException;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Best-effort mapping of stack frames to the mod JAR that contains their
 * classes. Results are evidence for investigation, not proof of causation.
 *
 * Index construction is intended for a background worker. Directory walking
 * is lazy and both directory/JAR scanning share a strict class-count limit.
 */
public final class ModAttribution {
    private static final Pattern TRANSFORMER = Pattern.compile(
            "(?:TRANSFORMER|LAYER SERVICE)/([A-Za-z0-9_.-]+)@[^/]+/");
    private static final int MAX_INDEXED_CLASSES = 250_000;
    private static volatile Index cachedIndex;

    private ModAttribution() {
    }

    /** Build the immutable mod/class index before the first stall occurs. */
    public static void warmUp() {
        index();
    }

    public static List<Evidence> analyze(ThreadInfo[] threads, long triggerThreadId) {
        if (threads == null || threads.length == 0) {
            return List.of();
        }

        Index index = index();
        if (index.metadata.isEmpty()) {
            return List.of();
        }

        Map<String, MutableEvidence> hits = new LinkedHashMap<>();
        for (ThreadInfo thread : threads) {
            if (thread == null) {
                continue;
            }

            boolean trigger = thread.getThreadId() == triggerThreadId;
            StackTraceElement[] stack = thread.getStackTrace();
            for (int depth = 0; depth < stack.length; depth++) {
                String modId = resolve(stack[depth], index);
                if (modId == null || "fiximizer".equals(modId)) {
                    continue;
                }

                MutableEvidence hit = hits.computeIfAbsent(modId, id -> {
                    ModMetadata metadata = index.metadata.get(id);
                    return new MutableEvidence(id,
                            metadata == null ? id : metadata.displayName,
                            metadata == null ? "unknown" : metadata.version);
                });

                if (trigger) {
                    hit.triggerFrames++;
                    if (depth < 5) {
                        hit.topTriggerFrames++;
                    }
                    if (hit.firstTriggerFrame == null) {
                        hit.firstTriggerFrame = stack[depth].toString();
                        hit.firstThreadName = thread.getThreadName();
                    }
                } else {
                    hit.otherThreadFrames++;
                }
            }
        }

        return hits.values().stream()
                .sorted(Comparator
                        .comparingInt(MutableEvidence::score).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (MutableEvidence hit) -> hit.topTriggerFrames).reversed())
                        .thenComparing(hit -> hit.modId))
                .limit(10)
                .map(MutableEvidence::freeze)
                .toList();
    }

    /**
     * Resolve one JFR/stack class name to a loaded mod. Exact class ownership
     * wins; ambiguous classes stop resolution; package-prefix matches are
     * marked as heuristic so reports can distinguish the evidence quality.
     *
     * The index must be warmed on a background worker before using this from
     * report generation.
     */
    public static Ownership resolveClassOwner(String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        Index index = cachedIndex;
        if (index == null) {
            return null;
        }

        String owner = index.classOwners.get(className);
        if (owner != null) {
            return ownership(owner, index, true);
        }
        if (index.ambiguousClasses.contains(className)) {
            return null;
        }

        int dot = className.lastIndexOf('.');
        while (dot > 0) {
            String packageName = className.substring(0, dot);
            owner = index.packageOwners.get(packageName);
            if (owner != null) {
                return ownership(owner, index, false);
            }
            if (index.ambiguousPackages.contains(packageName)) {
                return null;
            }
            dot = packageName.lastIndexOf('.');
        }
        return null;
    }

    private static Ownership ownership(String modId, Index index, boolean exactClassMatch) {
        ModMetadata metadata = index.metadata.get(modId);
        if (metadata == null) {
            return null;
        }
        return new Ownership(modId, metadata.displayName, metadata.version, exactClassMatch);
    }

    public record Ownership(
            String modId,
            String displayName,
            String version,
            boolean exactClassMatch
    ) {
    }

    static String confidence(int topTriggerFrames, int triggerFrames) {
        if (topTriggerFrames >= 3 && triggerFrames >= 5) {
            return "HIGH";
        }
        if (topTriggerFrames >= 1 && triggerFrames >= 2) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static String resolve(StackTraceElement frame, Index index) {
        String rawClass = frame.getClassName();
        Matcher marker = TRANSFORMER.matcher(rawClass);
        if (marker.find()) {
            String transformedModId = marker.group(1);
            if (index.metadata.containsKey(transformedModId)) {
                return transformedModId;
            }
            int slash = rawClass.lastIndexOf('/');
            if (slash >= 0) {
                rawClass = rawClass.substring(slash + 1);
            }
        }

        String exact = index.classOwners.get(rawClass);
        if (exact != null) {
            return exact;
        }
        if (index.ambiguousClasses.contains(rawClass)) {
            return null;
        }

        int dot = rawClass.lastIndexOf('.');
        while (dot > 0) {
            String packageName = rawClass.substring(0, dot);
            String owner = index.packageOwners.get(packageName);
            if (owner != null) {
                return owner;
            }
            if (index.ambiguousPackages.contains(packageName)) {
                return null;
            }
            dot = packageName.lastIndexOf('.');
        }
        return null;
    }

    private static Index index() {
        Index current = cachedIndex;
        if (current != null) {
            return current;
        }

        synchronized (ModAttribution.class) {
            current = cachedIndex;
            if (current == null) {
                current = buildIndex();
                cachedIndex = current;
            }
            return current;
        }
    }

    private static Index buildIndex() {
        Map<String, ModMetadata> metadata = new LinkedHashMap<>();
        Map<String, String> classes = new HashMap<>();
        Map<String, String> packages = new HashMap<>();
        Set<String> ambiguousClasses = new HashSet<>();
        Set<String> ambiguousPackages = new HashSet<>();
        int indexed = 0;

        try {
            ModList modList = ModList.get();
            if (modList == null) {
                return new Index(metadata, classes, packages,
                        ambiguousClasses, ambiguousPackages, 0);
            }

            for (IModFileInfo fileInfo : modList.getModFiles()) {
                List<? extends IModInfo> mods = fileInfo.getMods();
                for (IModInfo mod : mods) {
                    metadata.put(mod.getModId(), new ModMetadata(
                            mod.getDisplayName(), String.valueOf(mod.getVersion())));
                }

                // A JAR containing multiple mod IDs cannot be assigned to one
                // mod without inventing ownership, so keep its classes unknown.
                if (mods.size() != 1 || indexed >= MAX_INDEXED_CLASSES) {
                    continue;
                }

                String owner = mods.getFirst().getModId();
                Path file = fileInfo.getFile().getFilePath();
                try {
                    if (Files.isDirectory(file)) {
                        indexed += scanDirectory(file, owner, classes, packages,
                                ambiguousClasses, ambiguousPackages, indexed);
                    } else if (Files.isRegularFile(file)) {
                        indexed += scanJar(file, owner, classes, packages,
                                ambiguousClasses, ambiguousPackages, indexed);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // One inaccessible or unusual mod must not disable diagnostics.
                }

                if (indexed >= MAX_INDEXED_CLASSES) {
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // ModList may be unavailable during early startup or teardown.
        }

        return new Index(metadata, classes, packages,
                ambiguousClasses, ambiguousPackages, indexed);
    }

    private static int scanDirectory(
            Path root,
            String owner,
            Map<String, String> classes,
            Map<String, String> packages,
            Set<String> ambiguousClasses,
            Set<String> ambiguousPackages,
            int alreadyIndexed
    ) throws IOException {
        int added = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            var iterator = paths.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext() && alreadyIndexed + added < MAX_INDEXED_CLASSES) {
                Path path = iterator.next();
                String name = root.relativize(path).toString().replace('\\', '/');
                if (isClassEntry(name)) {
                    addClass(name, owner, classes, packages, ambiguousClasses, ambiguousPackages);
                    added++;
                }
            }
        }
        return added;
    }

    private static int scanJar(
            Path file,
            String owner,
            Map<String, String> classes,
            Map<String, String> packages,
            Set<String> ambiguousClasses,
            Set<String> ambiguousPackages,
            int alreadyIndexed
    ) throws IOException {
        int added = 0;
        try (JarFile jar = new JarFile(file.toFile(), false)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()
                    && alreadyIndexed + added < MAX_INDEXED_CLASSES) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && isClassEntry(entry.getName())) {
                    addClass(entry.getName(), owner, classes, packages,
                            ambiguousClasses, ambiguousPackages);
                    added++;
                }
            }
        }
        return added;
    }

    private static boolean isClassEntry(String name) {
        return name.endsWith(".class")
                && !name.startsWith("META-INF/versions/")
                && !name.equals("module-info.class")
                && !name.endsWith("/module-info.class")
                && !name.equals("package-info.class")
                && !name.endsWith("/package-info.class");
    }

    private static void addClass(
            String entry,
            String owner,
            Map<String, String> classes,
            Map<String, String> packages,
            Set<String> ambiguousClasses,
            Set<String> ambiguousPackages
    ) {
        String className = entry.substring(0, entry.length() - 6).replace('/', '.');
        putUnique(classes, ambiguousClasses, className, owner);
        int split = className.lastIndexOf('.');
        if (split > 0) {
            putUnique(packages, ambiguousPackages, className.substring(0, split), owner);
        }
    }

    private static void putUnique(
            Map<String, String> values,
            Set<String> ambiguous,
            String key,
            String owner
    ) {
        if (ambiguous.contains(key)) {
            return;
        }
        String previous = values.putIfAbsent(key, owner);
        if (previous != null && !previous.equals(owner)) {
            values.remove(key);
            ambiguous.add(key);
        }
    }

    public record Evidence(
            String modId,
            String displayName,
            String version,
            int triggerFrames,
            int otherThreadFrames,
            int topTriggerFrames,
            int score,
            String confidence,
            String firstTriggerFrame,
            String firstThreadName
    ) {
    }

    private record ModMetadata(String displayName, String version) {
    }

    private record Index(
            Map<String, ModMetadata> metadata,
            Map<String, String> classOwners,
            Map<String, String> packageOwners,
            Set<String> ambiguousClasses,
            Set<String> ambiguousPackages,
            int indexedClasses
    ) {
    }

    private static final class MutableEvidence {
        private final String modId;
        private final String displayName;
        private final String version;
        private int triggerFrames;
        private int otherThreadFrames;
        private int topTriggerFrames;
        private String firstTriggerFrame;
        private String firstThreadName;

        private MutableEvidence(String modId, String displayName, String version) {
            this.modId = modId;
            this.displayName = displayName;
            this.version = version;
        }

        private int score() {
            return topTriggerFrames * 100 + triggerFrames * 10 + Math.min(otherThreadFrames, 50);
        }

        private Evidence freeze() {
            return new Evidence(
                    modId, displayName, version, triggerFrames, otherThreadFrames,
                    topTriggerFrames, score(),
                    ModAttribution.confidence(topTriggerFrames, triggerFrames),
                    firstTriggerFrame, firstThreadName
            );
        }
    }
}

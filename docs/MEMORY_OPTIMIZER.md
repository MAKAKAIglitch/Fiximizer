# Memory Optimizer — implementation notes

## Design target

The first Memory Optimizer stage is intentionally conservative. Its job is to provide a bounded, opt-in way for Fiximizer and compatible adapters to reuse identical immutable strings while keeping CPU work and retained cache state predictable.

It is **not** a JVM heap compressor, object graph rewriter, or substitute for profiling. Java owns Minecraft's object heap and garbage collection; Fiximizer only canonicalizes values explicitly passed through its API.

## Current implementation

### 1. Explicit string canonicalization

Entry point:

\`com.makakaiglitch.fiximizer.memory.MemoryOptimizer.canonicalize(String)\`

An integration must use the returned reference. Passing a value to the API without storing the returned value cannot change the caller's object graph.

The current adapter is limited to selected optional-mod metadata discovered by \`CompatibilityDetector\` (mod IDs and version strings). This is a useful integration seam, but it is a low-volume workload: meaningful pack-wide memory savings have **not** been demonstrated.

### 2. Bounded weak table

\`StringDeduplicator\` uses a fixed, four-way set-associative table:

| Property | Current value |
|---|---:|
| Buckets | 16 |
| Ways per bucket | 4 |
| Maximum live table entries | 64 |
| Minimum string length | 8 UTF-16 code units |
| CAS attempts per call | 3 |
| Reference strength | Weak |
| Background cleanup thread | None |

Lookup work is bounded to four slots per call. Hash equality is only a prefilter; full \`String.equals\` is always checked before a reference is reused. A hash collision can evict a lookup entry, but it cannot cause unrelated strings to be merged.

If contention persists through three CAS attempts, the operation returns its original input. It never waits on a monitor or spins indefinitely on the caller's thread.

The cache:
- skips \`null\` and short strings;
- does not create a replacement string;
- does not retain values strongly;
- has a fixed upper bound;
- uses no periodic scan, heap walk, timer, or worker thread;
- is initialized only after the user enables the feature and an eligible value reaches the API;
- is cleared and detached when the feature is disabled.

The table and its counters still have a memory cost. It is therefore kept small, lazy, and opt-in.

### 3. Honest counters

The Memory tab displays:
- **duplicate hits** — a different String instance with equal contents was replaced by an already-live instance;
- **registrations** — values added to the weak lookup table;
- **stale-slot reuse** — a cleared weak-reference slot was reused;
- **collision evictions** — a live lookup entry was displaced because its bucket was full;
- **contention bypasses** — an operation returned its input after bounded CAS retries;
- **occupied slots / capacity** — current bounded table occupancy.

Calling the API repeatedly with the exact same String object is not counted as a deduplication hit. A hit count of zero means there is no observed memory saving from this table.

These counters do not estimate bytes saved. Exact savings require object-layout and retained-heap measurements on a controlled workload.

### 4. JVM-managed G1 String Deduplication

The JVM Advisor can optionally append:

\`-XX:+UseStringDeduplication\`

It does so only if the active garbage collector is detected as G1 and the user enables the recommendation. The setting is off by default.

This is a JVM-wide mechanism and is separate from Fiximizer's explicit string table. It requires restarting Minecraft. G1 may spend additional CPU time doing string deduplication work, so its impact must be measured on the user's workload before keeping the option enabled.

## What this stage does not do

- It does not scan all live objects or inspect arbitrary heap contents.
- It does not rewrite references held by Minecraft or third-party mods.
- It does not merge mutable NBT compounds, lists, arrays, model data, or VoxelShapes.
- It does not compress the Java heap or lower \`-Xmx\`.
- It does not move Java objects to Rust, native memory, or VRAM.
- It does not promise a fixed amount of RAM savings.

Those features require a workload-specific adapter, clear data ownership, mutation guarantees, and repeatable benchmarks. A generic transformation of third-party object graphs is not a safe extension strategy.

## CPU and memory acceptance tests

For every future adapter:

1. Identify the exact allocation or retained-object hotspot using JFR, class histograms, or heap analysis.
2. Use the same Minecraft build, mod list, world, Java version, heap settings, and warm-up procedure for before/after runs.
3. Measure retained heap after comparable GC points, allocation rate, GC pause time, process memory, CPU use, and tick/frame-time percentiles.
4. Include the memory and CPU cost of the deduplication table itself.
5. Test equality, hash collisions, concurrency, table bounds, lifecycle invalidation, and mutation isolation.
6. Keep the feature disabled by default until repeatable tests demonstrate a net benefit and no compatibility regression.

A strategy is accepted only when it lowers retained memory for a documented workload without a material CPU or gameplay-timing regression. If it does not produce a net benefit, it should be disabled or removed rather than retained for feature-count purposes.

## Why this module is Java-first

String identity, weak references, object lifetime, and GC behavior belong to the JVM. Native Rust code cannot automatically reduce Java heap just by being faster at hashing. JNI/FFM boundaries add packaging, memory-accounting, and call-boundary complexity.

Rust may be evaluated later for a measured, CPU-heavy bulk operation on primitive byte buffers (for example, a codec or data transformation) when a large batch amortizes the native-call cost. Such a module must prove a net end-to-end benefit and account for off-heap memory. It is not part of the current string deduplication path.


## Runtime-selected JVM memory backend (Java 21 / 25)

Fiximizer compiles for Java 21 but inspects the JVM that is actually running the game. Selection is based on exposed VM capabilities, not only on the version string.

### Java 25+ HotSpot

If the running VM exposes the product option \`UseCompactObjectHeaders\`, the JVM Advisor checks its current value and the loaded-class count. When the option is available, not already enabled, and below the 4,000,000-class limit, the recommended launcher arguments include:

\`-XX:+UseCompactObjectHeaders\`

Compact Object Headers reduce object headers from 96/128 bits to 64 bits on supported 64-bit HotSpot architectures. JDK 25 promotes this from the experimental JDK 24 feature to a product option. It is not enabled by default in JDK 25. The setting takes effect only after restarting Minecraft.

The VM option is queried through \`HotSpotDiagnosticMXBean\`. If a non-HotSpot VM or vendor build does not expose the option, Fiximizer does not guess or add the flag. If the class limit is reached, the flag is not recommended.

### Java 21 and compatible fallback

Java 21 does not expose the JDK 25 Compact Object Headers option. Fiximizer keeps Java 21-compatible bytecode and its bounded explicit String canonicalization API. G1 String Deduplication remains a separate, opt-in recommendation because it may add garbage-collection CPU work.

### Runtime and build compatibility are separate

- The mod's classes are compiled with \`--release 21\`; Java 21 remains the bytecode baseline.
- Gradle 9.2.1 can run on JDK 21 or JDK 25. The Java 21 compiler toolchain is resolved separately through the configured Foojay resolver.
- CI builds and runs unit tests with Gradle on both JDK 21 and JDK 25. This verifies the build toolchain and runtime capability detector; it does not replace a full Minecraft 1.21.1 client launch test on JDK 25.
- Minecraft 1.21.1 / NeoForge's documented runtime baseline remains Java 21. Java 25-specific optimizations are used only when the player is actually running a JVM that exposes them.

No garbage collector is switched automatically. Fiximizer leaves the JVM's selected collector unchanged.

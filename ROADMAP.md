# Fiximizer Roadmap

> **Product direction:** Fiximizer is being built into a modular performance optimizer for Minecraft modpacks. The first proving ground is a real Minecraft 1.21.1 NeoForge modpack; the long-term goal is a useful, configurable optimizer that can support many different packs and, over time, more Minecraft versions and loaders.

This document is an engineering roadmap, not a release-date promise. Priorities can change when measurements, compatibility testing, or community feedback reveal a more important problem.

## 1. Product vision

Fiximizer should help players understand and improve the performance of an entire modpack, not just display charts. It will combine three cooperating parts:

1. **A measurement engine** that finds expensive work and quantifies its cost.
2. **A safe optimization core** that applies only well-understood, reversible policies.
3. **Optional compatibility modules** that can profile or optimize specific mods when their behavior and APIs are understood.

The same core should work across different modpacks. A pack-specific investigation is a starting point for discovering reusable improvements, not a reason to hard-code one player's configuration into the mod.

### What “general-purpose optimizer” means

- The core handles common tasks such as profiling, workload classification, memory-aware policies, bounded caches, and optimization settings.
- Per-mod adapters provide deeper optimizations for systems that need mod-specific knowledge.
- Each optimization is independently configurable and checks whether it is compatible with the installed versions and other performance mods.
- Features that cannot be measured or safely applied remain diagnostic recommendations until there is enough evidence to automate them.

The current alpha is the foundation: it already has runtime measurements, stall diagnostics, JFR reports, workload profiles, an adaptive scheduler for Fiximizer-owned tasks, and several optional integration points. It is not yet a universal automatic FPS booster. The roadmap is intended to close that gap with measured, incremental work.

## 2. Current foundation

The following capabilities are already present in the repository:

- [x] Minecraft 1.21.1 / NeoForge foundation and bilingual dashboard.
- [x] Five workload profiles and a hysteresis-based memory-pressure guard.
- [x] Adaptive worker scheduler with bounded queues and task timing telemetry.
- [x] Client render-call, client tick, and integrated-server tick measurements.
- [x] Create contraption and Sable physics passive telemetry.
- [x] Stall watchdog, JVM thread snapshots, and bounded rolling JFR recording.
- [x] JFR hotspot analysis, incident correlation, heuristic mod attribution, and local HTML reports.
- [x] Detection and independent feature gates for Create, Create Aeronautics, Sable, Distant Horizons, and Voxy.
- [x] JVM argument advisor.
- [x] GPU/OpenCL discovery, a manual CPU-versus-GPU correctness benchmark, and an explicit disposable-data VRAM cache API.
- [x] Deferred startup of optional diagnostics and hardware probes.
- [x] Windows automatic builder and CI builds for Windows and Linux.

**Important distinction:** detection, telemetry, and an extension point are not the same as an optimization. The first deeper optimizations still need profiling evidence, implementation, and compatibility tests.

## 3. Development phases

### Phase 1 — Make measurements trustworthy and cheap

**Priority: highest.** Optimization is only useful when the bottleneck and the cost of measuring it are both understood.

#### 1.1 Startup Profiler and Launch-Time Optimization

**Goal:** reduce the time and resource cost between launching a modpack and reaching the title screen, within the parts of startup Fiximizer can safely influence.

- Measure the earliest available Fiximizer lifecycle points through mod initialization, client setup, resource reload milestones, and the first stable title-screen tick.
- Keep Fiximizer's own initialization cost separate from the time spent by Minecraft, the loader, the launcher, and other mods. Do not report a partial measurement as total process startup time.
- Use opt-in JFR/startup traces to identify class loading, mod initialization, resource reload, and filesystem bottlenecks where the available loader/JVM events expose them.
- Keep hardware discovery, GPU/OpenCL probing, deep profiling, mod-JAR attribution, and report indexing lazy or deferred unless a feature is explicitly requested.
- Remove redundant scans, repeated parsing, eager class loading, and synchronous disk work from the startup-critical path.
- Introduce small, versioned caches only for deterministic metadata that can be validated and safely rebuilt. Include Minecraft, loader, mod, configuration, and cache-format fingerprints where relevant.
- Profile cold starts separately from warm starts and compare equivalent launches.
- Never skip required mod callbacks, registry setup, dependency checks, or resource validation to make the progress bar move faster.

**Done when:** Fiximizer's startup contribution is measured separately, avoidable work is reduced, and repeated cold/warm-start tests show an improvement without missing registrations or breaking modpack correctness.

#### 1.2 Low-allocation / zero-allocation hot paths

- Audit render, client-tick, server-tick, and event-listener instrumentation.
- Keep recurring measurements in primitive fields and bounded ring buffers where practical.
- Avoid per-frame strings, temporary collections, streams, formatting, and avoidable wrapper objects.
- Move sorting, aggregation, report generation, and disk access to background work.
- Track the allocation cost of Fiximizer itself with JFR or an equivalent repeatable method.

**Done when:** selected steady-state hot paths create no avoidable recurring objects, and allocation measurements confirm the improvement. “Zero allocation” is a target for selected hot paths, not a claim that the entire mod never allocates.

#### 1.3 Adaptive Diagnostics

- Classify diagnostic work by importance: essential stall detection, normal telemetry, and optional deep diagnostics.
- Reduce the frequency of non-essential sampling when frame time, tick time, CPU pressure, or memory pressure is high.
- Preserve bounded incident capture and the user's ability to request a manual snapshot.
- Restore sampling gradually after load returns to normal.
- Show the current monitoring level and explain which optional sampling was reduced.
- Use hysteresis and minimum hold times so the sampling rate does not oscillate.

**Done when:** controlled tests show lower monitoring overhead under load without losing essential stall evidence or making the results misleading.

### Phase 2 — Build the optimization core

**Goal:** turn measurements into safe and useful actions.

- Introduce a workload and bottleneck model: rendering, client tick, server tick, chunk work, entities, block entities, resource reloads, memory/GC, storage, and background tasks.
- Add an optimization policy layer with explicit preconditions, compatibility checks, priorities, and rollback behavior.
- Separate **recommend**, **ask before applying**, and **automatic** actions. Automatic behavior must be opt-in where it can alter gameplay or resource use.
- Give each optimization its own setting and status so users can identify exactly what changed.
- Extend profiles beyond worker count: profile diagnostic intensity and only those optimization policies that have demonstrated safe behavior.
- Add import/export for profiles and a clear “restore defaults” path.
- Make every queue, sample history, cache, and background workload bounded.

**Done when:** the same core can select compatible optimization policies for different modpacks without embedding one pack's mod list or settings in the core.

### Phase 3 — First real Minecraft optimizations

**Goal:** move from observing performance to improving it across common bottleneck classes: launch time, resource reloads, chunks, entities, rendering, memory/GC, storage, and background work.

Prioritize one measured bottleneck at a time. Candidate areas include:

- **Startup and resource reloads:** reuse safe, versioned analysis results; avoid repeated discovery; measure resource reload phases.
- **Chunk loading and generation:** identify expensive stages, reduce duplicate work, and improve scheduling only where Minecraft and mod APIs permit it.
- **Entity and block-entity ticking:** identify expensive types and update patterns; add targeted optimizations only with correctness checks.
- **Rendering:** profile CPU-side preparation, visibility work, entity/block-entity rendering, particles, and avoidable state changes. Distinguish CPU time from GPU time.
- **Memory and garbage collection:** find allocation sources, duplicate caches, unbounded retained data, and avoidable object churn.
- **World saving and storage:** measure serialization and I/O stalls; preserve save ordering and data integrity.
- **Background work:** schedule only independent, explicitly safe work away from critical threads. Never move thread-affine Minecraft work just to increase worker count.

#### 3.1 Memory footprint reduction and safe object deduplication

**Goal:** reduce the amount of live heap retained by a modpack by eliminating proven duplicate data and storing eligible data more compactly. The target is lower real memory retention, not a lower number shown by a single heap-usage sample.

##### Discover what actually occupies memory

- Establish repeatable heap baselines after equivalent world loads and warm-up periods.
- Use JFR allocation data, class histograms, and opt-in heap-dump analysis to distinguish high allocation rate from long-lived retained objects.
- Attribute large object groups to Minecraft, individual mods, or shared libraries where evidence allows; mark ambiguous ownership rather than guessing.
- Track Java heap separately from native/off-heap memory, GPU memory, and total process working set.

##### Deduplicate immutable data

- Prototype content-based canonicalization for high-frequency immutable values such as strings, resource identifiers, and other proven flyweight candidates.
- Investigate NBT data at the tag/value level. Share only values and structures that are demonstrably immutable; do not share mutable compounds, lists, or backing arrays without copy-on-write or another explicit ownership model.
- Investigate duplicate baked model data, vertex arrays, BakedQuad data, and VoxelShape-related structures. Share them only where the relevant API and every participating mod treat the data as immutable.
- Use a hash as a lookup accelerator, then verify full content equality before reusing an object. A hash match alone is never sufficient.
- Compare custom string canonicalization with JVM-provided G1 String Deduplication. Prefer the built-in JVM mechanism when it already addresses the measured case; expose a JVM Advisor recommendation rather than duplicating its work blindly.

##### Store data more compactly

- Replace eligible object-heavy structures owned by Fiximizer or an integration with primitive arrays, packed representations, shared tables, or indexed storage where benchmarks justify the change.
- Reduce duplicate collection nodes, wrappers, and repeated metadata only at boundaries where ownership and mutation semantics are known.
- Keep serialization, world data, networking, and gameplay-visible values byte-for-byte or semantically correct.

##### Bound the deduplication engine and cache lifecycle

- Bound every intern table, index, and cache by entry count and/or memory budget; expose hit, miss, eviction, and estimated-overhead counters.
- Define explicit invalidation and lifecycle rules for resource reloads, world changes, mod reloads, and configuration changes.
- Evaluate strong, weak, and lifecycle-scoped references empirically. Reference wrappers, hash tables, cleanup queues, and hashing work also consume memory and CPU.
- Never perform an unrestricted scan of the Java heap or retain arbitrary objects indefinitely. Do not assume that a lower heap-used reading immediately after a collection proves a lasting reduction.
- Make each strategy independently configurable, with conservative defaults and a clear disable/fallback path.

##### Compatibility and acceptance criteria

- Detect overlapping memory optimizers and avoid applying duplicate or conflicting transformations. Provide per-feature controls when a compatible coexistence strategy cannot be proven.
- Add unit tests for equality, hash collisions, concurrency, bounds, eviction, invalidation, and mutation isolation.
- Benchmark the same world, mod list, Java runtime, heap settings, and warm-up procedure before and after each strategy.
- Report retained heap / post-GC live set where measurable, allocation rate, GC pause time, process memory, CPU overhead, and client/server tick or frame-time percentiles.
- Keep the feature opt-in until repeated tests show a net benefit and no data corruption, visual changes, save issues, or compatibility regressions.

**Done when:** each shipped memory strategy demonstrates a repeatable reduction in retained memory on at least one documented workload, accounts for its own table/cache overhead and CPU cost, passes mutation and compatibility tests, and can be disabled without leaving stale shared state. Do not promise a fixed number of megabytes or gigabytes saved across all modpacks.

The first optimization targets will be selected from reproducible profiles of real modpacks, including the maintainer's own test pack. Results that generalize should become reusable core policies or optional adapters.

**Done when:** each shipped optimization has a documented bottleneck, a reproducible before/after benchmark, compatibility checks, and a safe disable/rollback path.

#### 3.2 Network stack optimization and connection compatibility

**Goal:** reduce the CPU time, allocation rate, and memory overhead caused by Minecraft networking, improve client-side packet processing stability, and make connection failures easier to recover from. Network optimizations must preserve the vanilla wire protocol unless both endpoints explicitly negotiate an extension.

##### Measure before changing the pipeline

- Add opt-in, low-allocation network telemetry for packet/byte rates, packet-size distributions, inbound/outbound processing time, queue pressure, disconnect causes, and available server-reported latency.
- Keep telemetry bounded per connection and use primitive counters/ring buffers; do not allocate strings, collections, or log entries per packet.
- Distinguish server-reported ping/RTT, client-side packet-processing delay, event-loop queue delay, jitter, packet loss where measurable, and server tick delay. Never present one as another.
- Keep addresses, player identifiers, packet payloads, authentication data, and chat content out of telemetry and reports by default.
- Make deep sampling opt-in and automatically reduce its frequency under CPU or memory pressure.

##### Low-overhead packet processing

- Profile the existing Netty pipeline on the exact Minecraft/NeoForge version before introducing handlers or mixins.
- Optimize VarInt/VarLong, string, and packet-buffer serialization only when byte-for-byte protocol equivalence and representative benchmarks are demonstrated.
- Reduce avoidable temporary objects and copies; reuse buffers only with explicit ownership, reference-counting, lifetime, and per-connection memory bounds.
- Evaluate compression encoder/decoder improvements with the negotiated server threshold unchanged. Preserve all vanilla framing, validation, and error semantics.
- Evaluate flush consolidation only where ordering and latency semantics are preserved. Do not delay latency-sensitive packets or change gameplay-visible timing to improve a benchmark.
- Keep packet processing on the expected Netty event loop unless a task is proven independent and safe to move. Never reorder packets or run Minecraft packet handlers concurrently without an explicit protocol guarantee.
- Treat TCP socket options as measured, platform-specific policies, not universal tweaks. Do not replace Minecraft TCP with UDP for ordinary server connections.

##### Adaptive network profiles

- Provide conservative, low-latency, balanced, and memory-saver policies for Fiximizer-owned telemetry and optional pipeline features.
- Adapt diagnostic sampling and bounded telemetry capacity to CPU/RAM pressure and connection activity.
- Expose each active policy, its preconditions, measured overhead, and fallback state in the dashboard.
- Keep the default mode passive until an optimization has passed compatibility and workload testing.

##### Java/Rust backend selection and TCP socket policy

- Keep Java as the always-available fallback and offer an optional Rust native backend for **batch processing of Fiximizer telemetry statistics only**. Do not cross JNI/JNA for every game packet.
- In Auto mode, verify identical output first, warm up both implementations, then compare repeated runs on the same bounded input. Select Rust only when it is at least 10% faster in the local batch benchmark; otherwise keep Java.
- If the native library is absent, unsupported on the current OS/architecture, fails ABI/correctness validation, or errors at runtime, fall back to Java without affecting the game connection.
- Expose Auto / Java / Rust selection and a separate Rust availability toggle in the dashboard. Build and test native binaries in CI for each advertised platform; unsupported platforms continue with Java.
- Keep TCP_NODELAY a separate opt-in client socket policy, off by default. Apply it only when the channel supports the option and no known external Netty stack optimizer is present; restore the previous value on disable/disconnect. Benchmark end-to-end latency and CPU before claiming a benefit.

##### Connection recovery and protocol compatibility

- Diagnose DNS resolution, SRV records, IPv4/IPv6 address selection, socket connection, encryption/compression negotiation, login/configuration phases, and server disconnect reasons as separate stages.
- When a hostname resolves to multiple valid endpoints, evaluate a carefully bounded retry of another resolved endpoint after a transport-level failure, while preserving the original hostname for TLS/proxy/SRV semantics where applicable.
- Never silently change a server's game protocol, authentication state, account identity, or mod handshake. A retry must be bounded, visible, and must not replay gameplay packets or duplicate login side effects.
- Detect common protocol/version mismatch signals and recommend a compatible client/server version or an explicitly installed protocol translation layer.
- Treat ViaForge/ViaVersion-style translation as an optional, separately versioned integration. Do not implement a partial protocol translator inside the core.
- UDP/QUIC or another transport may be explored only as an optional extension when both client and server/proxy support the same negotiated protocol. Vanilla TCP connections must remain fully supported.
- A client-only mod cannot lower the physical route latency or remote server CPU time. Report those limits clearly and focus client-side optimization on local CPU, memory, and processing delay.

##### Compatibility-first integration system

- Detect networking-related mods and their exact versions at startup, then check actual capabilities rather than relying only on mod IDs.
- Initial compatibility targets to investigate include KryptonFoxified / Krypton Reno, Connectivity, Packet Fixer, ViaForge/ViaVersion integrations, and network libraries such as Modern Networking.
- Maintain a compatibility matrix with tested Minecraft/NeoForge versions, client/server presence, overlapping transformations, known limitations, and regression results.
- Detect duplicate Netty handlers, competing compression/decoder replacements, overlapping Mixins, protocol translators, and packet-limit modifications before activating a Fiximizer patch.
- Use isolated optional adapters and feature-level conflict policies. If a safe coexistence mode cannot be proven, disable only the conflicting Fiximizer feature and explain why; never block unrelated integrations.
- Test with each target mod alone, in relevant pairs, and in representative large modpacks. Missing, disabled, or incompatible optional mods must not affect startup or ordinary vanilla networking.

##### Acceptance criteria

- Verify exact packet bytes, VarInt/VarLong boundaries, compression framing, ordering, encryption, login/configuration phases, and disconnect behavior.
- Test single-player integrated server, LAN, remote dedicated servers, proxies, IPv4/IPv6, SRV, high-latency/loss/jitter scenarios, and large modded payloads.
- Compare CPU time, allocations per packet, retained memory, bytes copied, packet-processing p50/p95/p99, event-loop backlog, and connection success rate before/after.
- Demonstrate repeatable net benefit on both a low-load and a network-heavy workload; account for the optimizer's own overhead.
- Provide per-feature switches, safe fallback to the unmodified vanilla pipeline, and tests proving that disabling the feature restores ordinary behavior.

**Done when:** at least one network optimization has a repeatable end-to-end benefit, exact protocol correctness tests, documented mod compatibility, and a reliable vanilla fallback. Do not claim lower internet ping unless measured RTT to the same endpoint improves under controlled conditions.

### Phase 4 — Modpack compatibility modules

**Goal:** make Fiximizer useful across packs without turning the core into a collection of fragile special cases.

- Evolve the existing integration gates into isolated, optional adapters.
- Add capability and version checks before registering hooks or loading integration-specific classes.
- Detect overlaps with existing performance mods and avoid applying conflicting or duplicate optimizations.
- Start with integrations that have clear APIs, measurable costs, and reproducible user reports.
- Continue the existing Create and Sable profiling work, then implement deeper optimizations only when their real hot paths are understood.
- Evaluate other commonly used performance and content mods based on actual demand and maintained APIs, rather than promising support for every mod.
- Keep missing, disabled, or incompatible integrations from affecting core startup.

**Done when:** a pack can contain none, some, or many supported mods; unsupported combinations fail closed and the rest of Fiximizer continues to work.

### Phase 5 — Caching and compute acceleration

**Goal:** use caches, OpenCL, or native code only when a real workload benefits.

#### Safe caching

- Cache only data that can be recreated or validated.
- Key persistent entries by relevant Minecraft, mod, configuration, and data-format versions or fingerprints.
- Invalidate stale entries safely and bound disk/RAM/VRAM usage.
- Measure whether cache lookup and validation are cheaper than recomputing the data.
- Connect the existing VRAM cache API only to an integration that can restore or rebuild evicted data.

#### OpenCL and native compute

- Identify a sufficiently large, independent workload before writing a GPU kernel.
- Include CPU preparation, memory transfer, synchronization, and result validation in benchmarks.
- Keep a CPU implementation and automatic fallback.
- Use OpenCL only when the complete workload is faster and correct on supported devices.
- Evaluate Rust/JNI only for a measured CPU bottleneck where native interop produces a meaningful end-to-end gain.

**Done when:** an accelerated path is measurably faster for a real Fiximizer-owned or adapter-owned workload, has correctness tests, and degrades safely when the device or native runtime is unavailable.

### Phase 6 — Broader platform and version support

- Keep Minecraft 1.21.1 / NeoForge as the first compatibility target until its optimization features are validated.
- Add newer Minecraft and NeoForge lines through explicit build and runtime test matrices.
- Consider Fabric support after the core engine is sufficiently separated from loader APIs.
- Validate Windows and Linux behavior, startup, profiling, file handling, and optional native components.
- Publish clear compatibility tables for each release instead of assuming that a broad version range guarantees support.

**Done when:** each advertised game version and loader has a reproducible build plus runtime compatibility testing for the features enabled in that release.

### Phase 7 — Stable, useful releases

- Maintain a repeatable benchmark pack and a small set of representative test worlds.
- Track startup time, frame-time percentiles, MSPT/TPS, allocation rate, GC pauses, memory high-water mark, and background CPU cost.
- Publish before/after methodology and disclose when a result applies only to one hardware or mod configuration.
- Add regression tests for data safety, configuration migration, disabled integrations, and failure recovery.
- Improve onboarding, per-feature explanations, exportable diagnostics, and user-controlled privacy.
- Use real-world reports to prioritize fixes and compatibility work.

## 4. Benchmark and acceptance rules

A feature is not called an optimization merely because it adds a setting or moves work to another thread.

Before describing a performance improvement as successful:

1. Record a baseline and the exact Minecraft, loader, Java, mod, configuration, world, and hardware setup.
2. Compare equivalent runs, separating cold-start effects from warm-up and caching.
3. Repeat measurements and report medians and tail behavior (especially p95/p99), not just a single best result.
4. Measure the cost of Fiximizer's instrumentation as well as the workload being optimized.
5. Check correctness: world saves, simulation results, networking, rendering, and compatibility must remain valid.
6. Test disabled-feature behavior and verify that failure of an optional integration does not prevent the game from starting.
7. Keep results reproducible on more than one machine or configuration before making broad claims.

Useful metrics include startup duration, frame-time percentiles, client/server tick duration, MSPT/TPS, chunk and resource-reload time, bytes allocated per second, GC pause time, heap and native-memory usage, and Fiximizer's own CPU/disk overhead.

There is no universal FPS target. The relevant success metric depends on the bottleneck: a server-heavy pack needs different evidence from a GPU-bound shader pack.

## 5. Language and runtime strategy

Fiximizer is a Minecraft Java mod, so the main integration layer must remain compatible with NeoForge and the JVM. Additional languages are tools, not performance goals.

| Language | Intended role | Adoption rule |
|---|---|---|
| Java | Core mod, loader integration, lifecycle hooks, UI, scheduler, and most runtime hot paths | Default choice. It has direct access to Minecraft/NeoForge APIs and avoids an extra language runtime. |
| Kotlin | Optional isolated modules where null-safety, concise domain modelling, or multiplatform sharing materially improves maintainability | Do not add Kotlin solely to make the mod faster. Kotlin/JVM produces JVM-compatible bytecode; measure any standard-library, class-loading, and startup cost before adopting it. Keep hot paths allocation-aware regardless of language. |
| Rust | Optional native compute or parsing modules for a large, independent CPU-bound workload | Adopt only after end-to-end benchmarks show a meaningful gain after JNI/FFI, copying, native loading, and packaging costs. Keep a Java fallback and ship native binaries only for tested OS/CPU targets. |

No blanket rewrite is planned. Java remains the baseline; Kotlin and Rust are introduced only where a measured technical advantage outweighs startup time, memory, binary size, compatibility, and maintenance costs.

## 6. Engineering principles

- **Measure first.** No speculative “performance” patches.
- **Correctness before speed.** Never trade world or player-data integrity for a benchmark result.
- **Small, reversible changes.** Every optimization has its own toggle, preconditions, and fallback.
- **Low overhead by design.** Bounded data structures, lazy work, sampling budgets, and no unnecessary work on hot threads.
- **Memory optimizations must respect mutability and ownership.** Share only data proven immutable; bound every deduplication table and account for its own memory and CPU cost.
- **Respect ownership and thread affinity.** Fiximizer may schedule its own independent work; it must not take over arbitrary third-party or Minecraft tasks.
- **Compatibility is explicit.** Optional modules are isolated and version-aware.
- **Be honest about results.** Publish the test conditions and distinguish diagnostics, recommendations, and active optimizations.
- **Privacy by default.** Local reports should avoid unnecessary personal paths, identifiers, and network uploads.

## 7. What comes next

The immediate engineering sequence is:

1. Finish validating and prepare the separate Startup Optimizer implementation for integration; preserve the distinction between total JVM-to-title time and Fiximizer-owned phase durations.
2. Complete the low-allocation audit of telemetry and other recurring hot paths.
3. Implement Adaptive Diagnostics with a visible monitoring level and measured overhead.
4. Add a local, privacy-reviewed Diagnostic Bundle that can be attached to bug reports or inspected by an AI assistant.
5. Build a repeatable Benchmark Engine with versioned baselines, comparable scenarios, medians and tail percentiles.
6. Add a Runtime Health Check that reports the state and capabilities of each Fiximizer subsystem.
7. Reconcile the independent Startup, Memory and Network branches against the current main branch before integrating their code; preserve their work as separate changes until each has passed the relevant tests.
8. Select the first additional in-game optimization from a reproducible real-modpack profile, then measure its end-to-end benefit.

Kotlin modules, Rust native code, broad loader support, and automatic GPU acceleration are introduced only when they prove useful—not as rewrites for their own sake. Startup acceleration remains a first-class goal.

## 8. Follow-up implementation specifications

The audit-derived implementation proposals are maintained separately from this roadmap so they can be reviewed, changed and implemented independently:

- [Improvement proposals and implementation plan](docs/IMPROVEMENT_PROPOSALS.md) — Diagnostic Bundle, Benchmark Engine, Runtime Health Check, Memory/Network follow-ups, compatibility and integration gates.
- [Russian version](docs/IMPROVEMENT_PROPOSALS.ru.md).

These documents describe planned work. They do not imply that the proposed features are already implemented or that any independent feature branch has been merged.

---

*Roadmap status reflects the repository's current development state. Items without an implemented feature and tests are plans, not shipped capabilities.*

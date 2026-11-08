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

#### 1.1 Startup Profiler

- Measure Fiximizer's own initialization phases, from mod construction and registration through client setup and deferred service startup.
- Separate Fiximizer initialization time from total Minecraft startup time.
- Record per-subsystem timings and identify expensive class loading, registration, discovery, and setup.
- Measure cold and warm starts separately where that distinction is meaningful.
- Export a compact report that can be compared between builds.
- Keep measurement bounded and avoid synchronous disk I/O on Minecraft's critical startup thread.

**Done when:** reports consistently identify Fiximizer's own startup cost, distinguish it from other mods and Minecraft, and have measured low overhead.

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

**Goal:** move from observing performance to improving it.

Prioritize one measured bottleneck at a time. Candidate areas include:

- **Startup and resource reloads:** reuse safe, versioned analysis results; avoid repeated discovery; measure resource reload phases.
- **Chunk loading and generation:** identify expensive stages, reduce duplicate work, and improve scheduling only where Minecraft and mod APIs permit it.
- **Entity and block-entity ticking:** identify expensive types and update patterns; add targeted optimizations only with correctness checks.
- **Rendering:** profile CPU-side preparation, visibility work, entity/block-entity rendering, particles, and avoidable state changes. Distinguish CPU time from GPU time.
- **Memory and garbage collection:** find allocation sources, duplicate caches, unbounded retained data, and avoidable object churn.
- **World saving and storage:** measure serialization and I/O stalls; preserve save ordering and data integrity.
- **Background work:** schedule only independent, explicitly safe work away from critical threads. Never move thread-affine Minecraft work just to increase worker count.

The first optimization targets will be selected from reproducible profiles of real modpacks, including the maintainer's own test pack. Results that generalize should become reusable core policies or optional adapters.

**Done when:** each shipped optimization has a documented bottleneck, a reproducible before/after benchmark, compatibility checks, and a safe disable/rollback path.

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

## 5. Engineering principles

- **Measure first.** No speculative “performance” patches.
- **Correctness before speed.** Never trade world or player-data integrity for a benchmark result.
- **Small, reversible changes.** Every optimization has its own toggle, preconditions, and fallback.
- **Low overhead by design.** Bounded data structures, lazy work, sampling budgets, and no unnecessary work on hot threads.
- **Respect ownership and thread affinity.** Fiximizer may schedule its own independent work; it must not take over arbitrary third-party or Minecraft tasks.
- **Compatibility is explicit.** Optional modules are isolated and version-aware.
- **Be honest about results.** Publish the test conditions and distinguish diagnostics, recommendations, and active optimizations.
- **Privacy by default.** Local reports should avoid unnecessary personal paths, identifiers, and network uploads.

## 6. What comes next

The immediate engineering sequence is:

1. Startup Profiler.
2. Low-allocation telemetry audit and hot-path cleanup.
3. Adaptive Diagnostics with a visible overhead level.
4. Repeatable benchmark/baseline export.
5. The first evidence-backed optimization selected from a real modpack profile.
6. A reusable optimization-policy and adapter architecture around that work.

Rust, broad loader support, and automatic GPU acceleration are later possibilities—not prerequisites for making Fiximizer a useful optimizer.

---

*Roadmap status reflects the repository's current development state. Items without an implemented feature and tests are plans, not shipped capabilities.*

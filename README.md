<div align="center">

  <img src="assets/fiximizer-banner.svg" alt="Fiximizer — diagnostics, profiling and adaptive workload control" width="100%">

  <p><strong>Measure the bottleneck. Understand the cause. Tune what Fiximizer actually controls.</strong></p>

  <p>
    <a href="README.ru.md">Русская версия</a> ·
    <a href="https://github.com/MAKAKAIglitch/Fiximizer/actions/workflows/build.yml">CI builds</a> ·
    <a href="https://github.com/MAKAKAIglitch/Fiximizer/issues">Issues</a> ·
    <a href="BUILDING.md">Build guide</a> ·
    <a href="ROADMAP.md">Roadmap</a>
  </p>

  <p>
    <a href="https://github.com/MAKAKAIglitch/Fiximizer/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/MAKAKAIglitch/Fiximizer/build.yml?branch=main&label=build" alt="Build status"></a>
    <img src="https://img.shields.io/badge/version-0.14.0--alpha-orange" alt="Version 0.14.0-alpha">
    <img src="https://img.shields.io/badge/Minecraft-1.21.1-62A75B?logo=minecraft&logoColor=white" alt="Minecraft 1.21.1">
    <img src="https://img.shields.io/badge/NeoForge-21.1.256%2B-4B6FAD" alt="NeoForge 21.1.256 or newer in the 21.1 line">
    <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java 21">
    <a href="https://github.com/MAKAKAIglitch/Fiximizer/blob/main/LICENSE"><img src="https://img.shields.io/github/license/MAKAKAIglitch/Fiximizer" alt="MIT License"></a>
    <a href="https://github.com/MAKAKAIglitch/Fiximizer/stargazers"><img src="https://img.shields.io/github/stars/MAKAKAIglitch/Fiximizer?style=social" alt="GitHub stars"></a>
    <a href="https://github.com/MAKAKAIglitch/Fiximizer/network/members"><img src="https://img.shields.io/github/forks/MAKAKAIglitch/Fiximizer?style=social" alt="GitHub forks"></a>
  </p>

</div>

> [!IMPORTANT]
> **Fiximizer is evolving into a modular performance optimizer for Minecraft modpacks.** The current alpha provides profiling, stall diagnostics, an adaptive scheduler for Fiximizer-owned work, and optional mod telemetry. The first target is Minecraft 1.21.1 on NeoForge; deeper optimizations are added after real-pack benchmarks and compatibility checks. See the [roadmap](ROADMAP.md) for the full direction.

## Contents

- [Overview](#overview)
- [Features](#features)
- [In-game dashboard](#in-game-dashboard)
- [Profiles and adaptive scheduler](#profiles-and-adaptive-scheduler)
- [Memory optimizer](#memory-optimizer)
- [Diagnostics and reports](#diagnostics-and-reports)
- [Optional mod integrations](#optional-mod-integrations)
- [GPU, OpenCL and VRAM](#gpu-opencl-and-vram)
- [Compatibility](#compatibility)
- [Installation](#installation)
- [Building from source](#building-from-source)
- [Roadmap](#roadmap)
- [Bug reports and contributions](#bug-reports-and-contributions)
- [License](#license)

## Overview

**Fiximizer** is an open-source diagnostics and optimization project for modded **Minecraft Java Edition 1.21.1** on **NeoForge**, with the long-term goal of becoming a modular, general-purpose modpack optimizer.

It helps answer practical questions:

- Is the client render call, client tick or integrated-server tick taking too long?
- What was the JVM doing when the game stopped responding?
- Which thread, method or mod package is worth investigating?
- Is Fiximizer's own background queue falling behind?
- Is the system under memory pressure?
- Does the installed GPU's OpenCL path work correctly, and is it faster for a measured test?

Fiximizer combines bounded runtime measurements, Java Flight Recorder (JFR), incident reports, optional mod-aware telemetry and an adaptive scheduler for work owned by Fiximizer itself.

## Features

| Area | What is implemented |
|---|---|
| Runtime dashboard | Nine sections, including a dedicated Memory optimizer view |
| Client measurements | `GameRenderer.render()` call duration and client tick duration, with rolling percentiles |
| Integrated-server measurements | Full local single-player server tick duration and slow-tick counters |
| Stall watchdog | Detects long-running client, render and integrated-server event spans; captures JVM thread snapshots |
| JFR profiling | Bounded rolling recording, on-demand exports, CPU/allocation analysis and incident correlation |
| Incident reports | Related snapshots, incident timeline, ranked investigative candidates and a self-contained local HTML overview |
| Memory guard | Temporarily switches Fiximizer to its Memory Saver profile when configured memory-pressure conditions persist |
| Memory optimizer | Opt-in bounded weak String canonicalization for selected integration metadata, live reuse counters and optional G1 JVM advice |
| Adaptive scheduler | Queue-aware worker control, p95 timings and task counters for Fiximizer-owned work |
| JVM advisor | Recommends `-Xms` / `-Xmx` values based on detected RAM and loaded mod count |
| Optional integrations | Mod detection and independent gates for Create, Create Aeronautics, Sable, Distant Horizons and Voxy |
| GPU diagnostics | GPU/OpenCL discovery and a manual, result-validated CPU-versus-GPU vector-add test |
| VRAM cache API | Bounded cache for disposable data explicitly provided by a Fiximizer integration |

### How the pieces fit together

~~~mermaid
flowchart TD
    A["Minecraft + NeoForge events"] --> B["Bounded runtime measurements"]
    B --> C["In-game dashboard"]
    B --> D["Stall watchdog"]
    D --> E["JVM thread snapshots + rolling JFR"]
    E --> F["Analysis and incident correlation"]
    F --> G["Local text and HTML reports"]
    H["Fiximizer-owned task queue"] --> I["Adaptive worker scheduler"]
    J["Optional Create / Sable events"] --> B
    K["OpenCL discovery and manual test"] --> L["Validated benchmark report"]
~~~

## In-game dashboard

Open the same dashboard using any of these entry points:

| Entry point | Action |
|---|---|
| Keybind | Press **F8** |
| NeoForge mod list | Select Fiximizer and press **Config** |
| Client command | `/fiximizer` |
| Client command | `/fiximizer open` |
| Client command | `/fiximizer menu` |

The dashboard is available in English and Russian.

Its eight sections are:

1. **Overview** — runtime and memory status, active profile, diagnostic snapshot controls.
2. **Optimization** — selected profile, effective profile and memory guard.
3. **Integrations** — detected optional mods, versions and per-mod toggles.
4. **Physics** — Create contraption and Sable physics telemetry.
5. **Java / JVM** — runtime information, JFR controls and JVM argument recommendations.
6. **GPU / VRAM** — GPU and OpenCL status, self-test and explicit VRAM cache controls.
7. **Frames** — client render-call and client tick measurements.
8. **Server** — integrated single-player server tick measurements.
9. **Memory** — bounded string-sharing counters and optional G1 String Deduplication advice.

Optional hardware probes and stall-watchdog startup are deferred until the title screen or a loaded world has been ticking for **40 stable client ticks** (about two seconds). This keeps optional diagnostics work out of the critical initial loading path.

## Profiles and adaptive scheduler

Fiximizer provides five selectable strategies. They control only Fiximizer's own background work; they do not change Minecraft's render-thread priority, reorder Minecraft ticks or take control of other mods' worker pools.

| Profile | Worker limit | Queue capacity | OpenCL policy |
|---|---:|---:|---|
| Game smoothness | 2 | 48 | Conservative |
| Low latency | 2 | 32 | Very conservative |
| Balanced | 3 | 128 | Standard adaptive |
| Maximum performance | 4 | 256 | More permissive |
| Memory saver | 1 | 16 | Disabled |

### Queue-aware worker governor

The scheduler tracks the most recent **128 tasks**, including queue wait and execution time. Its dashboard exposes p95 timings and completed, failed, cancelled and rejected task counters.

- It starts conservatively and can add at most one worker per diagnostics sample when queue pressure is persistent, all workers are busy and CPU headroom is available.
- It reduces concurrency as system CPU load rises. At the selected profile's critical threshold, it limits the pool to one worker.
- It gradually releases unused capacity when the queue is idle.
- If CPU telemetry is unavailable, it holds the current limit instead of assuming that the system has spare capacity.
- The global worker limit is four. Lowering the limit does not interrupt tasks that are already running.

This is a scheduler for independent Fiximizer tasks, not a general-purpose Minecraft thread optimizer. Queue measurements do not guarantee higher FPS.

### Automatic memory guard

The memory guard samples Java heap usage and available physical RAM. When configured pressure conditions persist, it temporarily switches Fiximizer to **Memory Saver**. Separate recovery thresholds and repeated samples help avoid rapid profile switching.

When memory conditions recover, the previously selected profile is restored. The guard does not call `System.gc()`, unload objects owned by other mods or change the running JVM's heap limits.

## Memory optimizer

The Memory tab selects JVM-specific memory mechanisms from the **actually running runtime**, rather than assuming every Java vendor exposes the same features.

- **JDK 25+ Compact Object Headers:** when the active VM exposes HotSpot's `UseCompactObjectHeaders` option, the class-count safety limit is not reached, and the feature is not already enabled, the JVM Advisor automatically includes `-XX:+UseCompactObjectHeaders` in the recommended arguments. This reduces object headers from 96/128 bits to 64 bits on supported 64-bit HotSpot builds. The option is applied only after restarting the game.
- **Java 21 compatible fallback:** Java 21 does not expose the JDK 25 compact-header option. Fiximizer keeps Java 21 bytecode and uses its bounded explicit String canonicalization API; no Java 25-only classes or APIs are loaded.
- **Optional G1 String Deduplication:** the JVM Advisor can add `-XX:+UseStringDeduplication` only when G1 is detected and the user enables the setting. It may add garbage-collection CPU work, so it is off by default.

The explicit String table uses weak references, 16 buckets with four ways each (64 slots total), full string equality after hash matches, skips strings shorter than eight characters, and stops after three failed compare-and-set attempts. Under contention it bypasses the cache instead of spinning or blocking. It is created lazily and released when disabled; it never scans the heap, rewrites arbitrary Minecraft or third-party object graphs, or runs a recurring task.

**Current scope is intentionally small:** the first adapter covers selected optional-mod metadata (IDs and version strings). It is an integration foundation, not a claim that the entire modpack heap is compressed. The dashboard counts only reuse of a different, equal String instance as a deduplication hit; seeing the same object again is not counted as saved memory. A zero-hit result means no saving has been observed. See [the Memory Optimizer implementation notes](docs/MEMORY_OPTIMIZER.md).

## Diagnostics and reports

### Client, render and integrated-server timing

| Measurement | Window | Reported data |
|---|---:|---|
| `GameRenderer.render()` call | 600 samples | Average, p50, p95, p99, peak and slow-sample counts |
| Client tick | 600 samples | Average, p50, p95, p99, peak and slow-sample counts |
| Integrated-server tick | 600 ticks | Average, p50, p95, p99, peak and counts above 50 ms / 100 ms |
| Create contraption entity ticks | 100 server ticks | Entity tick count, average charged time per server tick, average/peak entity tick time and the most expensive entity type |
| Sable physics substeps | 1,000 substeps | Average, peak, total measured time and the dimension with the largest accumulated cost |

**Measurement boundaries matter:**

- `GameRenderer.render()` duration is not the time between displayed frames. It excludes presentation cadence and VSync waiting outside that call, and it is not GPU execution time.
- Integrated-server tick time includes Create, Sable and other server-side mod work. It overlaps with the Create and Sable measurements; do not add the values together.
- The Create profiler measures entities derived from `AbstractContraptionEntity`, not every Create block entity or method.
- Sable timing covers the work between its public pre/post physics events. It includes Java preparation, the physics-pipeline call (including native work) and pose updates; it is not a pure Rapier benchmark.
- Create and Sable telemetry described here is for the **local integrated single-player server**. The client dashboard does not measure CPU time on a remote dedicated server.

### Stall watchdog and Java Flight Recorder

The watchdog observes long-running event spans for the integrated server tick, client tick and render call. A separate daemon thread checks active spans every **200 ms**; the default stall threshold is **2 seconds**.

When a stall is detected, Fiximizer can capture:

- JVM thread stacks and runtime counters at detection time;
- bounded repeated snapshots associated with one incident ID;
- a rolling JFR recording, exported as a copy while the live recording continues;
- CPU execution samples, thread load, garbage-collection events and sampled allocations;
- automatic JFR hotspot analysis and stack/JFR/mod correlation;
- a self-contained HTML incident overview with a duration graph and links to related local files.

The default JFR ring is bounded by **60 seconds** and **64 MiB**. Older data is discarded when either limit is reached. JFR exports are rate-limited and report retention is bounded by configuration.

Mod attribution and candidate ranking are **heuristics for investigation**, not proof that a mod caused a stall. Multi-mod JARs, shared packages, transformed classes and native code can remain unattributed. Continuous JFR recording can add CPU and disk overhead; it can be disabled independently of basic watchdog snapshots.

### Where reports are stored

| Report | Location |
|---|---|
| Stall snapshots, JFR exports, analysis and incident HTML | `logs/fiximizer/stalls/` |
| OpenCL benchmark | `fiximizer-gpu-benchmark.txt` in the game directory |
| Suggested JVM arguments | `config/fiximizer-jvm-arguments.txt` |

The incident HTML report is self-contained and uses local files only; it does not load remote scripts, fonts or services.

## Optional mod integrations

Fiximizer detects the installed mod ID and version and provides an independent enable/disable gate for each target.

| Mod | Current integration |
|---|---|
| Create | Passive server-side tick telemetry for contraption entities |
| Create Aeronautics | Detection, version reporting and integration gate |
| Sable | Passive physics-substep telemetry through public NeoForge events |
| Distant Horizons | Detection, version reporting and integration gate |
| Voxy | Detection, version reporting and integration gate |

Create and Sable integrations are optional: Fiximizer does not link their classes as mandatory dependencies. If a target mod is absent or its toggle is disabled, its integration callback is not activated.

**Detection is not the same as optimization.** Intrusive Create, Aeronautics and Sable optimization patches are not shipped in this version. The current Create and Sable features are passive profilers; other listed mods currently have detection and guarded extension points.

## GPU, OpenCL and VRAM

### Manual OpenCL correctness benchmark

The GPU tab includes a manual OpenCL vector-add test with **1,048,576 elements**. It creates the OpenCL context, queue, program, kernel and buffers, transfers data to the GPU, runs the kernel, reads the result back and validates every output against the CPU result.

The report separates upload, kernel and download time and compares the full GPU path with a CPU baseline. The test uses three 4 MiB GPU buffers and runs on a daemon worker rather than the render thread.

A successful test confirms that this OpenCL workload ran correctly. It does **not** mean Minecraft, Create or Sable calculations are automatically accelerated by the GPU. Transfer cost is included, and the GPU path can be slower than the CPU baseline.

The OpenCL runtime is supplied by the installed graphics driver. Fiximizer can discover an available runtime; it does not install or replace graphics drivers.

### VRAM cache

The optional VRAM store is a bounded LRU cache for temporary blocks explicitly handed to Fiximizer by an integration.

- Default configured cap: **256 MiB**, adjustable up to **512 MiB**.
- It requires a successful OpenCL correctness test, memory pressure and a live VRAM-usage sensor to calculate a safe budget.
- It keeps a graphics reserve of at least **1.5 GiB or 20% of reported VRAM**, whichever is larger.
- Integrations must provide disposable data and know how to restore or rebuild it after eviction.

This is **not** extra Java heap, system RAM or a page file. Fiximizer cannot move arbitrary Minecraft or third-party mod objects into VRAM. Stock Minecraft 1.21.1's bundled OSHI version does not expose the used-VRAM metric required by the spill budget, so VRAM spilling may remain unavailable unless a compatible sensor is present.

### GPU telemetry availability

GPU identification and total VRAM can be read through the game-provided API. Live utilization, used VRAM, temperature and power depend on optional runtime OSHI GPU-stat methods; those values are not available through the stock Minecraft 1.21.1 OSHI setup. OpenCL device discovery is independent of those OSHI readings.

## Compatibility

| Component | Requirement |
|---|---|
| Minecraft | Java Edition **1.21.1** |
| Mod loader | **NeoForge 21.1.256 or newer compatible 21.1.x releases** |
| Java runtime | **Java 21** |
| Build / Gradle runtime | **JDK 21 or 25** (Java 21 compiler toolchain) |
| Optional integrations | Create, Create Aeronautics, Sable, Distant Horizons and Voxy |
| OpenCL | An OpenCL-capable device and runtime supplied by the graphics driver; required only for GPU features |

Fiximizer is built for NeoForge. Fabric and Forge builds are not provided by this repository.

## Installation

> [!NOTE]
> **There is currently no published GitHub Release.** Use a successful GitHub Actions build artifact for testing. Artifacts are retained for **14 days**, so they are not permanent release downloads.

1. Open the [Build workflow](https://github.com/MAKAKAIglitch/Fiximizer/actions/workflows/build.yml).
2. Select the latest successful run on the `main` branch.
3. Open its **Artifacts** section and download the package for the build you want:
   - `Fiximizer-0.14.0-alpha-linux`
   - `Fiximizer-0.14.0-alpha-windows`
4. Extract the archive and place the Fiximizer JAR in your Minecraft instance's `mods` folder.
5. Launch Minecraft **1.21.1** with a compatible NeoForge installation and Java 21.

The Linux and Windows packages are CI outputs. The mod itself is a Java mod; choose the artifact that is available from a successful run.

## Building from source

### Windows — automatic builder

Run this from the repository root:

~~~bat
build.bat
~~~

The builder accepts a full JDK 21 or 25 to run Gradle. If neither is available, it downloads Eclipse Temurin 21 from the official Adoptium API, verifies the SHA-256 checksum and stores the isolated fallback JDK in `.tools/jdk-21-temurin`. Gradle still compiles the mod with the Java 21 toolchain; system Java settings are not changed.

Other examples:

~~~bat
build.bat clean build
build.bat runClient
~~~

### Linux — automatic builder

Run from the repository root:

~~~sh
./build.sh
~~~

Examples:

~~~sh
./build.sh clean build
./build.sh runClient
~~~

The Linux builder accepts a complete JDK 21 or 25 for Gradle, including common Prism Launcher locations. If neither is available, it downloads Eclipse Temurin 21 for the detected CPU architecture, verifies the official SHA-256 checksum and stores it as an isolated fallback in `.tools/jdk-21-temurin`. Compilation remains Java 21. See [BUILDING.md](BUILDING.md) for details.

### Any platform — Gradle Wrapper

With JDK 21 or 25 installed as the Gradle runtime:

~~~bash
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
~~~

The JAR is written to `build/libs/`. See [BUILDING.md](BUILDING.md) for the detailed build workflow.

### Continuous integration

The [Build workflow](.github/workflows/build.yml) runs on pushes and pull requests. It:

- builds and tests against NeoForge 21.1.256 and 21.1.265 in its compatibility matrix;
- builds the mod on Ubuntu and Windows with Java 21;
- runs an additional Windows/Linux build-and-test matrix with Gradle on JDK 25 while preserving Java 21 bytecode;
- validates the POSIX shell builder and runs the automatic Linux builder;
- validates the PowerShell builder syntax and runs the automatic builder on Windows;
- uploads the resulting JARs as workflow artifacts;
- keeps those artifacts for 14 days.

## Roadmap

The alpha release is the measurement and diagnostics foundation for a broader modpack optimizer. The full plan covers launch-time acceleration, startup profiling, low-allocation telemetry, adaptive diagnostics, a safe optimization-policy engine, real Minecraft workload optimizations, per-mod adapters, caching, optional compute acceleration, and broader compatibility.

- [Full roadmap (English)](ROADMAP.md)
- [Дорожная карта (русский)](ROADMAP.ru.md)

The roadmap distinguishes features already implemented from planned work and defines how performance improvements will be benchmarked.

## Bug reports and contributions

Found a crash, incorrect reading or compatibility problem? Please [open an issue](https://github.com/MAKAKAIglitch/Fiximizer/issues) with:

- Minecraft, NeoForge, Java and Fiximizer versions;
- the relevant mod list and steps to reproduce;
- the exact symptom and what you expected to happen;
- the relevant log or Fiximizer report, after removing personal information and unrelated private paths.

Pull requests are welcome. For larger changes, open an issue first so the scope and compatibility impact can be discussed.

## License

Fiximizer is distributed under the **MIT License**. See [LICENSE](LICENSE).

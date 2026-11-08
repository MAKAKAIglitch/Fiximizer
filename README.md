# Fiximizer

**Adaptive diagnostics, targeted mod integration gates, memory pressure control and experimental GPU compute for Minecraft 1.21.1 / NeoForge.**

## Current status

**0.8.0-alpha — client render/tick workload telemetry alongside Create and Sable physics profiling**

### In-game menu

- Open the dashboard with F8.
- Open the same Fiximizer control center from the Config button in NeoForge's mod list.
- Seven sections: Overview, Optimization, Integrations, Physics, Java / JVM, GPU / VRAM, and Frames.
- Russian and English translations.

### CPU / GPU self-test

The GPU tab offers a manual OpenCL self-test using a 1,048,576-element vector addition.

It performs and validates all of these stages:

1. Create OpenCL context, queue, program, kernel and device buffers.
2. Upload two input arrays from system memory to GPU memory.
3. Execute the OpenCL kernel on the selected GPU.
4. Read the output back to system memory.
5. Compare every output value with the CPU result.
6. Report upload time, average kernel time, download time, total GPU-path time and CPU baseline.

The benchmark uses a small fixed working set (three 4 MiB GPU buffers) and runs on a daemon worker, not the render thread. CPU and GPU loops are warmed up before the timed measurements. The complete report is written to fiximizer-gpu-benchmark.txt in the game directory.

A successful result verifies that the GPU can compute this test correctly. It does **not** prove that every Minecraft workload will be faster on the GPU. Transfer time is included in the end-to-end comparison; a slow GPU path must not be described as a speedup.

### Bounded VRAM cache for explicit integration data

Fiximizer includes an opt-in VRAM store for temporary cache blocks that a Fiximizer integration explicitly hands to it.

- Enabled only after a successful OpenCL correctness test and during detected memory pressure.
- Default configured cap: 256 MiB; adjustable from the GPU tab, up to 512 MiB.
- The live VRAM monitor determines how much space is actually safe to use.
- At least 1.5 GiB or 20% of reported VRAM (whichever is larger) is kept as a graphics reserve.
- Uses an LRU policy for disposable cached blocks.
- Provides explicit store, restore, discard and clear operations.
- A restored direct buffer is owned by the caller and must be freed with LWJGL MemoryUtil.memFree.

**Important limitation:** this is not an extension of Java heap, system RAM, or the Windows page file. It cannot move arbitrary objects from Minecraft or third-party mods. An integration must serialize or otherwise provide its own disposable data, keep a valid handle, and know how to rebuild data if the LRU cache evicts it. No Create/Aeronautics/Sable object is automatically moved to VRAM yet.

### Client render and tick workload telemetry

The Frames tab passively measures two client-thread workloads with NeoForge events:

- `RenderFrameEvent.Pre/Post` measures wall-clock time spent inside `GameRenderer.render()`.
- `ClientTickEvent.Pre/Post` measures client tick wall-clock time.
- Each metric uses a bounded 600-sample rolling window and reports average, p50, p95, p99, peak and slow-sample counts.
- Percentiles are recalculated in batches rather than sorting on every rendered frame.
- The collector is independently configurable and can be disabled from the Frames tab.

**Measurement boundary:** render-call duration is not the time between displayed frames. It does not include presentation cadence or VSync waiting outside `GameRenderer.render()`, and it is not GPU execution time. Client tick measurements do not replace integrated-server or dedicated-server profiling. Use these metrics to distinguish expensive client rendering work from slow client logic, then capture JFR for method-level investigation.

### Create contraption tick telemetry

When Create is installed and its Fiximizer integration toggle is enabled, Fiximizer passively measures server-side tick time for entities inheriting Create's `AbstractContraptionEntity`.

- Uses NeoForge entity pre/post tick events and a bounded rolling window of the last 100 logical-server ticks.
- Reports total contraption entity ticks, average time charged per server tick, average and peak time per contraption entity tick, and the most expensive entity type seen during the current session.
- Uses class-hierarchy name matching and has no compile-time or runtime hard dependency on Create classes.
- Does not cancel, reorder, accelerate or otherwise change Create entity behavior.

**Scope:** this measurement is available for a local integrated single-player server. It does not measure remote dedicated-server CPU time, Create kinetic block-entity ticks, rendering, or arbitrary Create methods. Use the JFR profiler for whole-JVM method hotspots. This is a diagnostic integration, not yet an automatic FPS optimization or GPU offload.

### Sable physics substep telemetry

When Sable is installed, Fiximizer attaches to its public NeoForge pre/post physics-step events at runtime. This is an optional integration: no Sable classes are linked at compile time, and event registration is skipped when Sable is absent.

- Measures the latest 1,000 physics substeps across the integrated server's Sable physics systems.
- Reports substep count, average and peak wall-clock duration, total measured time, and the dimension with the largest accumulated cost in the window.
- Uses the Sable physics-system instance supplied by each event to pair pre/post timestamps.
- Runs only when the Sable integration toggle is enabled. It does not modify the timestep, forces, pipeline, entity order, or physics state.

**Measurement boundary:** the timer starts at Sable's pre-physics event and ends at its post-physics event. It includes Sable's Java-side preparation, queued-force application, the physics-pipeline call (including native Rust/Rapier work), and pose/wake-up updates between those events. It is not a pure Rapier benchmark or a native CPU-time breakdown. Telemetry is available for the local integrated single-player server; it is not registered on a remote dedicated server by the client dashboard.

### Five user-selected profiles

| Profile | Worker limit | Queue limit | GPU policy |
|---|---:|---:|---|
| Game smoothness | 2 | 48 | Conservative |
| Low latency | 2 | 32 | Very conservative |
| Balanced | 3 | 128 | Standard adaptive |
| Maximum performance | 4 | 256 | More permissive |
| Memory saver | 1 | 16 | Disabled |

Profiles regulate Fiximizer-owned background work only. They do not reorder Minecraft's render thread or third-party mods' workers.

### Automatic memory guard

- Samples Java heap and available physical memory.
- Enters temporary Memory Saver when heap pressure persists or available physical RAM is critically low.
- Uses separate recovery thresholds and multiple samples to avoid rapid switching.
- Restores the previously selected profile after memory recovers.
- Does not overwrite user preferences, call System.gc(), unload third-party objects or alter JVM heap settings at runtime.

The guard reduces Fiximizer's own queue and worker usage. The VRAM cache can only retain temporary buffers explicitly supplied by a Fiximizer integration; it does not automatically free memory owned by other mods.

### Mod-specific integration gates

Fiximizer detects the loaded NeoForge ModContainer, mod ID and installed version for Create, Create Aeronautics, Sable, Distant Horizons and Voxy. Each target has an independent toggle. The runForInstalledMod gate invokes an adapter only when that exact mod is loaded and enabled.

**Current scope:** detection, version reporting, toggles and conditional extension points are available. Create has a passive server-side contraption entity tick profiler, and Sable has passive physics-substep telemetry through its public NeoForge events. Intrusive Create/Aeronautics/Sable optimization patches are not shipped and will be considered only after reproducible profiling and version-specific compatibility tests.

### JVM argument advisor

- Detects physical RAM and loaded mod count.
- Recommends conservative -Xms and -Xmx values, rounded to integer MiB.
- Copies the argument line or saves it to config/fiximizer-jvm-arguments.txt.
- Leaves Java 21's default garbage collector selection unchanged.

JVM heap bounds cannot be modified by a running game. Paste the generated line into Prism Launcher → Edit Instance → Settings → Java → JVM arguments, then restart.

### Profiler and GPU telemetry

- Java Flight Recorder captures CPU execution samples, thread CPU load, garbage collection and sampled allocations.
- Automatic reports show hot methods, thread names, package namespace candidates and allocation hotspots.
- OSHI provides optional GPU telemetry; LWJGL checks for an installed OpenCL runtime and GPU devices.
- ComputeRouter only considers a Fiximizer-owned workload with a registered tested kernel when CPU/GPU conditions and the selected profile allow it.

OpenCL detection is not itself GPU offload. The OpenCL ICD/runtime is provided by the installed graphics driver. No arbitrary Minecraft, Create or Sable calculation is automatically converted to OpenCL.

### Build

Windows one-click build:

    build.bat

The builder searches for a full JDK 21. If needed, it downloads Eclipse Temurin from the official Adoptium API, verifies the SHA-256 checksum, and installs it locally in .tools/jdk-21-temurin. It does not change system Java settings.

More details: BUILDING.md.

Manual build:

- Windows: gradlew.bat build
- Linux/macOS: ./gradlew build

### Why the GPU path is OpenCL and not Rust?

OpenCL lets Fiximizer test GPU computation on supported vendors using the existing LWJGL runtime. Rust would add JNI and separate native binaries, but would not automatically speed up third-party Java methods. Rust can be evaluated later for a measured CPU-side bottleneck; it is not a substitute for a GPU compute API.

## Roadmap

- [ ] Validate client render/tick telemetry in a real 1.21.1 modpack and compare its readings with an external frame-time capture

- [x] NeoForge 1.21.1 project foundation
- [x] Five user-selectable gameplay profiles
- [x] Dashboard via F8 and the mod-list Config button
- [x] Automatic heap/physical-memory pressure guard
- [x] JVM argument recommendation and export
- [x] Loaded-mod/version detection and conditional integration gate
- [x] Russian and English UI
- [x] JFR hotspot, thread and package reports
- [x] OpenCL discovery and GPU telemetry
- [x] Bounded CPU/GPU OpenCL self-test with result validation
- [x] Passive Create server-side contraption entity tick profiler (100-tick rolling window)
- [x] Passive Sable physics substep profiler (last 1,000 pre/post event pairs)
- [x] Client render-call and client-tick workload profiler (600-sample rolling windows)
- [x] VRAM budgeting and explicit disposable cache block API
- [ ] First production Create optimization based on reproducible profiling data
- [ ] First Sable optimization based on reproducible physics profiling data
- [ ] Benchmark real mod calculations with a per-workload CPU/GPU comparison
- [ ] First real OpenCL kernel for an actual Fiximizer-owned gameplay workload
- [ ] Use VRAM cache from an integration with serialization/rebuild support
- [ ] Rolling benchmark comparison and before/after reports

## License

MIT. See the LICENSE file.

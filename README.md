# Fiximizer

**Adaptive diagnostics, targeted mod integration gates, memory pressure control and experimental GPU compute for Minecraft 1.21.1 / NeoForge.**

## Current status

**0.5.0-alpha — experimental OpenCL benchmark and bounded VRAM cache**

### In-game menu

- Open the dashboard with F8.
- Open the same Fiximizer control center from the Config button in NeoForge's mod list.
- Five sections: Overview, Optimization, Integrations, Java / JVM, and GPU / VRAM.
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

**Current scope:** detection, version reporting, toggles and conditional extension points are available. Intrusive Create/Aeronautics/Sable patches are not yet shipped. They will be added only after profiling and version-specific compatibility tests.

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
- [x] VRAM budgeting and explicit disposable cache block API
- [ ] First production Create integration using real profiled data
- [ ] First production Sable integration using real profiled data
- [ ] Benchmark real mod calculations with a per-workload CPU/GPU comparison
- [ ] First real OpenCL kernel for an actual Fiximizer-owned gameplay workload
- [ ] Use VRAM cache from an integration with serialization/rebuild support
- [ ] Rolling benchmark comparison and before/after reports

## License

MIT. See the LICENSE file.

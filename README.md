# Fiximizer

**Intelligent diagnostics, profiling and adaptive task scheduling for Minecraft 1.21.1 / NeoForge.**

Fiximizer is a modular performance toolkit. It measures the runtime, records Java Flight Recorder profiles, detects optional mod and GPU capabilities, and provides a priority scheduler for work owned by Fiximizer integrations.

## Current status

**0.3.0-alpha — gameplay profiles and automatic Windows builder**

### Gameplay optimization profiles

Change the active profile from the F8 dashboard. The choice is saved in `config/fiximizer-performance.properties`.

| Profile | Worker cap | Queue cap | GPU policy |
|---|---:|---:|---|
| Game smoothness | 2 | 48 | Conservative; only large workloads |
| Low latency | 2 | 32 | Very conservative; avoids small offloads |
| Balanced | 3 | 128 | Standard adaptive policy |
| Maximum performance | 4 | 256 | More permissive offload thresholds |
| Memory saver | 1 | 16 | OpenCL offload disabled |

Profiles also set task-queue ordering and CPU-load thresholds for reducing worker concurrency. `maxBackgroundWorkers` in the NeoForge config remains a global upper limit.

These settings apply to Fiximizer-owned background tasks. They do not modify Minecraft's render thread, other mods' internal workers, operating-system thread priorities, heap size, or garbage collector. "Memory saver" limits Fiximizer's worker and queue footprint; it does not forcibly reduce the JVM heap.

### Included

- F8 diagnostics dashboard (rebindable in Controls).
- Heap, JVM, processor, CPU and thread metrics.
- Java Flight Recorder (JFR) recording with start/stop controls.
- An automatic offline hotspot report beside each JFR file: CPU methods ranked by execution-sample share, sampled allocation hotspots, and HIGH / MEDIUM / LOW investigation priorities.
- JFR captures execution samples, CPU load, thread CPU load, garbage collection and allocation samples.
- A scoped profiler API for timing Fiximizer-owned sections.
- A bounded priority queue for Fiximizer-managed background tasks: CRITICAL, HIGH, NORMAL, and BACKGROUND.
- Adaptive worker count based on the selected gameplay profile and measured system CPU load.
- Optional GPU telemetry through OSHI and OpenCL device detection through LWJGL.
- Russian and English translations.
- One-click Windows build script with isolated JDK 21 discovery/installation and SHA-256 verification, adapted from the builder in the Rusify repository.

### Important limitations

- JFR rankings are statistical. They help identify hot Java methods, but sample share is not exact method wall time.
- Class/package names do not always map one-to-one to mod IDs; use the ranked methods and stack traces to choose an integration target.
- The built-in scoped profiler measures only code explicitly instrumented by Fiximizer.
- Priority scheduling applies only to Fiximizer-submitted background tasks. It does not change Minecraft, Create, Sable or other mods' thread priorities.
- OpenCL detection is implemented, but this version does **not** move Minecraft, Create or Sable calculations to the GPU. A workload must have a dedicated, tested OpenCL kernel and enough independent data to outweigh dispatch and transfer costs.
- The OpenCL runtime/ICD is supplied by the graphics driver. The Windows build script does not install GPU drivers or OpenCL runtimes.
- GPU monitoring depends on the driver and operating system exposing utilization/sensor data. Unsupported values are shown as unavailable.

## Compute routing design

The CPU/GPU router is profile-aware. A workload can be considered for OpenCL only when:

1. A verified OpenCL kernel exists for that exact workload.
2. The selected profile allows GPU offload.
3. Work size and data-transfer volume exceed that profile's thresholds.
4. CPU load is high enough and the GPU has measured headroom.

The OpenCL ICD/runtime is provided by the graphics driver; Fiximizer bundles the matching LWJGL 3.3.3 Java binding for Minecraft 1.21.1.

## Requirements

- Minecraft **1.21.1**
- NeoForge **21.1.265 or newer in the 21.1 line**
- Java **21**
- Optional: a working OpenCL runtime from the GPU driver for OpenCL device detection

## Build

Windows one-click build:

```bat
build.bat
```

The script searches for a full JDK 21. If none is found, it downloads Eclipse Temurin 21 from the official Adoptium API, verifies the SHA-256 checksum, and installs it locally under `.tools/jdk-21-temurin`. It does not change system Java settings.

More details: [BUILDING.md](BUILDING.md).

Manual build:

- Windows: `gradlew.bat build`
- Linux/macOS: `./gradlew build`

The JAR is generated in `build/libs`. GitHub Actions builds the project on Linux and Windows.

## Design principles

1. **Measure before optimizing.**
2. **Never interfere with third-party threads or the Minecraft main thread.**
3. **Keep integrations optional and independently configurable.**
4. **No forced garbage collection or arbitrary thread-priority changes.**
5. **No telemetry uploads.** Profiles and hardware readings remain local.

## Roadmap

- [x] NeoForge 1.21.1 project foundation
- [x] Local diagnostics dashboard
- [x] Java Flight Recorder capture
- [x] Automatic hotspot and investigation-priority report
- [x] Fiximizer-scoped profiler API
- [x] Prioritized adaptive worker scheduler
- [x] Five gameplay optimization profiles
- [x] Profile-aware CPU/GPU routing policy
- [x] Automatic Windows JDK 21 builder
- [x] GPU telemetry and OpenCL device detection
- [ ] Thread hotspot analysis in JFR reports
- [ ] First real OpenCL kernel for a Fiximizer-owned batch workload
- [ ] Profiling-guided Create / Aeronautics integration
- [ ] Profiling-guided Sable integration
- [ ] Bounded rolling history and JSON report export

## Languages

The in-game runtime is Java because it integrates directly with NeoForge and Minecraft's JVM. Python can be useful for additional offline analysis of JFR-derived reports. Rust is worth considering only for a later, isolated native compute library if profiling proves Java itself is the bottleneck; it is not required for the current design.

## License

MIT. See the LICENSE file.

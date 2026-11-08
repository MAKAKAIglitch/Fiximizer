# Fiximizer

**Intelligent diagnostics, profiling and adaptive task scheduling for Minecraft 1.21.1 / NeoForge.**

Fiximizer is a modular performance toolkit. It measures the runtime, records Java Flight Recorder profiles, detects optional mod and GPU capabilities, and provides a priority scheduler for work owned by Fiximizer integrations.

## Current status

**0.2.0-alpha — profiler and compute foundation**

### Included

- F8 diagnostics dashboard (rebindable in Controls).
- Heap, JVM, processor, CPU and thread metrics.
- Java Flight Recorder (JFR) recording with start/stop controls.
- An automatic offline hotspot report beside each JFR file: CPU methods ranked by execution-sample share, sampled allocation hotspots, and HIGH / MEDIUM / LOW investigation priorities.
- JFR captures execution samples, CPU load, thread CPU load, garbage collection and allocation samples. Open JFR files with Java Mission Control or inspect them with the JDK jfr tool.
- A scoped profiler API for timing Fiximizer-owned sections.
- A bounded priority queue for Fiximizer-managed background tasks: CRITICAL, HIGH, NORMAL, and BACKGROUND.
- Adaptive worker count: the scheduler reduces its own concurrency as system CPU load rises.
- Optional GPU telemetry through OSHI and OpenCL device detection through LWJGL.
- Russian and English translations.

### Important limitations

- JFR rankings are statistical. They help identify hot Java methods, but sample share is not exact method wall time.
- Class/package names do not always map one-to-one to mod IDs; use the ranked methods and stack traces to choose an integration target.
- The built-in scoped profiler measures only code explicitly instrumented by Fiximizer.
- Priority scheduling applies only to Fiximizer-submitted background tasks. It does not change Minecraft, Create, Sable or other mods' thread priorities.
- OpenCL detection is implemented, but this version does **not** move Minecraft, Create or Sable calculations to the GPU. A workload must have a dedicated, tested OpenCL kernel and enough independent data to outweigh dispatch and transfer costs.
- GPU monitoring depends on the driver and operating system exposing utilization/sensor data. Unsupported values are shown as unavailable.

## Compute routing design

The CPU/GPU router is deliberately conservative. A future task can be considered for OpenCL only when all conditions are met:

1. A verified OpenCL kernel exists for that exact workload.
2. The workload is large and data-parallel enough to offset transfers and dispatch overhead.
3. CPU load is high enough to make offload useful.
4. The GPU has measured headroom and enough memory.

The OpenCL ICD/runtime is provided by the graphics driver; Fiximizer bundles the matching LWJGL 3.3.3 Java binding for Minecraft 1.21.1.

## Requirements

- Minecraft **1.21.1**
- NeoForge **21.1.265 or newer in the 21.1 line**
- Java **21**
- Optional: a working OpenCL runtime from the GPU driver for OpenCL device detection

## Build

- Windows: gradlew.bat build
- Linux/macOS: ./gradlew build

The JAR is generated in build/libs. GitHub Actions builds the project and uploads the JAR as a workflow artifact.

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
- [x] GPU telemetry and OpenCL device detection
- [x] Conservative CPU/GPU routing policy
- [ ] First real OpenCL kernel for a Fiximizer-owned batch workload
- [ ] Profiling-guided Create / Aeronautics integration
- [ ] Profiling-guided Sable integration
- [ ] Bounded rolling history and JSON report export

## Languages

The in-game runtime is Java because it integrates directly with NeoForge and Minecraft's JVM. Python can be useful for additional offline analysis of CSV/JFR-derived reports. Rust is worth considering only for a later, isolated native compute library if profiling proves Java itself is the bottleneck; it is not required for the current design.

## License

MIT. See the LICENSE file.

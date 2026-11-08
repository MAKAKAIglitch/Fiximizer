# Fiximizer

**Adaptive diagnostics, targeted mod integration gates, memory pressure control and JVM guidance for Minecraft 1.21.1 / NeoForge.**

## Current status

**0.4.0-alpha — smart menu, guarded integrations and automatic memory saver**

### In-game menu

- Open the dashboard with F8.
- Open the same Fiximizer control center from the Config button in NeoForge's mod list. Better ModList uses NeoForge's `IConfigScreenFactory` extension point, so no Mod Menu compatibility dependency is required.
- Four sections: Overview, Optimization, Integrations, and Java / JVM.
- Russian and English translations.

### Five user-selected profiles

| Profile | Worker limit | Queue limit | GPU policy |
|---|---:|---:|---|
| Game smoothness | 2 | 48 | Conservative |
| Low latency | 2 | 32 | Very conservative |
| Balanced | 3 | 128 | Standard adaptive |
| Maximum performance | 4 | 256 | More permissive |
| Memory saver | 1 | 16 | Disabled |

Profiles regulate Fiximizer-owned background work only. They do not reorder Minecraft's render thread or other mods' workers.

### Automatic memory guard

- Samples the Java heap and physical-memory availability.
- Enters temporary Memory Saver after heap usage stays above the configured threshold for several samples, or immediately at a critical heap/physical-memory condition.
- Uses separate recovery threshold and multiple recovery samples to avoid rapidly switching back and forth.
- Automatically restores the previously selected profile when memory pressure clears.
- Does not overwrite the user's saved profile, call `System.gc()`, unload objects owned by other mods, or change JVM heap arguments at runtime.

The automatic guard reduces Fiximizer's own queue capacity and background worker count and disables its future OpenCL offload policy during the override. It cannot reclaim memory already allocated by Minecraft or third-party mods.

### Mod-specific integration gates

Fiximizer detects the actual loaded NeoForge `ModContainer`, mod ID and installed version for Create, Create Aeronautics, Sable, Distant Horizons and Voxy. Each target has an independent toggle. The shared `runForInstalledMod` gate only invokes an adapter callback when the exact target is loaded and enabled; absent mods are never passed to that callback.

**Current scope:** this release establishes the detection, version reporting, independent toggles and conditional adapter entry point. It does not yet ship intrusive Create/Aeronautics/Sable patches. Those will be added one by one after profiling and verifying each target mod/version. An enabled toggle is permission for a future adapter, not a claim that a third-party mod has already been modified.

### JVM argument advisor

- Detects installed physical RAM and loaded mod count.
- Recommends conservative `-Xms` and `-Xmx` values, rounded to integer MiB.
- Copies the argument line or saves it to `config/fiximizer-jvm-arguments.txt`.
- Leaves Java 21's default garbage collector selection unchanged.

JVM heap bounds cannot be modified by a running game. Paste the generated line into Prism Launcher → Edit Instance → Settings → Java → JVM arguments, then restart. Fiximizer does not edit Prism's instance files automatically.

### Profiler and GPU

- Java Flight Recorder captures CPU execution samples, thread CPU load, garbage collection and sampled allocations.
- Automatic offline reports show hot methods, thread names, package namespace candidates and allocation hotspots.
- OSHI provides optional GPU telemetry; LWJGL checks for an installed OpenCL runtime and GPU devices.
- ComputeRouter only considers a tested OpenCL kernel when the active profile allows it, CPU load is high enough and the GPU has measured headroom.

OpenCL detection is not the same as GPU offload. No Minecraft/Create/Sable calculation is moved to the GPU until an exact workload has a separately tested kernel. The OpenCL ICD/runtime is provided by the graphics driver.

### Build

Windows one-click build:

```bat
build.bat
```

The builder searches for a full JDK 21. If needed, it downloads Eclipse Temurin from the official Adoptium API, verifies the SHA-256 checksum, and installs it locally in `.tools/jdk-21-temurin`. It does not change system Java settings.

More details: [BUILDING.md](BUILDING.md).

Manual build:

- Windows: `gradlew.bat build`
- Linux/macOS: `./gradlew build`

### Why no Rust native module yet?

The current hotspots are JVM workload and game-mod integration problems, not a demonstrated bottleneck in Fiximizer's own Java code. Rust would add JNI and per-platform native binaries without making third-party Java methods automatically faster. Java remains the runtime; Rust will be considered only for a measured, isolated compute kernel where native code demonstrates a repeatable win.

## Roadmap

- [x] NeoForge 1.21.1 project foundation
- [x] Five user-selectable gameplay profiles
- [x] Custom dashboard available via F8 and the mod-list Config button
- [x] Automatic heap/physical-memory pressure guard with hysteresis
- [x] JVM argument recommendation and export
- [x] Loaded-mod/version detection and conditional integration gate
- [x] Russian and English UI
- [x] JFR hotspot, thread and package reports
- [x] OpenCL discovery and GPU telemetry
- [ ] First validated Create integration patch
- [ ] First validated Sable integration patch
- [ ] First real OpenCL kernel for a Fiximizer-owned data-parallel workload
- [ ] Rolling benchmark comparison and before/after reports

## License

MIT. See the LICENSE file.

# Fiximizer

**Intelligent diagnostics and optimization framework for Minecraft 1.21.1 / NeoForge.**

Fiximizer is being built as a modular performance toolkit. It is designed to inspect a modded installation, identify available compatibility targets, expose useful runtime diagnostics, and add carefully tested optimizations behind individual settings.

## Current status

**0.1.0-alpha — diagnostics foundation**

This first development build provides:

- A client diagnostics screen, opened with **F8** (the key can be changed in Controls).
- Configurable diagnostic sampling interval and diagnostics enable/disable setting.
- Heap, JVM, processor and loaded-mod information.
- Optional detection of Create, Create Aeronautics, Sable, Distant Horizons and Voxy.
- A standard NeoForge configuration screen in the Mods menu.

The compatibility scan only reports whether a target mod is present. It does not claim that an optimization integration already exists.

**Important:** this alpha does not apply performance patches yet and does not change gameplay behavior. The first goal is to establish a reliable baseline before adding optimizations. Future patches will be added as independent modules, with explicit settings and profiling evidence.

## Requirements

- Minecraft **1.21.1**
- NeoForge **21.1.265 or newer in the 21.1 line**
- Java **21**

## Build

Open the project in IntelliJ IDEA as a Gradle project, or run the included wrapper:

- Windows: gradlew.bat build
- Linux/macOS: ./gradlew build

The first run downloads the Gradle distribution and required Minecraft/NeoForge dependencies. The mod JAR is generated in build/libs.

GitHub Actions builds the project on pushes and pull requests.

## Design principles

1. **Measure before optimizing.** No performance claims without repeatable profiling.
2. **Modular integrations.** Optional mod support must not become a hard dependency.
3. **User control.** Every behavior-changing optimization gets its own setting and can be disabled.
4. **Safe defaults.** No forced garbage collection, arbitrary thread manipulation, or destructive world changes.
5. **No telemetry.** Diagnostics stay local; Fiximizer does not send performance data anywhere.

## Roadmap

- [x] NeoForge 1.21.1 project foundation
- [x] Client diagnostics screen and configurable sampling
- [x] Optional-mod detection
- [ ] Bounded diagnostic history and exportable local reports
- [ ] Profiling-guided entity and tick optimizations
- [ ] Optional Create / Aeronautics compatibility module
- [ ] Optional Sable physics compatibility module
- [ ] Optional Distant Horizons / Voxy compatibility modules

## License

MIT. See the LICENSE file.

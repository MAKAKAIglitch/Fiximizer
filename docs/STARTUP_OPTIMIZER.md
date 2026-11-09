# Startup Optimizer

## Scope

The Startup Optimizer reduces work that Fiximizer itself performs while Minecraft and NeoForge are bringing up the client. It does not claim to accelerate arbitrary third-party mod initializers, and it does not alter the required mod lifecycle.

The first implementation focuses on safe deferral and trustworthy timing:

- Record Fiximizer entrypoint construction, client construction, the client setup event, first title-screen tick, stable client readiness, optional mod scanning, and optional-service scheduling.
- Delay optional compatibility discovery and optional diagnostics until the client has completed 40 stable title-screen or world ticks.
- Keep the compatibility scan on the client thread because NeoForge ModList and ModContainer access is lifecycle-sensitive.
- Keep hardware discovery and OpenCL probing on their existing optional/asynchronous paths.
- Let the user copy a local startup report from the dashboard.

## Compatibility guarantees

The Startup Optimizer:

- Does not skip, suppress, or reorder NeoForge lifecycle callbacks.
- Does not change another mod's initialization state or registration order.
- Does not add Mixin, coremod, bytecode transformation, classloader, or launch-plugin hooks.
- Does not rewrite mod metadata, registries, datapacks, resource packs, or saved worlds.
- Does not move Minecraft-owned or third-party mod work to a worker thread.
- Starts optional Fiximizer components independently; a runtime/linkage failure in one optional component is logged and does not prevent the remaining optional startup work from being attempted.
- Keeps an explicit dashboard refresh action for users who need compatibility detection before the deferred scan runs.

No third-party compatibility claim is implied by the absence of bytecode patches. The normal modpack matrix still needs to be tested with real combinations and versions.

## Measurement semantics

The JVM-to-first-title-screen value comes from RuntimeMXBean.getUptime() and is sampled only after the title screen is visible. It includes the whole Java process startup path: JVM, Minecraft, NeoForge, all mods, resource loading, filesystem activity, and machine-specific startup overhead. It must not be reported as time spent by Fiximizer or any single mod.

Individual Fiximizer phases use System.nanoTime() milestones. The timeline is bounded to a fixed number of atomic timestamps, records each milestone once, and is unaffected by wall-clock adjustments.

The text report intentionally excludes player identifiers, local file paths, server addresses, and installed mod lists.

## Language and native-code policy

The integration layer remains Java 21 because it directly uses NeoForge and Minecraft APIs and is already loaded by the JVM. This first startup implementation adds no Kotlin, Rust, JNI, or native runtime.

A native library would add extraction/loading and platform packaging work to the startup path. Rust should only be reconsidered for a large, independent CPU-bound analysis task after profiling proves that the Java implementation is a bottleneck and end-to-end measurements include JNI/FFI, copying, and native loading costs. Kotlin may be useful for isolated maintainability boundaries, but it is not inherently faster than Java on the JVM.

## Validation plan

For each supported NeoForge 21.1.x build:

1. Build and run unit tests on Java 21, Windows and Linux.
2. Compare at least five cold starts and five warm starts with the same launcher, Java runtime, mod list, world, and resource packs.
3. Report median and p95 JVM-to-title time, Fiximizer-owned phase durations, and the time at which optional diagnostics are scheduled.
4. Verify startup with no optional targets and with Create, Sable, Distant Horizons, and Voxy present individually and in representative combinations.
5. Verify that a missing or incompatible optional integration does not block Minecraft startup.
6. Compare logs and behavior with optional detection enabled and disabled.
7. Confirm that a manual dashboard refresh still works before the deferred scan.

No FPS, TPS, or fixed startup-time improvement is claimed until these tests are run on a reproducible modpack.

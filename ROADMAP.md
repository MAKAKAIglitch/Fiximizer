# Fiximizer Roadmap

This roadmap describes the planned engineering priorities for making Fiximizer
more efficient, predictable, and lightweight while preserving the accuracy of
its diagnostics.

The order is intentional: measure Fiximizer's own cost first, reduce the cost
of its hot paths, make diagnostics respond to system load, isolate optional
integrations, and only then consider native compute.

> These are development priorities, not fixed release dates or promises. The
> implementation order may be adjusted when profiling or testing reveals a
> more important bottleneck.

## Priority 1 — Startup Profiler

**Goal:** Measure the time Fiximizer itself adds during Minecraft startup.

Planned work:

- Measure the total duration of Fiximizer initialization.
- Record timings for major initialization phases and individual subsystems.
- Identify slow registration, setup, resource-loading, and initialization paths.
- Keep the profiler's own overhead small and avoid blocking the main thread.
- Make the collected timings available in a readable diagnostic report.

**Completion criteria:**

- Startup timings are repeatable and clearly attributed to Fiximizer phases.
- Measurement overhead is small enough not to distort the results.
- Reports distinguish Fiximizer initialization time from total Minecraft startup
  time.

## Priority 2 — Zero-allocation Telemetry

**Goal:** Minimize recurring memory allocations in telemetry and other
frequently executed code paths.

Planned work:

- Audit client tick, server tick, render, and event-listener hot paths.
- Avoid creating temporary objects for routine measurements where practical.
- Prefer primitive values, reusable storage, and bounded ring buffers.
- Move aggregation and report formatting away from critical game threads.
- Measure allocation rate and garbage-collection impact before and after each
  change.

**Completion criteria:**

- Steady-state telemetry creates no avoidable recurring allocations in its
  critical measurement paths.
- All retained samples and queues have explicit size limits.
- Measurements confirm that allocation pressure has decreased without losing
  accuracy or useful diagnostic data.

*Zero-allocation is a target for carefully selected hot paths, not a claim that
the entire mod can operate without allocations during initialization, events,
or report generation.*

## Priority 3 — Adaptive Diagnostics

**Goal:** Automatically reduce the overhead of optional monitoring when the
client or integrated server is under heavy load.

Planned work:

- Define clear signals for elevated system or game workload.
- Dynamically reduce the sampling frequency of non-essential diagnostics.
- Preserve critical stall detection and bounded incident capture.
- Restore normal sampling rates when the load returns to an acceptable level.
- Expose the current diagnostic activity level and relevant decisions to the
  user.

**Completion criteria:**

- Sampling changes are bounded, predictable, and do not oscillate rapidly.
- Essential watchdog functionality remains available under load.
- Adaptive behavior reduces monitoring overhead in controlled benchmarks.
- Users can understand which diagnostic features have been throttled and why.

## Priority 4 — Lazy Integrations

**Goal:** Keep optional mod integrations isolated and avoid unnecessary work
when their target mods are not installed.

Planned work:

- Detect optional dependencies without introducing hard dependencies.
- Register integration hooks only when the corresponding mod is present.
- Keep optional integration classes isolated from common initialization paths.
- Avoid loading integration-specific classes or performing integration setup
  when the target mod is absent.
- Preserve graceful behavior when an optional mod is missing or incompatible.

**Completion criteria:**

- Fiximizer starts correctly with none, some, or all supported optional mods.
- Missing optional mods do not cause class-loading errors or unnecessary
  initialization work.
- Integration behavior remains isolated and independently configurable.

## Priority 5 — Rust Compute Module

**Goal:** Evaluate native Rust code only for a proven compute bottleneck that
benefits from moving work outside the JVM.

This is an exploratory priority, not a commitment to rewrite parts of Fiximizer
in Rust.

Planned work:

- Identify a specific CPU-intensive computation through profiling.
- Build a representative Java baseline and a realistic native prototype.
- Include JNI or other interoperability overhead in end-to-end measurements.
- Compare execution time, memory use, startup cost, packaging complexity, and
  platform compatibility.
- Keep a Java implementation or fallback when it is more practical or
  performant.

**Adoption criteria:**

- A repeatable benchmark demonstrates a meaningful end-to-end improvement for
  a real Fiximizer workload.
- Interoperability and native-library overhead do not erase the gain.
- The module can fail safely without preventing Minecraft or Fiximizer from
  starting.
- Distribution and maintenance costs are justified by the measured benefit.

If no suitable workload is found, the Rust module should remain unimplemented.
Using Rust by itself is not a performance objective.

---

## Engineering Principles

- **Measure before optimizing.** Changes should be motivated by profiles and
  repeatable benchmarks, not assumptions.
- **Keep critical paths lightweight.** Game-thread work should be bounded and
  predictable.
- **Bound background work.** Queues, sample histories, caches, and report
  retention must have explicit limits.
- **Preserve correctness.** Performance improvements must not silently discard
  important measurements or change Minecraft and third-party mod behavior.
- **Make optional features truly optional.** Missing integrations or native
  components must not prevent the core mod from loading.
- **Verify end-to-end results.** Include instrumentation, allocation, and
  interoperability overhead when evaluating performance.

## Overall Direction

Fiximizer should first become better at measuring its own cost, then reduce
that cost. Adaptive behavior and isolated integrations come next. Native
compute is worth considering only when profiling identifies a concrete task
and benchmarks prove that it is beneficial.

# Fiximizer — Improvement Proposals and Integration Plan

**Status:** design proposals; documentation-only change.  
**Audit baseline:** `main` at `9728fede8faf6ef6d34f1c4a87d1ecba3c5d9d19` (9 November 2026).  
**Target baseline:** Minecraft 1.21.1, NeoForge 21.1.x, Java 21.  
**Scope:** prepare independently reviewable work for later implementation and integration. This document does not merge, rebase or modify the existing feature branches.

## 1. Executive summary

Fiximizer already has a useful diagnostic foundation: bounded runtime measurements, JFR recording and analysis, incident reports, a stall watchdog, optional mod telemetry, a memory-pressure controller and an adaptive scheduler for Fiximizer-owned tasks.

The next improvement should make the path from **symptom → evidence → diagnosis → reproducible test → compatible action** much stronger. The following additions are recommended:

1. **Diagnostic Bundle** — a predictable, local archive for debugging and AI-assisted analysis, with privacy review before export.
2. **Benchmark Engine** — repeatable scenarios and versioned baselines, not one-off best-case timings.
3. **Runtime Health Check** — a clear status/capability view for each subsystem, including fallback reasons.
4. **Memory follow-ups** — prioritize measured, high-retention immutable structures instead of broad object rewriting.
5. **Network telemetry follow-ups** — distinguish raw buffer observations, server-reported latency and local processing delay.
6. **Compatibility capability registry** — use version/capability checks and feature-level conflict decisions rather than mod-ID detection alone.

The existing Startup, Memory and Network implementations remain in their own branches. Their work should be reviewed and integrated separately after comparison with the current `main`.

## 2. Repository audit snapshot

The branch comparisons below were made against the audit baseline commit above. Ahead/behind counts can change as branches advance; re-run the comparisons immediately before any future integration.

| Branch | Snapshot against `main` | Notable independent work |
|---|---:|---|
| `feature/startup-optimizer` | 16 ahead / 2 behind | `StartupOptimizer`, `StartupProfiler`, `StartupTimeline`, startup tests and design notes |
| `feature/memory-optimizer` | 54 ahead / 4 behind | Bounded string canonicalization, runtime memory capability detection and tests |
| `feature/network-engine` | 53 ahead / 2 behind | Passive Netty telemetry, optional TCP policy, Java/Rust statistics backend, compatibility detection and tests |
| `feature/network-optimizer-compat` | 0 ahead / 4 behind | No unique commits relative to its merge base at audit time; refresh before deciding whether it is still needed |

### 2.1 Startup branch

The separate startup branch already contains a first implementation and a design document. It records Fiximizer milestones, delays optional work until the client has been stable, and creates a small local startup report.

A key semantic distinction is already documented there: **JVM start → first title screen** includes JVM, Minecraft, NeoForge, all mods, resources, filesystem and machine startup. It is not time attributable to Fiximizer or to an individual mod. Fiximizer-owned phases must be reported separately.

The proposed follow-up is validation and integration preparation, not a second competing Startup Profiler implementation.

### 2.2 Memory branch

The memory branch contains a bounded weak string table and runtime capability detection. The current string canonicalization path is deliberately narrow and is not proof of pack-wide heap savings. Its observed hit count and retained-heap measurements must determine whether that particular adapter is useful.

The next meaningful memory work is to profile large retained structures in real packs, then investigate only candidates with explicit immutability and ownership guarantees. FerriteCore's published technical notes are useful as architectural research, not as a source of code to copy: [FerriteCore memory-optimization notes](https://github.com/malte0811/FerriteCore/blob/0cef1f2add1f1329aa6e690e8e292acd625c5c6d/summary.md).

The memory branch also contains JVM capability detection for Compact Object Headers on runtimes where the actual VM option exists. Keep this as capability-aware advice; never infer support from the Java major version alone, and never assume Java 21 exposes a Java 25 option.

### 2.3 Network branch

The network branch is currently a **passive telemetry and narrowly scoped socket-policy experiment**, not a replacement transport or a packet-processing accelerator.

Its documented implementation observes raw Netty `ByteBuf` bytes/frames, samples Minecraft's server-reported player latency, has an optional TCP_NODELAY policy and offers Java/Rust implementations for the local percentile calculation.

Metric naming must remain exact:

- `inboundFrames` / `outboundFrames` count observed raw `ByteBuf` observations; they must not be presented as a guaranteed count of Minecraft protocol packets.
- `PlayerInfo#getLatency()` is the latency value reported by Minecraft for the local player. It is not an independent ICMP/UDP RTT measurement, packet-loss estimate or server-tick measurement.
- A client-only mod cannot remove Internet route latency or remote server processing time.

The Rust backend only summarizes a bounded local sample array. The live path is small, so the Java/Rust decision must account for the complete JNA/FFI call, data preparation/copying, warm-up and runtime cost. Keep Java as a reliable baseline and select Rust only after a repeatable end-to-end win on a representative workload. The current native packaging described by the branch is Windows x86_64 and Linux x86_64; do not advertise macOS native support until CI builds, packages and tests that artifact.

## 3. Diagnostic Bundle

### Goal

Produce one predictable archive that lets a developer or AI assistant understand a reported problem without requiring the user to manually collect unrelated logs and screenshots.

### Proposed contents

A versioned bundle may contain:

- `manifest.json` — bundle schema version, Fiximizer version, Minecraft/NeoForge/Java versions, operating system and feature states.
- `system.json` — available processors, physical memory totals, JVM heap limits, GC names and available hardware capabilities.
- `mods.json` — mod IDs and versions only when the user explicitly includes the mod inventory.
- `performance.json` — frame/client-tick/server-tick summaries, memory pressure, queue telemetry and the selected profile.
- `jfr-analysis.txt` — analysis summary for a recording the user selected or explicitly enabled for export.
- `incident.json` — relevant incident timeline, thread summary and correlation evidence when available.
- `network.json` — aggregate counters and metric definitions; never raw payloads.
- `errors.log` — a bounded, time-limited selection of relevant error messages.

The exporter should include only the sections applicable to the selected diagnostic category. For example, a rendering report should not automatically include network diagnostics or a full mod inventory.

### Privacy and data handling

- Generate the archive locally. Network upload is not part of the feature.
- Show the user a preview of included files and categories before writing the archive.
- Exclude chat, packet payloads, authentication data, session tokens, UUIDs, player names, server addresses and full local paths by default.
- Apply structured redaction before text sanitization; do not rely on a list of a few regular expressions as the only privacy boundary.
- Make mod inventory inclusion explicit because mod lists can reveal private pack contents.
- Bound report size, number of log lines, JFR exports and retained bundles.
- Use stable, documented JSON fields so future versions can be compared by tools.
- Record redaction counts and omissions in the manifest without recording the removed values.

### Acceptance tests

1. Unit-test redaction with representative paths, IPv4/IPv6 addresses, hostnames, UUIDs, tokens, chat fragments and malformed log lines.
2. Confirm that the archive contains no prohibited raw values after redaction.
3. Verify size and entry-count limits, path handling and duplicate-name behavior.
4. Verify that disabled diagnostics and missing JFR files produce a valid partial bundle rather than blocking export.
5. Test that exporting a bundle does not run compression or file I/O on render, client-tick, server-tick or Netty event-loop threads.

## 4. Benchmark Engine

### Goal

Replace isolated manual timings with comparable before/after measurements. A benchmark result must describe a workload, not claim a universal FPS gain.

### Core design

Each benchmark run should have a versioned manifest containing:

- benchmark ID and schema version;
- Minecraft, NeoForge, Java and Fiximizer versions;
- hardware capability summary;
- active mod/configuration fingerprint, with private paths and identifiers excluded;
- world/scenario identifier chosen by the user;
- render distance, simulation distance and relevant graphics settings;
- warm-up duration, measured duration, sample interval and number of repetitions;
- enabled Fiximizer features and detected overlapping optimizers.

A run should produce distributions and summaries, not only a single number:

- client frame/render-call p50, p95 and p99;
- client tick and integrated-server tick p50, p95 and p99;
- MSPT/TPS where a valid server-side measurement exists;
- JFR CPU/allocation sample summaries and GC pause data when recording is enabled;
- heap used/committed/max, process memory and available native/off-heap readings;
- scheduler queue wait, execution time and rejection counters;
- GPU metrics only when the driver/provider exposes trustworthy readings.

### Scenario model

Start with repeatable, user-selected scenarios rather than automated gameplay:

1. **Menu idle** — measure monitoring overhead without a world loaded.
2. **Stable scene** — fixed player position, camera, time and entity population.
3. **Entity-heavy scene** — a documented number and type of entities.
4. **Chunk traversal** — a repeatable route or prepared world area.
5. **Resource reload** — a user-triggered reload with a fixed resource-pack set.
6. **Integrated-server load** — a controlled single-player scene with a documented tick workload.

The same test pack, world, route, warm-up and settings must be used for baseline and candidate runs. Compare medians and tail percentiles across multiple runs; keep cold-start tests separate from warm-start tests.

### Output and acceptance

- Store baselines locally in a versioned format.
- Allow explicit export/import of benchmark files.
- Show baseline, candidate, absolute delta, relative delta, sample count and variability.
- Mark invalid comparisons when versions, settings or scenario fingerprints differ materially.
- Include the overhead of Fiximizer's own instrumentation.
- Add deterministic unit tests for aggregation, percentile calculation, missing samples and invalid manifests.
- Do not describe a result as an optimization unless a repeated test shows a useful end-to-end improvement without correctness or compatibility regressions.

## 5. Runtime Health Check

### Goal

Make it obvious which Fiximizer systems are working, which are intentionally disabled, which are unsupported on the current runtime and which failed.

### Proposed state model

Each subsystem should expose a small immutable status snapshot:

- `HEALTHY` — initialized and operating.
- `DISABLED` — disabled by configuration or selected profile.
- `DEGRADED` — core feature works, but an optional capability is missing.
- `UNSUPPORTED` — the current OS, architecture, JVM or loader does not expose the required capability.
- `FAILED` — initialization or a runtime operation failed; include a sanitized reason and fallback state.
- `NOT_STARTED` — lazy initialization has not been requested yet.

Subsystems to report include Startup, JFR, Diagnostics, Memory Guard, Scheduler, Memory Optimizer, Network Telemetry, Java/Rust backend, GPU telemetry, OpenCL, VRAM cache and optional mod integrations.

### Requirements

- Status collection must be cheap and must not probe hardware repeatedly from the render loop.
- Each status should expose the last transition time, a short reason, active fallback and whether user action is needed.
- A failed optional subsystem must not make unrelated subsystems fail.
- Do not label an unavailable GPU sensor as zero utilization.
- Distinguish "not measured" from a measured zero.
- Export the same sanitized status data through the Diagnostic Bundle.

## 6. Memory Optimizer follow-ups

1. Establish a retained-heap baseline using the same pack, world, Java runtime, heap settings and warm-up sequence.
2. Attribute large retained object groups to Minecraft, a mod or a shared library only when the evidence supports it.
3. Investigate block-state neighbor/property structures, multipart predicate/model reuse, immutable resource identifiers, baked quad data and VoxelShape data as separate candidates.
4. For each candidate, document whether data is immutable, who owns it, when it is created, and when it becomes invalid.
5. Compare any candidate against existing optimizers such as FerriteCore; avoid duplicate transformations and preserve per-feature switches.
6. Measure retained heap after comparable GC points, allocation rate, GC pause time, CPU overhead and frame/tick percentiles.
7. Keep an optimization opt-in until it has repeatable net benefit and mutation-isolation tests.

Do not treat a high heap-used reading alone as a leak. Do not report a fixed number of megabytes saved from a weak-table hit counter. The table's references, slots, hashing and counters are part of the cost.

## 7. Network Optimizer follow-ups

1. Rename/document raw buffer counters so the UI does not call them protocol packet counts.
2. Keep server-reported latency separate from independent RTT, event-loop delay, packet-processing duration and server tick delay.
3. Add byte-rate and buffer-rate windows using bounded primitive counters; add deeper sampling only when explicitly enabled.
4. Profile the current pipeline before adding handlers or mixins. Record handler placement and detect duplicate handler installation.
5. Benchmark the entire Java and Rust summary paths, including data preparation, native-call overhead, memory copying and result validation.
6. Keep TCP_NODELAY disabled by default and retain restoration of the previous socket option; evaluate it only with controlled server/platform tests.
7. Keep packet encoding, compression, pooling, flush policy and protocol changes out of the first stage until exact ordering/framing tests and compatibility matrices exist.
8. Preserve Java fallback and the ordinary Minecraft transport when native libraries or optional integrations are unavailable.

## 8. Compatibility capability registry

The next compatibility layer should separate **detection** from **permission to optimize**.

Each adapter should declare:

- mod ID aliases and supported version range;
- the exact capability or hook it needs;
- whether it is client, server or both sides;
- features it overlaps with;
- test status for each supported Minecraft/NeoForge line;
- a safe passive mode and a reason when an optimization is suppressed.

Resolve compatibility per feature. Detecting Krypton should suppress only conflicting network changes, not unrelated diagnostics. Detecting FerriteCore should suppress only duplicate memory transformations that overlap with its proven behavior. Unsupported versions should return a clear state, not trigger best-effort invasive hooks.

## 9. Integration and test gates

Before merging any independent implementation branch into `main`:

1. Fetch the latest `main` and compare it with the feature branch; do not rely on old ahead/behind counts.
2. Review the complete diff and preserve unrelated work from both lines of development.
3. Resolve conflicts explicitly; do not force-update or merge one feature branch into another as part of this proposal.
4. Run Java 21 unit tests on Windows and Linux.
5. Run the applicable NeoForge build matrix and resource/translation validation.
6. Run Rust tests and native build tests only on platforms for which artifacts are actually packaged.
7. Launch a client and a dedicated/integrated server test where the feature applies.
8. Exercise both enabled and disabled paths, missing optional dependencies, unsupported platforms and fallback behavior.
9. Attach benchmark and compatibility evidence to the eventual integration review.

The order for these independent lines should be chosen by the maintainer after reviewing the latest branch diffs. This document does not prescribe a merge order or authorize merging any branch.

## 10. Suggested implementation order

| Order | Work item | Reason |
|---:|---|---|
| 1 | Validate/review Startup Optimizer branch | It already has a focused implementation and tests |
| 2 | Diagnostic Bundle | Implemented in `feature/diagnostic-bundle`; awaiting CI and review |
| 3 | Runtime Health Check | Implemented in `feature/runtime-health-check`; awaiting CI and review |
| 4 | Benchmark Engine | Next measurement foundation for later optimizations |
| 5 | Memory candidate experiment | Proceed only after retained-heap profiling |
| 6 | Network telemetry refinement | Correct metric semantics before deeper pipeline work |
| 7 | Compatibility capability registry | Consolidates per-feature conflict handling |

This ordering is a proposal, not a release schedule. A measured regression or compatibility issue can change priorities.

## References

- [Fiximizer repository](https://github.com/MAKAKAIglitch/Fiximizer)
- [NeoForge 1.21.1 lifecycle events](https://docs.neoforged.net/docs/1.21.1/concepts/events/)
- [NeoForge resource reload listeners](https://docs.neoforged.net/docs/resources/reloadlisteners)
- [FerriteCore technical memory-optimization notes](https://github.com/malte0811/FerriteCore/blob/0cef1f2add1f1329aa6e690e8e292acd625c5c6d/summary.md)
- [ModernFix repository](https://github.com/embeddedt/ModernFix)
- [spark profiler](https://github.com/lucko/spark)

No third-party source code is copied by these proposals. References are for technical research and comparison only.

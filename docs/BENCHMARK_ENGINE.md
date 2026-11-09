# Benchmark Engine

The first Benchmark Engine iteration creates repeatable, local before/after measurements. It measures Fiximizer's existing render-call, client-tick and integrated-server-tick event windows; it does not automate gameplay or claim a universal FPS increase.

## Run protocol

Each run follows one fixed protocol:

1. Select one of the six documented workload scenarios in the dashboard.
2. Keep the selected world, camera, player position, entity population and graphics settings stable where the scenario permits.
3. Warm up for 10 seconds. Samples from this period are excluded.
4. Measure for 30 seconds. The dashboard displays the remaining time and bounded sample counts.
5. The result is saved locally as a versioned JSON document.

A run is marked incomplete when it does not collect at least 30 render-call samples and 10 client-tick samples. Scenarios that explicitly expect an integrated server also require at least 10 integrated-server tick samples. Incomplete runs may be inspected, but cannot become baselines.

## Scenarios

- `MENU_IDLE` — idle on the title screen.
- `STABLE_SCENE` — a stable scene with a fixed camera and position.
- `ENTITY_HEAVY` — a documented scene with many entities.
- `CHUNK_TRAVERSAL` — a repeatable route through loaded or newly generated chunks.
- `RESOURCE_RELOAD` — a user-triggered resource reload.
- `INTEGRATED_SERVER_LOAD` — a controlled single-player workload with server ticking.

The scenarios are instructions for the person running the test; Fiximizer does not move the player, alter a world, spawn entities or trigger resource reloads.

## Metrics

Each timing window uses a bounded ring buffer (8,192 samples by default). Results include sample count, overwritten sample count, mean, standard deviation, minimum, maximum and nearest-rank p50/p95/p99 values.

- **Render-call** is wall-clock time around the client render-frame callback. It is not a direct measurement of GPU execution or displayed-frame cadence.
- **Client tick** is wall-clock time around the client tick callbacks.
- **Integrated-server tick** is wall-clock time around logical-server ticks observed by the client distribution in single-player. Remote dedicated-server internals are not measured.

A run also stores point-in-time runtime snapshots before and after the measured window: heap used/committed/max, available threads, CPU readings when exposed by the JVM, cumulative GC count/time when available, Fiximizer scheduler counters, JFR recording state and already-published GPU telemetry. CPU and GPU values are not time-averaged in this version. Missing values remain unavailable (for example, `-1` in numeric fields); they are not reported as measured zero.

## Environment matching and baselines

The environment manifest records Minecraft, NeoForge, Fiximizer and Java versions; OS and architecture; processor count; maximum JVM heap; render and simulation distance; optimization profile; and a settings fingerprint.

Third-party mod IDs and versions are sorted and hashed. The raw mod inventory is not written to the benchmark result. Player identity, world name/path, server address, chat and packet contents are not collected.

A baseline is stored per scenario and compatibility fingerprint in `logs/fiximizer/benchmarks`. The fingerprint includes the scenario, Minecraft/NeoForge/Java, hardware, third-party modpack and relevant settings. It deliberately excludes the Fiximizer version so a new Fiximizer build can be compared with a prior build when the rest of the environment matches.

Comparisons are rejected when the schema, scenario or compatibility fingerprint differs, or when either run is incomplete. A negative p95 delta means the candidate's p95 duration was lower in that metric; it is not proof of an improvement across every workload.

## Files and retention

- `run-<id>.json` — a saved run.
- `baseline-<scenario>-<fingerprint>.json` — the saved baseline for one compatible scenario/environment.

The repository retains up to 40 most recent run documents. Baselines are retained separately. JSON writes use a temporary file and atomic replacement where the filesystem supports it. The repository API also supports importing a valid run JSON as a baseline and exporting a selected run/baseline document.

All files remain local. There is no automatic upload or telemetry submission.

## Current scope and limitations

This iteration measures event wall-clock durations and before/after runtime counters. It does not yet aggregate JFR allocation or CPU samples into the benchmark result, measure remote-server MSPT/TPS, capture independent network RTT, or automate exact in-world scenarios. Use the same manual test procedure for baseline and candidate runs and repeat runs before drawing conclusions.

# Runtime Health Check

Runtime Health Check is a passive snapshot of Fiximizer-owned subsystems. It is available from the dashboard and is also included in the local Diagnostic Bundle as `health.json`.

## State meanings

| State | Meaning |
|---|---|
| `HEALTHY` | The subsystem is initialized and its published status indicates normal operation. |
| `DISABLED` | The feature is disabled by configuration or the active optimization profile. |
| `DEGRADED` | A capability is missing or an operation fell back to a supported implementation. |
| `UNSUPPORTED` | The current runtime exposes no compatible capability for this optional feature. |
| `FAILED` | Initialization or a required operation failed. The snapshot explains the fallback and whether user action is recommended. |
| `NOT_STARTED` | Lazy initialization or the first sample has not completed. |
| `IDLE` | The feature is available but dormant because its activation conditions are not currently met. |

## Reported subsystems

The snapshot reports Startup, Diagnostics, JFR, Memory Guard, Scheduler, Memory Optimizer, Network Telemetry, the Java/Rust statistics backend, GPU Telemetry, OpenCL, the VRAM cache and optional mod integrations.

Each record contains a stable subsystem ID, state, sanitized reason, fallback description, user-action flag and last observed transition time. Transition times change only when the state or its explanatory fields change.

## Collection and privacy

- Collection reads cached or already-published service state. It does not initialize GPU/OpenCL, open connections, run benchmarks, read logs or perform disk I/O.
- The UI snapshot is cached for one second to avoid repeated status work during rendering.
- Missing metrics remain unavailable; they are never converted into a measured zero.
- Reasons are sanitized and length-bounded. Diagnostic Bundle applies its text redactor again before writing `health.json`.
- No mod inventory, player identity, server address, packet contents or filesystem paths are included.
- A disabled or unsupported optional subsystem does not make unrelated systems unhealthy.

The report describes subsystem state, not a performance guarantee. A `HEALTHY` status does not imply higher FPS or lower memory usage.

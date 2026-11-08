# Fiximizer Network Optimizer

Status: experimental implementation for Minecraft 1.21.1 / NeoForge.

## Implemented in this branch

- Opt-in, client-side Netty traffic observer. It counts inbound/outbound raw `ByteBuf` bytes and frames without retaining or modifying packet data.
- Server-reported player ping sampling once per second with a bounded 64-sample history, p50/p95 and average absolute jitter.
- Cached UI snapshots so statistics aggregation and temporary arrays are not created every rendered frame.
- Optional TCP_NODELAY policy. It is disabled by default, only changes the option when the active channel supports it and currently has it disabled, and restores the previous value on disconnect or when the setting is switched off.
- Detection for Krypton/Krypton Reno/Krypton Foxified, Connectivity, Packet Fixer, Connection Packet Fixer, ViaForge and ViaVersion. When a known networking modifier is present, Fiximizer leaves active socket tuning alone and reports the coexistence policy.
- Java and optional Rust implementations for the local p50/p95/p99 statistics calculation. Auto mode checks that both implementations return identical values, measures the same bounded workload, and selects Rust only when it is at least 10% faster. Any missing native library, ABI mismatch, correctness error or runtime failure falls back to Java.
- Network tab controls in the Fiximizer menu: telemetry toggle, Java/Auto/Rust backend mode, Rust availability toggle, TCP_NODELAY toggle, and a repeatable local backend benchmark.
- Optional native Rust build support in the Linux and Windows x86_64 CI jobs and local builder scripts. A missing Rust toolchain or unsupported platform does not prevent the Java build.

## Current behavior and boundaries

This is not a replacement transport for Minecraft. Normal Minecraft connections continue to use the protocol and transport selected by Minecraft and the server. UDP/QUIC is not enabled.

The Rust backend currently accelerates only Fiximizer's local statistics calculation. It does not parse or rewrite Minecraft packets, compress network traffic, change the wire protocol, or reduce the physical distance to a server. The auto selector may correctly choose Java when the small live statistics workload is too small to offset native-call overhead.

The ping displayed here is the latency value reported by Minecraft for the local player. It is not an independent raw ICMP/UDP RTT test, packet-loss measurement, or server tick measurement. A client-only mod cannot directly lower remote server processing time or the Internet route's physical latency.

TCP_NODELAY is disabled by default. If enabled, Fiximizer changes it only for the active client connection, only when the channel exposes that TCP option, and only when no recognized external networking modifier is present. Its effect is platform- and server-dependent, so no fixed ping reduction is promised.

Telemetry is also disabled by default to keep idle overhead at zero apart from the menu's settings state. When enabled, the observer performs simple counter increments on network buffers and periodically samples the server-reported ping.

## Compatibility policy

| Detected mod family | Fiximizer behavior |
|---|---|
| Krypton, Krypton Reno / Krypton FNP, Krypton Foxified | Keep Fiximizer's active socket tuning passive; observe traffic only |
| Connectivity, Packet Fixer, Connection Packet Fixer | Keep active socket tuning passive; observe traffic only |
| ViaForge, ViaVersion | Keep active socket tuning passive; observe traffic only |
| No known networking modifier | Optional TCP_NODELAY policy may be applied if enabled and supported |

Detection is based on known mod IDs and is a conservative guard, not a guarantee that every fork or future version is detected. The telemetry handler uses its own unique name and forwards every message unchanged.

## Settings

- **Passive network telemetry**: disabled by default.
- **Backend mode**: Auto by default; Java and Rust may be selected manually.
- **Allow Rust native backend**: enabled by default, but Rust is used only when the matching native library is packaged and passes validation.
- **Prefer TCP_NODELAY**: disabled by default.

Native libraries are bundled for Windows x86_64 and Linux x86_64 by CI and by the local builder when Cargo is available. Other systems use Java.

## Not implemented yet

These remain roadmap items, not claims about this implementation:

- Netty packet encoding/decoding or compression changes.
- Buffer pooling/reuse in the live Minecraft pipeline.
- Flush consolidation or packet scheduling changes.
- Packet processing/event-loop latency and queue-depth profiling.
- Automatic retry across DNS/SRV endpoints.
- Protocol-version fallback or protocol translation.
- UDP/QUIC transport negotiation with a server/proxy that explicitly supports it.

These features require more invasive hooks and a dedicated compatibility matrix. The current implementation deliberately leaves the live Minecraft packet protocol and ordering unchanged.

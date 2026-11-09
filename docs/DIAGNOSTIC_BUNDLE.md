# Diagnostic Bundle

Diagnostic Bundle creates a bounded ZIP archive in the local Minecraft game directory:

\`logs/fiximizer/diagnostic-bundles/\`

The dashboard shows the planned archive entries and privacy exclusions before the user confirms export. Compression and disk I/O run on Fiximizer's bounded background scheduler, not on the render, client-tick, integrated-server-tick or Netty event-loop threads.

## Included data

- \`manifest.json\`: schema version, Minecraft/NeoForge/Fiximizer/Java/OS versions, entry names and redaction counters.
- \`system.json\`: Java runtime, OS family/architecture, processor count, heap limit and garbage-collector names.
- \`performance.json\`: heap and CPU samples, current optimization profile, memory-guard state and aggregate Fiximizer scheduler/string-cache counters.
- \`network.json\`: aggregate traffic counters and server-reported latency statistics only.
- `health.json`: sanitized subsystem states, reasons, fallbacks and last observed state transitions.
- \`startup.txt\`: Fiximizer startup timing summary.
- \`errors.log\`: a bounded tail of warning/error lines from the local latest log, when available.
- \`incident.txt\`: the latest bounded stall report, when available.

## Excluded by default

The exporter does not include the installed mod inventory, chat, packet payloads, player UUIDs/names, server addresses, IP addresses, full local filesystem paths or raw JFR recordings. It does not upload any data or make network requests.

Text exports pass through the redactor before they are written. Chat-like log lines are omitted. Health reasons are sanitized before serialization and redacted again during export. The archive has fixed flat entry names, a maximum of eight files, per-entry and total uncompressed-size limits, atomic finalization and bounded retention of the newest five archives.

The redactor is defence in depth, not a promise that arbitrary third-party log text is safe to publish. Users should still review an archive before sharing it. New sections must have an explicit data-minimization and privacy review before being added.

## Schema and compatibility

The archive uses \`schemaVersion: 1\`. Consumers should ignore unknown fields and tolerate optional entries being absent. Missing diagnostics or a missing latest log must not prevent export of the remaining sections.

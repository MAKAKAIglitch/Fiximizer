# Runtime Smoke Tests

The runtime smoke tests launch the actual NeoForge development runtime. They complement compilation and JUnit tests; they do not replace manual gameplay, mod-combination, save-integrity, or performance testing.

## Covered paths

### Dedicated server

The test launches the isolated `runSmokeServer` configuration on Windows and Linux. It prepares a disposable directory under `runs/smoke-server`, accepts the Minecraft EULA only in that test directory, and writes a small local-only flat-world configuration.

The test passes only when:
- the Fiximizer common mod entrypoint logs that it is loading;
- the dedicated server reaches its normal `Done` startup message;
- the server accepts the console `stop` command and exits successfully.

The server binds to loopback only and uses a dedicated test port. It does not connect to a public server.

### Client

The Linux client test launches the isolated `runSmokeClient` configuration under Xvfb. It passes only when:
- the client-side Fiximizer entrypoint registers its dashboard;
- the client reaches the title screen and records 40 stable client ticks;
- the test sends a window-close event and the Gradle runtime exits successfully.

This verifies that the client event subscriber is active and that deferred startup services can be scheduled during an actual client boot.

## Local commands

Dedicated server (Windows or Linux):

```shell
python scripts/runtime_smoke.py server
```

Dedicated client on Linux with Xvfb and xdotool installed:

```shell
xvfb-run -a -s "-screen 0 1280x720x24" python3 scripts/runtime_smoke.py client
```

The Gradle run configurations use separate directories in `runs/`; generated worlds, EULA files, logs, and client settings are not committed.

## Boundaries

A passing smoke test proves that the supported CI runtime can bootstrap Fiximizer and complete the tested startup/shutdown path. It does not certify arbitrary third-party mod combinations, connect to a multiplayer server, prove memory/FPS/network improvements, or replace a manual world-load and gameplay session. Those remain separate acceptance tests.

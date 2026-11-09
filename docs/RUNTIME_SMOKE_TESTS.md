# Runtime Smoke Tests

The runtime smoke tests launch the actual NeoForge development runtime. They complement compilation and JUnit tests; they do not replace manual gameplay, mod-combination, save-integrity, or performance testing.

## Covered paths

### Dedicated server

The test launches the isolated `runSmokeServer` configuration on Windows and Linux. It prepares a disposable directory under `runs/smoke-server`, accepts the Minecraft EULA only in that test directory, and writes a small local-only world configuration with short view and simulation distances.

The test passes only when:
- the Fiximizer common mod entrypoint logs that it is loading;
- the dedicated server reaches its normal `Done` startup message;
- the server accepts the console `stop` command and exits successfully.

The game server binds to loopback only and uses a dedicated test port. A random, one-run RCON password is generated for the test; RCON also binds to loopback and is used only to send the clean shutdown command. It does not connect to a public server.

### Client

The Linux client test launches the isolated `runSmokeClient` configuration under Xvfb with Openbox as a lightweight window manager. The test activates the Minecraft window so focus-dependent client ticks can advance; OpenAL uses its null driver because CI has no physical audio device. It passes only when:
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

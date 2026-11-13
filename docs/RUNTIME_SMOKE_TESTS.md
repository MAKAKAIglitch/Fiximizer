# Runtime Smoke Test: Dedicated Server

This test launches the actual NeoForge dedicated-server development runtime. It complements compilation and JUnit tests; it does not replace manual gameplay or third-party mod compatibility testing.

## What it verifies

- The Fiximizer common mod entrypoint loads.
- The server reaches the normal `Done` startup message.
- The server accepts an authenticated loopback RCON `stop` command and exits cleanly.
- The shared Mob Mixin configuration is loaded during dedicated-server startup. The AI throttle controller keeps the feature at vanilla interval 2 on dedicated servers because its setting is client-only.

The test uses a disposable `runs/smoke-server` directory, a local-only server bind, a dedicated test port, and a random one-run RCON password. The EULA acceptance is written only into that disposable test directory.

## Run locally

Windows or Linux:

```shell
python scripts/runtime_smoke.py server
```

The Gradle run configuration is `runSmokeServer`. Generated world data, EULA, server properties, and logs are not committed.

## Scope

A passing test verifies bootstrap and clean shutdown on the tested OS. It does not prove AI behavior, performance gains, multiplayer client/server command support, or compatibility with arbitrary third-party mixins.

#!/usr/bin/env python3
"""Boot a real NeoForge client or dedicated server and verify Fiximizer startup."""

from __future__ import annotations

import argparse
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANSI_ESCAPE = re.compile(r"\x1B\[[0-?]*[ -/]*[@-~]")


class SmokeFailure(RuntimeError):
    """Raised when the requested game runtime does not reach a healthy state."""


def gradle_command(task: str) -> list[str] | str:
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper), task, "--no-daemon", "--console=plain"]
    if os.name == "nt":
        # Windows batch wrappers must be launched through the command shell.
        return subprocess.list2cmdline(command)
    return command


def prepare_server_directory() -> None:
    run_directory = ROOT / "runs" / "smoke-server"
    run_directory.mkdir(parents=True, exist_ok=True)
    (run_directory / "eula.txt").write_text(
        "# Accepted for this disposable CI smoke-test directory only.\n"
        "eula=true\n",
        encoding="utf-8",
    )
    properties = {
        "allow-flight": "true",
        "allow-nether": "false",
        "broadcast-console-to-ops": "false",
        "enable-command-block": "false",
        "enable-query": "false",
        "enable-rcon": "false",
        "enforce-secure-profile": "false",
        "generate-structures": "false",
        "level-name": "smoke-world",
        "level-seed": "2718281828459045",
        "level-type": "minecraft:flat",
        "max-players": "1",
        "motd": "Fiximizer CI runtime smoke",
        "online-mode": "false",
        "pvp": "false",
        "server-ip": "127.0.0.1",
        "server-port": "25575",
        "simulation-distance": "2",
        "spawn-protection": "0",
        "view-distance": "2",
    }
    (run_directory / "server.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in properties.items()),
        encoding="utf-8",
    )


def start_process(mode: str, log_path: Path) -> tuple[subprocess.Popen[str], queue.Queue[str | None]]:
    task = "runSmokeServer" if mode == "server" else "runSmokeClient"
    command = gradle_command(task)
    print(f"[runtime-smoke] Starting NeoForge {mode}: {task}", flush=True)

    process = subprocess.Popen(
        command,
        cwd=ROOT,
        shell=(os.name == "nt"),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    output_queue: queue.Queue[str | None] = queue.Queue()

    def forward_output() -> None:
        assert process.stdout is not None
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("w", encoding="utf-8") as log_file:
            for line in iter(process.stdout.readline, ""):
                sys.stdout.write(line)
                sys.stdout.flush()
                log_file.write(line)
                log_file.flush()
                output_queue.put(ANSI_ESCAPE.sub("", line.rstrip("\r\n")))
        output_queue.put(None)

    threading.Thread(target=forward_output, name=f"{mode}-smoke-output", daemon=True).start()
    return process, output_queue


def wait_for_markers(
    process: subprocess.Popen[str],
    output_queue: queue.Queue[str | None],
    markers: tuple[str, ...],
    timeout_seconds: int,
) -> set[str]:
    remaining = set(markers)
    deadline = time.monotonic() + timeout_seconds

    while remaining and time.monotonic() < deadline:
        if process.poll() is not None and output_queue.empty():
            break
        try:
            line = output_queue.get(timeout=0.25)
        except queue.Empty:
            continue

        if line is None:
            break
        for marker in tuple(remaining):
            if marker in line:
                remaining.remove(marker)

    if remaining:
        missing = ", ".join(sorted(remaining))
        raise SmokeFailure(f"runtime exited or timed out before these startup markers appeared: {missing}")
    return remaining


def stop_server(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        raise SmokeFailure(f"dedicated server exited unexpectedly with code {process.returncode}")
    assert process.stdin is not None
    process.stdin.write("stop\n")
    process.stdin.flush()

    try:
        return_code = process.wait(timeout=90)
    except subprocess.TimeoutExpired as exc:
        process.kill()
        process.wait(timeout=15)
        raise SmokeFailure("dedicated server did not stop after the stop command") from exc

    if return_code != 0:
        raise SmokeFailure(f"dedicated server returned exit code {return_code}")


def close_client_window() -> None:
    if not os.environ.get("DISPLAY"):
        raise SmokeFailure("client smoke test requires DISPLAY; run it under xvfb-run")
    if shutil.which("xdotool") is None:
        raise SmokeFailure("client smoke test requires xdotool to close the test window")

    deadline = time.monotonic() + 20
    window_id = None
    while time.monotonic() < deadline:
        result = subprocess.run(
            ["xdotool", "search", "--onlyvisible", "--name", "Minecraft"],
            check=False,
            capture_output=True,
            text=True,
        )
        matches = [item.strip() for item in result.stdout.splitlines() if item.strip()]
        if matches:
            window_id = matches[-1]
            break
        time.sleep(0.5)

    if window_id is None:
        raise SmokeFailure("Minecraft reached the startup log marker but no visible client window was found")

    close_result = subprocess.run(
        ["xdotool", "windowclose", window_id],
        check=False,
        capture_output=True,
        text=True,
    )
    if close_result.returncode != 0:
        raise SmokeFailure(f"could not send a close event to the Minecraft window: {close_result.stderr.strip()}")


def run_smoke(mode: str, startup_timeout: int) -> None:
    if mode == "server":
        prepare_server_directory()

    log_path = ROOT / "build" / "runtime-smoke" / f"{mode}.log"
    process, output_queue = start_process(mode, log_path)

    try:
        if mode == "server":
            markers = (
                "Fiximizer is loading for Minecraft 1.21.1 / NeoForge.",
                "Done (",
                "For help, type",
            )
        else:
            markers = (
                "Fiximizer menu registered in the mod list.",
                "Deferred startup services scheduled after 40 stable client ticks.",
            )

        wait_for_markers(process, output_queue, markers, startup_timeout)

        if mode == "server":
            print("[runtime-smoke] Dedicated server reached Done; requesting clean shutdown.", flush=True)
            stop_server(process)
        else:
            print("[runtime-smoke] Client reached the title-screen stable-tick milestone; closing window.", flush=True)
            close_client_window()
            try:
                return_code = process.wait(timeout=90)
            except subprocess.TimeoutExpired as exc:
                process.kill()
                process.wait(timeout=15)
                raise SmokeFailure("Minecraft client did not exit after the window close event") from exc
            if return_code != 0:
                raise SmokeFailure(f"Minecraft client returned exit code {return_code}")

    except Exception:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=15)
        raise

    print(f"[runtime-smoke] PASS: NeoForge {mode} started and exited cleanly.", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("server", "client"))
    parser.add_argument("--startup-timeout", type=int, default=360)
    args = parser.parse_args()

    try:
        run_smoke(args.mode, args.startup_timeout)
    except (OSError, SmokeFailure, subprocess.SubprocessError) as exc:
        print(f"[runtime-smoke] FAIL: {exc}", file=sys.stderr, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Boot a real NeoForge client or dedicated server and verify Fiximizer startup."""

from __future__ import annotations

import argparse
import os
import queue
import re
import secrets
import shutil
import socket
import struct
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


def prepare_server_directory() -> str:
    run_directory = ROOT / "runs" / "smoke-server"
    rcon_password = secrets.token_urlsafe(24)
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
        "enable-rcon": "true",
        "enforce-secure-profile": "false",
        "generate-structures": "false",
        "level-name": "smoke-world",
        "level-seed": "2718281828459045",
        "level-type": "minecraft:normal",
        "max-players": "1",
        "motd": "Fiximizer CI runtime smoke",
        "online-mode": "false",
        "pvp": "false",
        "rcon.password": rcon_password,
        "rcon.port": "25576",
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
    return rcon_password


def prepare_client_directory() -> None:
    run_directory = ROOT / "runs" / "smoke-client"
    run_directory.mkdir(parents=True, exist_ok=True)
    options_file = run_directory / "options.txt"
    existing = options_file.read_text(encoding="utf-8") if options_file.exists() else ""
    options = [line for line in existing.splitlines() if not line.startswith("pauseOnLostFocus:")]
    options.append("pauseOnLostFocus:false")
    options_file.write_text("\n".join(options) + "\n", encoding="utf-8")


def start_process(mode: str, log_path: Path) -> tuple[subprocess.Popen[str], queue.Queue[str | None]]:
    task = "runSmokeServer" if mode == "server" else "runSmokeClient"
    command = gradle_command(task)
    print(f"[runtime-smoke] Starting NeoForge {mode}: {task}", flush=True)

    process = subprocess.Popen(
        command,
        cwd=ROOT,
        shell=(os.name == "nt"),
        stdin=subprocess.DEVNULL,
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


def receive_exact(connection: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        chunk = connection.recv(size - len(chunks))
        if not chunk:
            raise SmokeFailure("RCON connection closed before the full response arrived")
        chunks.extend(chunk)
    return bytes(chunks)


def send_rcon_packet(connection: socket.socket, request_id: int, packet_type: int, payload: str) -> None:
    body = struct.pack("<ii", request_id, packet_type) + payload.encode("utf-8") + b"\x00\x00"
    connection.sendall(struct.pack("<i", len(body)) + body)


def read_rcon_packet(connection: socket.socket) -> tuple[int, int, str]:
    packet_size = struct.unpack("<i", receive_exact(connection, 4))[0]
    if packet_size < 10 or packet_size > 1_048_576:
        raise SmokeFailure(f"invalid RCON packet size: {packet_size}")
    body = receive_exact(connection, packet_size)
    request_id, packet_type = struct.unpack_from("<ii", body)
    payload = body[8:-2].decode("utf-8", errors="replace")
    return request_id, packet_type, payload


def stop_server(process: subprocess.Popen[str], rcon_password: str) -> None:
    if process.poll() is not None:
        raise SmokeFailure(f"dedicated server exited unexpectedly with code {process.returncode}")

    deadline = time.monotonic() + 20
    last_error: Exception | None = None
    while time.monotonic() < deadline and process.poll() is None:
        try:
            with socket.create_connection(("127.0.0.1", 25576), timeout=3) as connection:
                connection.settimeout(5)
                send_rcon_packet(connection, 1, 3, rcon_password)
                response_id, _, _ = read_rcon_packet(connection)
                if response_id != 1:
                    raise SmokeFailure("dedicated server rejected the temporary RCON credentials")
                send_rcon_packet(connection, 2, 2, "stop")
                last_error = None
                break
        except (OSError, SmokeFailure) as exc:
            last_error = exc
            time.sleep(0.5)
    else:
        raise SmokeFailure(f"could not authenticate to the loopback RCON endpoint: {last_error}")

    try:
        return_code = process.wait(timeout=90)
    except subprocess.TimeoutExpired as exc:
        process.kill()
        process.wait(timeout=15)
        raise SmokeFailure("dedicated server did not stop after the authenticated RCON stop command") from exc

    if return_code != 0:
        raise SmokeFailure(f"dedicated server returned exit code {return_code}")


def find_client_window(timeout_seconds: int = 20) -> str:
    if not os.environ.get("DISPLAY"):
        raise SmokeFailure("client smoke test requires DISPLAY; run it under xvfb-run")
    if shutil.which("xdotool") is None:
        raise SmokeFailure("client smoke test requires xdotool")

    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        result = subprocess.run(
            ["xdotool", "search", "--onlyvisible", "--name", "Minecraft"],
            check=False,
            capture_output=True,
            text=True,
        )
        matches = [item.strip() for item in result.stdout.splitlines() if item.strip()]
        if matches:
            return matches[-1]
        time.sleep(0.5)

    raise SmokeFailure("Minecraft logged its client entrypoint but no visible client window was found")


def activate_client_window(window_id: str) -> None:
    # Xvfb alone does not provide a window manager or keyboard focus. CI starts
    # Openbox so Minecraft's normal focus-dependent client ticks can advance.
    result = subprocess.run(
        ["xdotool", "windowactivate", "--sync", window_id],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SmokeFailure(f"could not activate the Minecraft window: {result.stderr.strip()}")

    focus = subprocess.run(
        ["xdotool", "windowfocus", "--sync", window_id],
        check=False,
        capture_output=True,
        text=True,
    )
    if focus.returncode != 0:
        raise SmokeFailure(f"could not focus the Minecraft window: {focus.stderr.strip()}")


def close_client_window(window_id: str) -> None:
    active_window = subprocess.run(
        ["xdotool", "getactivewindow"],
        check=False,
        capture_output=True,
        text=True,
    )
    if active_window.returncode != 0 or active_window.stdout.strip() != window_id:
        raise SmokeFailure("Minecraft is not the active window; refusing to send the close shortcut elsewhere")

    # Ask the window manager to close Minecraft normally. xdotool windowclose
    # can destroy the X11 drawable immediately, racing the render thread and
    # producing BadDrawable during GLFW shutdown on headless CI.
    close_result = subprocess.run(
        ["xdotool", "key", "--clearmodifiers", "alt+F4"],
        check=False,
        capture_output=True,
        text=True,
    )
    if close_result.returncode != 0:
        raise SmokeFailure(f"could not send Alt+F4 to the Minecraft window: {close_result.stderr.strip()}")


def run_smoke(mode: str, startup_timeout: int) -> None:
    rcon_password = prepare_server_directory() if mode == "server" else None
    if mode == "client":
        prepare_client_directory()

    log_path = ROOT / "build" / "runtime-smoke" / f"{mode}.log"
    process, output_queue = start_process(mode, log_path)

    try:
        if mode == "server":
            markers = (
                "Fiximizer is loading for Minecraft 1.21.1 / NeoForge.",
                "Done (",
                "For help, type",
            )
            wait_for_markers(process, output_queue, markers, startup_timeout)
            print("[runtime-smoke] Dedicated server reached Done; requesting clean shutdown.", flush=True)
            assert rcon_password is not None
            stop_server(process, rcon_password)
        else:
            wait_for_markers(
                process,
                output_queue,
                ("Fiximizer menu registered in the mod list.",),
                startup_timeout,
            )
            window_id = find_client_window()
            activate_client_window(window_id)
            print("[runtime-smoke] Client window focused; waiting for 40 stable client ticks.", flush=True)
            wait_for_markers(
                process,
                output_queue,
                ("Deferred startup services scheduled after 40 stable client ticks.",),
                90,
            )
            print("[runtime-smoke] Client reached the title-screen stable-tick milestone; closing window.", flush=True)
            close_client_window(window_id)
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

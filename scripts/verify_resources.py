#!/usr/bin/env python3
"""Validate bundled locale JSON and placeholder parity without third-party packages."""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
LANG_DIR = ROOT / "src/main/resources/assets/fiximizer/lang"
LOCALES = ("en_us.json", "ru_ru.json")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sd]")


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_locale(path: Path) -> dict[str, str]:
    raw = path.read_bytes()
    if b"\x00" in raw:
        raise ValueError(f"{path.relative_to(ROOT)} contains a NUL byte")

    parsed = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicate_keys)
    if not isinstance(parsed, dict):
        raise ValueError(f"{path.relative_to(ROOT)} must contain a JSON object")

    for key, value in parsed.items():
        if not isinstance(key, str) or not isinstance(value, str):
            raise ValueError(f"{path.relative_to(ROOT)} must contain only string keys and values")
        if "\x00" in value:
            raise ValueError(f"{path.relative_to(ROOT)} value for {key!r} contains NUL")

    return parsed


def main() -> int:
    locales: dict[str, dict[str, str]] = {}
    for locale in LOCALES:
        path = LANG_DIR / locale
        try:
            locales[locale] = load_locale(path)
        except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
            print(f"[ERROR] {exc}", file=sys.stderr)
            return 1

    english = locales["en_us.json"]
    russian = locales["ru_ru.json"]
    en_keys = set(english)
    ru_keys = set(russian)

    missing_ru = sorted(en_keys - ru_keys)
    missing_en = sorted(ru_keys - en_keys)
    if missing_ru or missing_en:
        if missing_ru:
            print("[ERROR] Missing Russian translations: " + ", ".join(missing_ru), file=sys.stderr)
        if missing_en:
            print("[ERROR] Missing English translations: " + ", ".join(missing_en), file=sys.stderr)
        return 1

    mismatches = []
    for key in sorted(en_keys):
        en_placeholders = Counter(PLACEHOLDER.findall(english[key]))
        ru_placeholders = Counter(PLACEHOLDER.findall(russian[key]))
        if en_placeholders != ru_placeholders:
            mismatches.append(key)

    if mismatches:
        print("[ERROR] Translation placeholder mismatch: " + ", ".join(mismatches), file=sys.stderr)
        return 1

    java_root = ROOT / "src/main/java"
    null_files = [
        path.relative_to(ROOT).as_posix()
        for path in java_root.rglob("*.java")
        if b"\x00" in path.read_bytes()
    ]
    if null_files:
        print("[ERROR] Java source contains NUL bytes: " + ", ".join(null_files), file=sys.stderr)
        return 1

    print(f"[ OK ] Locale JSON valid: {len(english)} English / {len(russian)} Russian keys")
    print("[ OK ] Locale keys and printf placeholders match")
    print("[ OK ] No NUL bytes in Java source or locale JSON")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

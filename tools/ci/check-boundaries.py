# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Enforces Heartline architectural module and layer boundaries on tracked Kotlin sources."""

from __future__ import annotations

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

DATA_PREFIXES = (
    "phone/src/main/kotlin/com/heartline/phone/data/",
    "wear/src/main/kotlin/com/heartline/wear/data/",
)
SDK_PREFIX = "wear/src/main/kotlin/com/heartline/wear/sensor/sdk/"
UI_ROOTS = {
    "phone/src/main/kotlin/com/heartline/phone/di/PhoneModule.kt",
    "phone/src/main/kotlin/com/heartline/phone/MainActivity.kt",
    "wear/src/main/kotlin/com/heartline/wear/di/WearModule.kt",
    "wear/src/main/kotlin/com/heartline/wear/MainActivity.kt",
}
UI_PREFIXES = (
    "phone/src/main/kotlin/com/heartline/phone/ui/",
    "wear/src/main/kotlin/com/heartline/wear/ui/",
)
MAIN_ROOTS = ("shared/src/main/", "datalayer/src/main/", "phone/src/main/", "wear/src/main/")

ROOM_IMPORT_RE = re.compile(r"^\s*import\s+androidx\.room\b")
ROOM_ANNO_RE = re.compile(r"@(?:Entity|Dao|Database)\b")
ROOM_SYMBOL_RE = re.compile(r"\b[A-Z]\w*(?:Entity|Dao)\b")
SAMSUNG_RE = re.compile(r"\bcom\.samsung\.")
SDK_GATEWAY_RE = re.compile(r"\bSdkSensorGateway\b")
SDK_GATEWAY_CAST_RE = re.compile(r"\bas\??\s+SdkSensorGateway\b")
KOIN_INJECT_RE = re.compile(r"\bkoinInject\b")
TEST_DOUBLE_RE = re.compile(
    r"\b(?:InMemoryTransport|SyntheticSession|BpExportEvaluation|EcgExportEvaluation|WearSample)\b"
)
UI_IMPORT_RE = re.compile(r"^\s*import\s+com\.heartline\.(?:phone|wear)\.ui\.")
PHONE_IMPORT_RE = re.compile(r"^\s*import\s+com\.heartline\.phone\.")
WEAR_IMPORT_RE = re.compile(r"^\s*import\s+com\.heartline\.wear\.")
DATALAYER_DIAG_RE = re.compile(r"\bcom\.heartline\.datalayer\.diag\b")


def strip_comments_and_strings(src: str) -> str:
    """Replaces Kotlin comments and string literals with spaces while preserving line breaks."""
    out: list[str] = []
    i = 0
    n = len(src)
    block_depth = 0
    while i < n:
        ch = src[i]
        nxt = src[i : i + 2]
        if block_depth > 0:
            if nxt == "/*":
                block_depth += 1
                out.append("  ")
                i += 2
            elif nxt == "*/":
                block_depth -= 1
                out.append("  ")
                i += 2
            else:
                out.append("\n" if ch == "\n" else " ")
                i += 1
        elif nxt == "/*":
            block_depth = 1
            out.append("  ")
            i += 2
        elif nxt == "//":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
        elif ch == "'":
            out.append("'")
            i += 1
            if i < n and src[i] == "\\":
                out.append(src[i : i + 2])
                i += 2
            while i < n and src[i] not in ("'", "\n"):
                out.append(src[i])
                i += 1
            if i < n and src[i] == "'":
                out.append("'")
                i += 1
        elif src[i : i + 3] == '"""':
            out.append("   ")
            i += 3
            while i < n and src[i : i + 3] != '"""':
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            if i < n:
                out.append("   ")
                i += 3
        elif ch == '"':
            out.append(" ")
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\" and i + 1 < n:
                    out.append("\n" if src[i + 1] == "\n" else "  ")
                    i += 2
                else:
                    out.append("\n" if src[i] == "\n" else " ")
                    i += 1
            if i < n:
                out.append(" ")
                i += 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def check_file(rel: str, code_lines: list[str]) -> list[str]:
    """Checks all boundary rules applicable to a tracked Kotlin file."""
    errors: list[str] = []
    is_main = rel.startswith(MAIN_ROOTS)

    for lineno, line in enumerate(code_lines, 1):
        if DATALAYER_DIAG_RE.search(line):
            errors.append(f"{rel}:{lineno}: removed package com.heartline.datalayer.diag referenced")

        if not is_main:
            continue

        if not rel.startswith(DATA_PREFIXES) and (
            ROOM_IMPORT_RE.search(line) or ROOM_ANNO_RE.search(line) or ROOM_SYMBOL_RE.search(line)
        ):
            errors.append(f"{rel}:{lineno}: Room annotation or entity/DAO outside */data/")

        if not rel.startswith(SDK_PREFIX) and SAMSUNG_RE.search(line):
            errors.append(f"{rel}:{lineno}: com.samsung.* usage outside wear sensor/sdk/")

        if SDK_GATEWAY_CAST_RE.search(line) or (
            rel != "wear/src/main/kotlin/com/heartline/wear/di/WearModule.kt"
            and not rel.startswith(SDK_PREFIX)
            and SDK_GATEWAY_RE.search(line)
        ):
            errors.append(f"{rel}:{lineno}: SdkSensorGateway leak outside WearModule/sdk/")

        if KOIN_INJECT_RE.search(line):
            errors.append(f"{rel}:{lineno}: koinInject in main source (inject via constructor/ViewModel)")

        if TEST_DOUBLE_RE.search(line):
            errors.append(f"{rel}:{lineno}: test double or offline evaluator referenced in main source")

        if (
            rel not in UI_ROOTS
            and not rel.startswith(UI_PREFIXES)
            and UI_IMPORT_RE.search(line)
        ):
            errors.append(f"{rel}:{lineno}: upward UI import outside */ui/ or composition roots")

        if not rel.startswith("phone/src/main/") and PHONE_IMPORT_RE.search(line):
            errors.append(f"{rel}:{lineno}: cross-module import of com.heartline.phone")

        if not rel.startswith("wear/src/main/") and WEAR_IMPORT_RE.search(line):
            errors.append(f"{rel}:{lineno}: cross-module import of com.heartline.wear")

    return errors


def main() -> int:
    tracked = subprocess.check_output(
        ["git", "-C", str(ROOT), "ls-files", "--", "*.kt"], text=True
    ).splitlines()
    bad: list[str] = []
    for raw in tracked:
        rel = raw.replace("\\", "/")
        path = ROOT / rel
        if not path.is_file():
            continue
        code = strip_comments_and_strings(path.read_text(encoding="utf-8"))
        bad.extend(check_file(rel, code.splitlines()))
    if bad:
        print("Architectural boundary violations detected:")
        print("\n".join(bad))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

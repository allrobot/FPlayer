#!/usr/bin/env python3
"""Fail-closed hygiene checks for a source-controlled release tree.

The scanner reports categories and SHA-256 digests only.  It never prints the
matched text or the path of a file that may contain a secret or media name.
Tracked text is scanned through Git's inventory; explicitly selected result
files can be added with ``--allow``/``--include`` after they are generated.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
from collections import namedtuple
from pathlib import Path
from typing import Iterable, Sequence


class HygieneScanError(ValueError):
    """Raised when the scanner cannot validate its requested input tree."""


HygieneFinding = namedtuple("HygieneFinding", ("category", "digest"))
"""A sanitized finding; raw text and file names are intentionally absent."""


# Build caches and isolated execution ledgers are not release-tree inputs.  The
# rules are explicit so a newly introduced cache cannot silently hide an APK.
SKIP_DIRECTORY_NAMES = {
    ".git",
    ".gradle",
    ".idea",
    ".cxx",
    ".superpowers",
    "__pycache__",
    "build",
    "jniLibs",
}
SKIP_DIRECTORY_PREFIXES = ("out-", "work-")
BINARY_SUFFIXES = {
    ".aar",
    ".aab",
    ".apk",
    ".dll",
    ".dylib",
    ".exe",
    ".native",
    ".so",
}
KEY_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem"}
TEMPORARY_SUFFIXES = {".new", ".swp", ".tmp"}

_CREDENTIAL_LITERAL = re.compile(
    r"(?i)\b(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key)"
    r"\b\s*[:=]\s*(?:\"[^\"\r\n]{4,}\"|'[^'\r\n]{4,}')"
)
_CREDENTIAL_ASSIGNMENT = re.compile(
    r"(?i)\b(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key)"
    r"\b\s*=\s*['\"](?:[^'\"]{4,})['\"]"
)
_PRIVATE_KEY = re.compile(
    r"-----BEGIN(?: RSA| EC| OPENSSH| DSA)? PRIVATE KEY-----"
    r"|-----BEGIN [A-Z0-9 ]+ PRIVATE KEY-----"
)
_WINDOWS_ABSOLUTE = re.compile(r"(?i)(?<![A-Za-z0-9])[A-Z]:[\\/]")
_UNC_ABSOLUTE = re.compile(r"(?<![A-Za-z0-9])\\\\[A-Za-z0-9_.-]+\\[A-Za-z0-9_.-]+")
_POSIX_ABSOLUTE = re.compile(
    r"(?<![A-Za-z0-9])/(?:Users|home|mnt|private|storage|sdcard|var|root|opt|srv|etc)/"
)
_IPV4 = re.compile(
    r"(?<![A-Za-z0-9.])(?:\d{1,3}\.){3}\d{1,3}(?![A-Za-z0-9.])(?=\D|$)"
)
_MAC = re.compile(r"(?i)(?<![A-Za-z0-9])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![A-Za-z0-9])")
_SAFE_MACS = {"AA:BB:CC:DD:EE:FF"}
_SERIAL_PORT = re.compile(
    r"(?i)(?<![A-Za-z0-9])COM\d{1,3}(?![A-Za-z0-9])"
    r"|(?<![A-Za-z0-9])/dev/(?:tty|cu)[A-Za-z0-9._-]+"
)
_MEDIA_LITERAL = re.compile(
    r"(?P<quote>[\"'`])(?P<name>[A-Za-z][A-Za-z0-9 _().-]{1,180}\."
    r"(?:mp4|mkv|avi|mov|webm|funscript))(?P=quote)",
    re.IGNORECASE,
)
_SAFE_MEDIA_NAME = re.compile(
    r"(?i)^(?:clip|video|new-video|old|new|synthetic|t31-synthetic|media|sample|fixture|test|other)"
    r"(?:[._-][a-z0-9]+)*\.(?:mp4|mkv|avi|mov|webm|funscript)$"
)
_SAFE_MEDIA_STEM = re.compile(
    r"(?i)^(?:clip|video|new-video|old|new|synthetic|media|sample|fixture|test)"
    r"(?:[._-][a-z0-9]+)*$"
)


def _is_safe_ipv4(value: str) -> bool:
    """Loopback and the unspecified address are local test values, not endpoints."""

    octets = value.split(".")
    if len(octets) != 4 or any(int(item) > 255 for item in octets):
        return True
    return octets[0] == "127" or value == "0.0.0.0"


def _relative_file(root: Path, value: str) -> tuple[Path, str]:
    if not isinstance(value, str) or not value.strip():
        raise HygieneScanError("selected scanner path is empty")
    candidate_value = value.replace("\\", "/")
    if candidate_value.startswith("/") or re.match(r"^[A-Za-z]:", candidate_value):
        raise HygieneScanError("selected scanner paths must be repository-relative")
    root = root.resolve()
    candidate = (root / Path(candidate_value)).resolve()
    try:
        relative = candidate.relative_to(root)
    except ValueError as error:
        raise HygieneScanError("selected scanner path escapes the repository") from error
    if not candidate.is_file():
        raise HygieneScanError("selected scanner path is missing")
    return candidate, relative.as_posix()


def _git_tracked(root: Path) -> set[str]:
    try:
        result = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z"],
            check=True,
            capture_output=True,
        )
    except (FileNotFoundError, subprocess.CalledProcessError) as error:
        raise HygieneScanError("Git tracked-file inventory is unavailable") from error
    return {
        item.decode("utf-8")
        for item in result.stdout.split(b"\0")
        if item
    }


def _skip_directory(name: str) -> bool:
    return name in SKIP_DIRECTORY_NAMES or name.startswith(SKIP_DIRECTORY_PREFIXES)


def _walk_files(root: Path) -> Iterable[tuple[Path, str]]:
    for directory, names, files in os.walk(root):
        names[:] = sorted(name for name in names if not _skip_directory(name))
        for name in sorted(files):
            path = Path(directory) / name
            relative = path.relative_to(root).as_posix()
            yield path, relative


def _read_text(path: Path) -> tuple[str, bytes] | None:
    try:
        raw = path.read_bytes()
    except OSError:
        return None
    if b"\0" in raw:
        return None
    try:
        return raw.decode("utf-8"), raw
    except UnicodeDecodeError:
        return None


def _has_media_leak(text: str) -> bool:
    for match in _MEDIA_LITERAL.finditer(text):
        if not _SAFE_MEDIA_NAME.fullmatch(match.group("name")):
            return True
    return False


def _text_categories(text: str) -> set[str]:
    categories: set[str] = set()
    if _CREDENTIAL_LITERAL.search(text) or _CREDENTIAL_ASSIGNMENT.search(text):
        categories.add("credential")
    if _PRIVATE_KEY.search(text):
        categories.add("private-key")
    if _WINDOWS_ABSOLUTE.search(text) or _UNC_ABSOLUTE.search(text) or _POSIX_ABSOLUTE.search(text):
        categories.add("absolute-path")
    numeric_addresses = []
    for match in _IPV4.finditer(text):
        prefix = text[max(0, match.start() - 24) : match.start()]
        if re.search(r"(?i)version\s*=\s*[\"']?$", prefix):
            continue
        numeric_addresses.append(match.group(0))
    macs = [value.upper() for value in _MAC.findall(text) if value.upper() not in _SAFE_MACS]
    if any(not _is_safe_ipv4(value) for value in numeric_addresses) or macs:
        categories.add("device-address")
    if _SERIAL_PORT.search(text):
        categories.add("serial-port")
    if _has_media_leak(text):
        categories.add("media-filename")
    return categories


def _digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _artifact_categories(path: Path, relative: str, tracked: set[str]) -> set[str]:
    lower_name = path.name.lower()
    suffix = path.suffix.lower()
    categories: set[str] = set()
    if suffix in TEMPORARY_SUFFIXES or ".tmp." in lower_name:
        categories.add("temporary-file")
    if suffix in KEY_SUFFIXES:
        categories.add("key-material")
    if suffix in BINARY_SUFFIXES:
        categories.add("binary-artifact" if relative in tracked else "untracked-binary")
    return categories


def scan_release_hygiene(
    root: Path,
    *,
    allow: Sequence[str] = (),
    include: Sequence[str] = (),
) -> list[HygieneFinding]:
    """Return sanitized findings for the tracked release tree.

    ``allow`` and ``include`` are intentionally aliases: they add selected,
    repository-relative generated result files to the text scan.  They do not
    suppress a finding in those files.
    """

    root = root.resolve()
    if not root.is_dir():
        raise HygieneScanError("scanner root is not a directory")
    tracked = _git_tracked(root)
    selected: dict[str, Path] = {}
    for value in (*allow, *include):
        path, relative = _relative_file(root, value)
        selected[relative] = path

    text_paths: dict[str, Path] = {}
    for relative in tracked:
        path = root / Path(relative)
        if path.is_file():
            text_paths[relative] = path
    text_paths.update(selected)

    findings: list[HygieneFinding] = []
    for relative, path in sorted(text_paths.items()):
        loaded = _read_text(path)
        if loaded is None:
            continue
        text, raw = loaded
        digest = _digest(raw)
        findings.extend(
            HygieneFinding(category=category, digest=digest)
            for category in sorted(_text_categories(text))
        )

    for path, relative in _walk_files(root):
        categories = _artifact_categories(path, relative, tracked)
        if not categories:
            continue
        try:
            raw = path.read_bytes()
        except OSError:
            continue
        digest = _digest(raw)
        findings.extend(
            HygieneFinding(category=category, digest=digest)
            for category in sorted(categories)
        )

    return sorted(findings, key=lambda finding: (finding.category, finding.digest))


def format_diagnostics(findings: Sequence[HygieneFinding]) -> str:
    """Render category counts and digests without paths or matched contents."""

    if not findings:
        return "PASS: release hygiene scan found no forbidden literals or artifacts"
    grouped: dict[str, set[str]] = {}
    for finding in findings:
        grouped.setdefault(finding.category, set()).add(finding.digest)
    lines = [f"{category}: {len(digests)} finding(s)" for category, digests in sorted(grouped.items())]
    return "FAIL: " + "; ".join(lines)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--allow", action="append", default=[], metavar="PATH")
    parser.add_argument("--include", action="append", default=[], metavar="PATH")
    args = parser.parse_args(argv)
    try:
        findings = scan_release_hygiene(args.root, allow=args.allow, include=args.include)
    except HygieneScanError as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2
    print(format_diagnostics(findings))
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())

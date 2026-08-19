#!/usr/bin/env python3
"""Fail-closed checks for the inputs required by a T32 release build.

This verifier intentionally does not resolve dependencies or create evidence.  It
only checks that the evidence produced by those operations is present, internally
consistent, and free of the common dynamic-version/ignored-artifact shortcuts.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable
from urllib.parse import urlparse


DYNAMIC_VERSION = re.compile(
    r"(?:^|[=:\"'])\s*(?:latest(?:\.[a-z]+)?|[+*]|[0-9]+(?:\.[0-9]+)*\+|.*\+|.*SNAPSHOT)",
    re.IGNORECASE,
)
ALLOWED_REPOSITORY_HOSTS = {
    "repo1.maven.org",
    "dl.google.com",
    "plugins.gradle.org",
    "jitpack.io",
    "maven.aliyun.com",
}
REPOSITORY_URL = re.compile(r"maven\s*\(\s*[\"'](https?://[^\"']+)[\"']\s*\)")


def _read_json(path: Path, label: str, failures: list[str]) -> dict:
    if not path.is_file():
        failures.append(f"{label} is missing: {path.as_posix()}")
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        failures.append(f"{label} is not valid JSON: {error}")
        return {}
    if not isinstance(value, dict):
        failures.append(f"{label} must contain a JSON object")
        return {}
    return value


def _resolve(root: Path, path: Path) -> Path:
    return path if path.is_absolute() else root / path


def _check_dynamic_versions(root: Path, failures: list[str]) -> None:
    versions_file = root / "gradle" / "libs.versions.toml"
    if not versions_file.is_file():
        failures.append("gradle/libs.versions.toml is missing")
        return
    for line_number, line in enumerate(versions_file.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.split("#", 1)[0]
        if DYNAMIC_VERSION.search(stripped):
            failures.append(f"dynamic dependency version at libs.versions.toml:{line_number}")


def _check_repositories(root: Path, failures: list[str]) -> None:
    settings_file = root / "settings.gradle.kts"
    if not settings_file.is_file():
        failures.append("settings.gradle.kts is missing")
        return
    contents = settings_file.read_text(encoding="utf-8")
    for url in REPOSITORY_URL.findall(contents):
        host = (urlparse(url).hostname or "").lower()
        if host not in ALLOWED_REPOSITORY_HOSTS:
            failures.append(f"unapproved repository host: {host or '<invalid>'}")


def _check_verification_metadata(path: Path, failures: list[str]) -> None:
    if not path.is_file():
        failures.append(f"Gradle verification metadata is missing: {path.as_posix()}")
        return
    try:
        root = ET.fromstring(path.read_text(encoding="utf-8"))
    except (OSError, ET.ParseError) as error:
        failures.append(f"Gradle verification metadata is invalid XML: {error}")
        return
    local_name = lambda tag: tag.rsplit("}", 1)[-1]
    components = [element for element in root.iter() if local_name(element.tag) == "component"]
    if not components:
        failures.append("Gradle verification metadata has no verified components")
    for element in root.iter():
        name = local_name(element.tag)
        if name in {"ignored-artifacts", "ignored-components", "ignored-keys"}:
            failures.append(f"Gradle verification metadata contains forbidden {name} rule")
        origin = element.attrib.get("origin", "")
        if origin and origin.startswith("file:"):
            failures.append("Gradle verification metadata contains a local file origin")


def _check_matrix(root: Path, matrix: dict, failures: list[str]) -> None:
    lock_path = root / "native-build" / "sources.lock.json"
    lock = _read_json(lock_path, "native source lock", failures)
    lock_id = lock.get("lock_id")
    if matrix.get("schema_version") != 1:
        failures.append("license matrix schema_version must be 1")
    if matrix.get("lock_id") != lock_id:
        failures.append("license matrix lock_id does not match native source lock")
    status = matrix.get("status")
    if status != "PASS":
        failures.append(f"license matrix status is {status!r}; complete evidence is required")
    entries = matrix.get("entries")
    if not isinstance(entries, list) or not entries:
        failures.append("license matrix has no dependency entries")
    required = matrix.get("required_evidence", [])
    if required and any(not (_resolve(root, Path(item)).is_file()) for item in required):
        failures.append("license matrix references missing required evidence")


def _check_toolchain(root: Path, manifest: dict, failures: list[str]) -> None:
    lock = _read_json(root / "native-build" / "sources.lock.json", "native source lock", failures)
    target = lock.get("target", {})
    if not manifest.get("ndk_package"):
        failures.append("toolchain manifest is missing ndk_package")
    elif target.get("ndk_package") and manifest["ndk_package"] != target["ndk_package"]:
        failures.append("toolchain ndk_package does not match native lock")
    for field in ("clang", "lld", "meson", "ninja", "python"):
        if not manifest.get(field):
            failures.append(f"toolchain manifest is missing {field}")
    if target.get("meson_version") and manifest.get("meson") != target["meson_version"]:
        failures.append("toolchain meson does not match native lock")


def _check_native_evidence(root: Path, failures: list[str]) -> None:
    evidence = root / "native-build" / "out-arm64" / "evidence"
    required = (
        "source-manifest.json",
        "toolchain-manifest.json",
        "build-options.json",
        "elf-dependencies.json",
        "checksums.sha256",
        "reproducibility.sha256",
    )
    if not evidence.is_dir():
        failures.append("native evidence directory is missing")
        return
    for name in required:
        if not (evidence / name).is_file():
            failures.append(f"native evidence is missing {name}")
    licenses = evidence / "licenses"
    if not licenses.is_dir() or not any(licenses.rglob("*")):
        failures.append("native evidence licenses directory is empty")


def verify_release_inputs(
    root: Path,
    verification_metadata: Path,
    matrix: Path,
    toolchain_manifest: Path,
) -> list[str]:
    """Return sorted input failures; an empty list means the inputs are complete."""

    root = root.resolve()
    failures: list[str] = []
    verification_path = _resolve(root, verification_metadata)
    matrix_path = _resolve(root, matrix)
    toolchain_path = _resolve(root, toolchain_manifest)

    _check_verification_metadata(verification_path, failures)
    matrix_value = _read_json(matrix_path, "license matrix", failures)
    toolchain_value = _read_json(toolchain_path, "toolchain manifest", failures)
    _check_matrix(root, matrix_value, failures)
    _check_toolchain(root, toolchain_value, failures)
    _check_native_evidence(root, failures)
    _check_dynamic_versions(root, failures)
    _check_repositories(root, failures)

    # Keep output deterministic and avoid leaking local paths beyond the supplied
    # repository-relative labels.
    return sorted(dict.fromkeys(failures))


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--verification", type=Path, default=Path("gradle/verification-metadata.xml"))
    parser.add_argument("--matrix", type=Path, default=Path("docs/compliance/license-matrix.json"))
    parser.add_argument(
        "--toolchain",
        type=Path,
        default=Path("native-build/out-arm64/evidence/toolchain-manifest.json"),
    )
    args = parser.parse_args(argv)
    failures = verify_release_inputs(args.root, args.verification, args.matrix, args.toolchain)
    if failures:
        for failure in failures:
            print(f"BLOCKED: {failure}", file=sys.stderr)
        return 1
    print("OK: release input evidence is complete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

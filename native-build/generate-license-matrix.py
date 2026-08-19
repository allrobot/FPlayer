#!/usr/bin/env python3
"""Generate the deterministic T32 native and Gradle license inventory."""

from __future__ import annotations

import argparse
import json
import os
import posixpath
import re
from pathlib import Path
from typing import Any


class LicenseMatrixError(ValueError):
    """Raised when release evidence is incomplete or inconsistent."""


REQUIRED_EVIDENCE = (
    "source-manifest.json",
    "build-options.json",
    "elf-dependencies.json",
    "toolchain-manifest.json",
)
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise LicenseMatrixError(f"required evidence is missing: {path.name}") from error
    except (OSError, json.JSONDecodeError) as error:
        raise LicenseMatrixError(f"cannot read JSON evidence {path.name}: {error}") from error
    if not isinstance(value, dict):
        raise LicenseMatrixError(f"JSON evidence must be an object: {path.name}")
    return value


def _safe_relative(value: str, *, label: str) -> str:
    """Normalize a repository-relative path and reject host/cache locations."""
    if not isinstance(value, str) or not value:
        raise LicenseMatrixError(f"{label} must be a non-empty relative path")
    value = value.replace("\\", "/")
    if value.startswith(("/", "//")) or re.match(r"^[A-Za-z]:", value):
        raise LicenseMatrixError(f"{label} must not be absolute")
    normalized = posixpath.normpath(value)
    if normalized == "." or normalized == ".." or normalized.startswith("../"):
        raise LicenseMatrixError(f"{label} escapes its repository root")
    if ".gradle" in normalized.lower():
        raise LicenseMatrixError(f"{label} contains a Gradle cache path")
    return normalized


def _display_path(path: Path, repo_root: Path) -> str:
    try:
        relative = path.resolve().relative_to(repo_root.resolve())
        return _safe_relative(relative.as_posix(), label="evidence path")
    except ValueError:
        # Temporary fixture paths are represented by a harmless logical name.
        return _safe_relative(path.name, label="evidence path")


def _revision(source: dict[str, Any], *, label: str) -> str:
    revision = source.get("revision")
    digest = source.get("sha256")
    if revision:
        if not HEX40.fullmatch(revision):
            raise LicenseMatrixError(f"{label}: invalid revision")
        return revision
    if digest:
        if not HEX64.fullmatch(digest):
            raise LicenseMatrixError(f"{label}: invalid sha256")
        return digest
    raise LicenseMatrixError(f"{label}: missing revision or sha256")


def _source_index(
    items: Any, *, label: str, key: str = "id"
) -> dict[str, dict[str, Any]]:
    if not isinstance(items, list):
        raise LicenseMatrixError(f"{label} must be a list")
    result: dict[str, dict[str, Any]] = {}
    for item in items:
        if not isinstance(item, dict) or not item.get(key):
            raise LicenseMatrixError(f"{label} contains an entry without {key}")
        item_id = item[key]
        if item_id in result:
            raise LicenseMatrixError(f"duplicate evidence id: {item_id}")
        result[item_id] = item
    return result


def _notice_paths(
    source: dict[str, Any], evidence_root: Path, repo_root: Path
) -> list[str]:
    files = source.get("notice_files")
    if not isinstance(files, list) or not files:
        raise LicenseMatrixError(f"{source.get('id', '<unknown>')}: missing notice files")
    result: list[str] = []
    source_root = evidence_root / "licenses" / source["id"]
    for notice in files:
        relative = _safe_relative(notice, label=f"{source['id']} notice path")
        path = source_root.joinpath(*relative.split("/"))
        if not path.is_file() or path.stat().st_size == 0:
            raise LicenseMatrixError(f"{source['id']}: missing notice path {relative}")
        result.append(_display_path(path, repo_root))
    return sorted(set(result))


def _linkage(source_id: str, builds: list[dict[str, Any]]) -> str:
    values = sorted(
        {
            str(build.get("linkage"))
            for build in builds
            if source_id in build.get("source_ids", []) and build.get("linkage")
        }
    )
    return ";".join(values) if values else "unlinked"


def generate_license_matrix(
    lock_path: Path, native_evidence: Path, gradle_report: Path, output_path: Path
) -> None:
    """Join locked sources, native evidence, ELF outputs, and Gradle metadata."""
    lock = _read_json(lock_path)
    if lock.get("schema_version") != 1 or not lock.get("lock_id"):
        raise LicenseMatrixError("native lock has an unsupported schema or missing lock_id")
    lock_id = lock["lock_id"]
    repo_root = lock_path.resolve().parent.parent
    native_evidence = native_evidence.resolve()
    gradle_report = gradle_report.resolve()
    evidence = {
        name: _read_json(native_evidence / name) for name in REQUIRED_EVIDENCE
    }
    for name, document in evidence.items():
        if name != "toolchain-manifest.json" and document.get("lock_id") != lock_id:
            raise LicenseMatrixError(f"{name}: lock_id does not match native lock")
    if not gradle_report.is_file():
        raise LicenseMatrixError(
            "Gradle resolution report is unavailable; compliance matrix is BLOCKED"
        )
    gradle = _read_json(gradle_report)
    components = gradle.get("components", gradle.get("dependencies"))
    if not isinstance(components, list):
        raise LicenseMatrixError("Gradle report must contain a components list")

    lock_sources = _source_index(lock.get("sources"), label="native lock sources")
    source_manifest = _source_index(
        evidence["source-manifest.json"].get("sources"), label="source manifest"
    )
    option_sources = _source_index(
        evidence["build-options.json"].get("sources"), label="build-options sources"
    )
    builds = lock.get("builds", [])
    if not isinstance(builds, list):
        raise LicenseMatrixError("native lock builds must be a list")

    entries: list[dict[str, Any]] = []
    for source_id, source in lock_sources.items():
        expected_revision = _revision(source, label=source_id)
        option = option_sources.get(source_id)
        if option is None or _revision(option, label=f"build-options:{source_id}") != expected_revision:
            raise LicenseMatrixError(f"{source_id}: revision/hash does not match build evidence")
        observed = source_manifest.get(source_id)
        if observed is None:
            if source.get("role") not in {"reference-only", "toolchain-runtime"}:
                raise LicenseMatrixError(f"{source_id}: missing source-manifest evidence")
        elif _revision(observed, label=f"source-manifest:{source_id}") != expected_revision:
            raise LicenseMatrixError(f"{source_id}: revision/hash does not match source evidence")
        notices = _notice_paths(source, native_evidence, repo_root)
        evidence_refs = {
            _display_path(native_evidence / "build-options.json", repo_root),
            *notices,
        }
        if observed is not None:
            evidence_refs.add(_display_path(native_evidence / "source-manifest.json", repo_root))
        if source.get("role") == "toolchain-runtime":
            evidence_refs.add(_display_path(native_evidence / "toolchain-manifest.json", repo_root))
        entries.append(
            {
                "id": f"native:{source_id}",
                "source_role": source["role"],
                "revision_or_hash": expected_revision,
                "linkage": _linkage(source_id, builds),
                "license_expression": source["license_expression"],
                "selected_license": source.get("selected_license", source["license_expression"]),
                "notice_paths": notices,
                "runtime_scope": "reference-only" if source["role"] == "reference-only" else source["role"],
                "evidence_refs": sorted(evidence_refs),
            }
        )

    elf = evidence["elf-dependencies.json"].get("libraries")
    if not isinstance(elf, list):
        raise LicenseMatrixError("elf-dependencies.json must contain libraries")
    elf_by_name = _source_index(elf, label="ELF libraries", key="soname")
    expected_artifacts = lock.get("expected_artifacts", {}).get("apk_shared_libraries", [])
    for soname in expected_artifacts:
        library = elf_by_name.get(soname)
        if library is None or not library.get("sha256"):
            raise LicenseMatrixError(f"ELF evidence is missing expected library: {soname}")
        digest = library["sha256"]
        if not HEX64.fullmatch(digest):
            raise LicenseMatrixError(f"ELF evidence has invalid sha256: {soname}")
        entries.append(
            {
                "id": f"artifact:{soname}",
                "source_role": "native-artifact",
                "revision_or_hash": digest,
                "linkage": "shared",
                "license_expression": "evidence-only",
                "selected_license": "evidence-only",
                "notice_paths": [],
                "runtime_scope": "runtime",
                "evidence_refs": [_display_path(native_evidence / "elf-dependencies.json", repo_root)],
            }
        )

    seen_gradle: set[str] = set()
    for component in components:
        if not isinstance(component, dict) or not component.get("id"):
            raise LicenseMatrixError("Gradle component is missing id")
        component_id = str(component["id"])
        if component_id in seen_gradle:
            raise LicenseMatrixError(f"duplicate Gradle component id: {component_id}")
        seen_gradle.add(component_id)
        expression = component.get("license_expression")
        selected = component.get("selected_license")
        notices = component.get("notice_paths")
        if not expression or not selected or not isinstance(notices, list) or not notices:
            raise LicenseMatrixError(f"{component_id}: incomplete license metadata")
        safe_notices = sorted({_safe_relative(path, label=f"{component_id} notice path") for path in notices})
        revision = component.get("sha256") or component.get("revision_or_hash") or component.get("version")
        if not revision:
            raise LicenseMatrixError(f"{component_id}: missing version or hash")
        entries.append(
            {
                "id": f"gradle:{component_id}",
                "source_role": "gradle-dependency",
                "revision_or_hash": str(revision),
                "linkage": str(component.get("linkage", "declared")),
                "license_expression": str(expression),
                "selected_license": str(selected),
                "notice_paths": safe_notices,
                "runtime_scope": str(component.get("runtime_scope", "runtime")),
                "evidence_refs": [_display_path(gradle_report, repo_root)],
            }
        )

    entries.sort(key=lambda entry: entry["id"])
    matrix = {
        "schema_version": 1,
        "status": "PASS",
        "lock_id": lock_id,
        "generation_inputs": {
            "native_lock": _display_path(lock_path, repo_root),
            "native_evidence": _display_path(native_evidence, repo_root),
            "gradle_report": _display_path(gradle_report, repo_root),
        },
        "entries": entries,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_name(output_path.name + ".tmp")
    temporary.write_text(json.dumps(matrix, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    os.replace(temporary, output_path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--native-evidence", type=Path, required=True)
    parser.add_argument("--gradle-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        generate_license_matrix(args.lock, args.native_evidence, args.gradle_report, args.output)
    except (LicenseMatrixError, OSError, json.JSONDecodeError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Assemble deterministic release NOTICE files from the license matrix."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import posixpath
import re
from pathlib import Path
from typing import Any


class NoticeAssemblyError(ValueError):
    """Raised when notice evidence is incomplete or unsafe to assemble."""


VALID_MATRIX_STATUSES = {"PASS", "FAIL", "BLOCKED", "NOT_RUN"}


def _read_matrix(path: Path) -> tuple[dict[str, Any], bytes]:
    try:
        raw = path.read_bytes()
        matrix = json.loads(raw.decode("utf-8"))
    except FileNotFoundError as error:
        raise NoticeAssemblyError("license matrix is missing") from error
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise NoticeAssemblyError(f"license matrix cannot be read: {error}") from error
    if not isinstance(matrix, dict):
        raise NoticeAssemblyError("license matrix must be a JSON object")
    if matrix.get("schema_version") != 1:
        raise NoticeAssemblyError("license matrix schema_version must be 1")
    status = matrix.get("status")
    if status not in VALID_MATRIX_STATUSES:
        raise NoticeAssemblyError("license matrix has an invalid status")
    if not isinstance(matrix.get("entries"), list):
        raise NoticeAssemblyError("license matrix entries must be a list")
    return matrix, raw


def _safe_relative(value: Any, *, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise NoticeAssemblyError(f"{label} must be a non-empty relative path")
    value = value.replace("\\", "/")
    if value.startswith(("/", "//")) or re.match(r"^[A-Za-z]:", value):
        raise NoticeAssemblyError(f"{label} must be repository-relative")
    normalized = posixpath.normpath(value)
    if normalized in {".", ".."} or normalized.startswith("../"):
        raise NoticeAssemblyError(f"{label} escapes its allowed root")
    return normalized


def _safe_field(value: Any, *, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise NoticeAssemblyError(f"{label} must be a non-empty string")
    if "\n" in value or "\r" in value:
        raise NoticeAssemblyError(f"{label} must be a single line")
    return value.strip()


def _resolve_notice_path(license_root: Path, logical_path: str) -> Path:
    parts = logical_path.split("/")
    if "licenses" in parts:
        parts = parts[parts.index("licenses") + 1 :]
    if not parts:
        raise NoticeAssemblyError(f"notice path has no file: {logical_path}")
    root = license_root.resolve()
    candidate = root.joinpath(*parts).resolve()
    try:
        candidate.relative_to(root)
    except ValueError as error:
        raise NoticeAssemblyError(
            f"notice path escapes license root: {logical_path}"
        ) from error
    if not candidate.is_file() or candidate.stat().st_size == 0:
        raise NoticeAssemblyError(f"missing or empty notice path: {logical_path}")
    return candidate


def _read_notice_text(path: Path, logical_path: str) -> str:
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise NoticeAssemblyError(
            f"notice path is not readable UTF-8: {logical_path}"
        ) from error
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if not text.strip():
        raise NoticeAssemblyError(f"missing or empty notice path: {logical_path}")
    return text.rstrip("\n") + "\n"


def _component_records(
    entries: list[Any], license_root: Path
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    components: list[dict[str, Any]] = []
    texts: list[dict[str, str]] = []
    seen_ids: set[str] = set()
    seen_pairs: set[tuple[str, str]] = set()
    for raw_entry in entries:
        if not isinstance(raw_entry, dict):
            raise NoticeAssemblyError("license matrix contains a non-object entry")
        component_id = _safe_field(raw_entry.get("id"), label="component id")
        if component_id in seen_ids:
            raise NoticeAssemblyError(f"duplicate component id: {component_id}")
        seen_ids.add(component_id)
        role = _safe_field(raw_entry.get("source_role"), label=f"{component_id} role")
        revision = _safe_field(
            raw_entry.get("revision_or_hash"), label=f"{component_id} revision"
        )
        selected_license = _safe_field(
            raw_entry.get("selected_license"), label=f"{component_id} license"
        )
        runtime_scope = _safe_field(
            raw_entry.get("runtime_scope"), label=f"{component_id} runtime scope"
        )
        raw_paths = raw_entry.get("notice_paths")
        if not isinstance(raw_paths, list):
            raise NoticeAssemblyError(f"{component_id} notice_paths must be a list")
        notice_paths: list[str] = []
        resolved_texts: list[tuple[str, str]] = []
        for raw_path in raw_paths:
            logical_path = _safe_relative(
                raw_path, label=f"{component_id} notice path"
            )
            pair = (component_id, logical_path)
            if pair in seen_pairs:
                raise NoticeAssemblyError(
                    f"duplicate component notice pair: {component_id} {logical_path}"
                )
            seen_pairs.add(pair)
            notice_path = _resolve_notice_path(license_root, logical_path)
            resolved_texts.append(
                (logical_path, _read_notice_text(notice_path, logical_path))
            )
            notice_paths.append(logical_path)
        notice_paths.sort()
        components.append(
            {
                "id": component_id,
                "role": role,
                "revision": revision,
                "selected_license": selected_license,
                "runtime_scope": runtime_scope,
                "notice_paths": notice_paths,
            }
        )
        include_reference_text = raw_entry.get("notice_required") is True
        if runtime_scope != "reference-only" or include_reference_text:
            for logical_path, text in resolved_texts:
                texts.append(
                    {
                        "id": component_id,
                        "path": logical_path,
                        "license": selected_license,
                        "revision": revision,
                        "text": text,
                    }
                )
    components.sort(key=lambda item: item["id"])
    texts.sort(key=lambda item: (item["id"], item["path"]))
    return components, texts


def _render_notice(
    matrix: dict[str, Any], matrix_hash: str, components: list[dict[str, Any]]
) -> str:
    lines = [
        "FPlayer NOTICE",
        "==============",
        "",
        "FPlayer-owned source is licensed under GPL-3.0-or-later.",
        "Project license text: LICENSE",
        "License matrix: docs/compliance/license-matrix.json",
        f"License matrix schema: {matrix['schema_version']}",
        f"License matrix status: {matrix['status']}",
        f"License matrix SHA-256: {matrix_hash}",
        f"Native lock ID: {matrix.get('lock_id', 'not-recorded')}",
        "",
        "Component attribution index",
        "---------------------------",
    ]
    if not components:
        lines.append(
            "No component rows are available while the license matrix is non-PASS."
        )
    for component in components:
        notices = ", ".join(component["notice_paths"]) or "none"
        lines.append(
            " | ".join(
                (
                    component["id"],
                    component["role"],
                    component["selected_license"],
                    component["runtime_scope"],
                    component["revision"],
                    notices,
                )
            )
        )
    lines.extend(
        (
            "",
            "This index is not a PASS claim. A non-PASS matrix status means the",
            "release-license gate remains BLOCKED until the complete dependency",
            "closure and every required original notice text are verified.",
        )
    )
    return "\n".join(lines) + "\n"


def _render_third_party(
    matrix: dict[str, Any], matrix_hash: str, texts: list[dict[str, str]]
) -> str:
    parts = [
        "FPlayer THIRD_PARTY_LICENSES\n"
        "============================\n\n"
        "License matrix: docs/compliance/license-matrix.json\n"
        f"License matrix status: {matrix['status']}\n"
        f"License matrix SHA-256: {matrix_hash}\n\n"
    ]
    if not texts:
        parts.append(
            "No third-party license text is assembled while the license matrix "
            "is non-PASS.\n"
        )
    for item in texts:
        parts.append(
            "----- BEGIN NOTICE -----\n"
            f"Component: {item['id']}\n"
            f"Path: {item['path']}\n"
            f"Selected-License: {item['license']}\n"
            f"Revision-Or-Hash: {item['revision']}\n\n"
            f"{item['text']}"
            "----- END NOTICE -----\n\n"
        )
    return "".join(parts).rstrip("\n") + "\n"


def _atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp.t32-notices")
    temporary.write_text(content, encoding="utf-8", newline="\n")
    if temporary.read_text(encoding="utf-8") != content:
        raise NoticeAssemblyError(f"temporary output read-back failed: {path.name}")
    os.replace(temporary, path)


def assemble_notices(
    matrix_path: Path,
    license_root: Path,
    notice_path: Path,
    third_party_path: Path,
    project_license: Path | None = None,
) -> None:
    """Validate matrix evidence and write stable top-level notice assets."""
    matrix, matrix_bytes = _read_matrix(matrix_path)
    if project_license is None:
        project_license = Path(__file__).resolve().parents[1] / "LICENSE"
    try:
        project_text = project_license.read_text(encoding="utf-8")
    except (FileNotFoundError, OSError, UnicodeDecodeError) as error:
        raise NoticeAssemblyError("project LICENSE is missing or unreadable") from error
    if not project_text.strip():
        raise NoticeAssemblyError("project LICENSE is empty")
    components, texts = _component_records(matrix["entries"], license_root)
    matrix_hash = hashlib.sha256(matrix_bytes).hexdigest().upper()
    notice = _render_notice(matrix, matrix_hash, components)
    third_party = _render_third_party(matrix, matrix_hash, texts)
    _atomic_write(notice_path, notice)
    _atomic_write(third_party_path, third_party)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--license-root", type=Path, required=True)
    parser.add_argument("--project-license", type=Path, required=True)
    parser.add_argument("--notice", type=Path, required=True)
    parser.add_argument("--third-party", type=Path, required=True)
    args = parser.parse_args()
    try:
        assemble_notices(
            args.matrix,
            args.license_root,
            args.notice,
            args.third_party,
            args.project_license,
        )
    except (NoticeAssemblyError, OSError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Materialize the T02 native inputs from the immutable source lock."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.parse
from pathlib import Path


def run(*args: str, cwd: Path | None = None) -> str:
    return subprocess.check_output(args, cwd=cwd, text=True, stderr=subprocess.STDOUT).strip()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def materialize_git(source: dict, destination: Path) -> None:
    revision = source["revision"]
    if destination.exists() and (destination / ".git").exists():
        actual = run("git", "-C", str(destination), "rev-parse", "HEAD")
        if actual != revision:
            run("git", "-C", str(destination), "fetch", "--depth=1", "origin", revision)
            run("git", "-C", str(destination), "checkout", "--detach", revision)
    elif destination.exists():
        raise RuntimeError(f"refusing to reuse non-git input directory: {destination}")
    else:
        destination.mkdir(parents=True)
        run("git", "-C", str(destination), "init", "--quiet")
        run("git", "-C", str(destination), "remote", "add", "origin", source["url"])
        run("git", "-C", str(destination), "fetch", "--depth=1", "origin", revision)
        run("git", "-C", str(destination), "checkout", "--detach", revision)
    if source["kind"] == "git-submodule":
        run("git", "-C", str(destination), "submodule", "update", "--init", "--recursive")
    actual = run("git", "-C", str(destination), "rev-parse", "HEAD")
    if actual != revision:
        raise RuntimeError(f"{source['id']}: resolved {actual}, expected {revision}")


def materialize_archive(source: dict, destination: Path, cache: Path) -> None:
    archive = cache / Path(urllib.parse.urlparse(source["url"]).path).name
    cache.mkdir(parents=True, exist_ok=True)
    if not archive.exists() or sha256(archive) != source["sha256"]:
        temporary = archive.with_suffix(archive.suffix + ".part")
        if temporary.exists():
            temporary.unlink()
        run("curl", "--fail", "--location", "--retry", "3", "--output", str(temporary), source["url"])
        if sha256(temporary) != source["sha256"]:
            temporary.unlink(missing_ok=True)
            raise RuntimeError(f"{source['id']}: archive SHA-256 mismatch")
        temporary.replace(archive)
    destination.mkdir(parents=True, exist_ok=True)
    marker = destination / ".archive.sha256"
    if marker.exists() and marker.read_text(encoding="ascii").strip() == source["sha256"]:
        return
    for child in destination.iterdir():
        if child.name != ".archive.sha256":
            if child.is_dir():
                shutil.rmtree(child)
            else:
                child.unlink()
    run("tar", "-xf", str(archive), "--strip-components=1", "-C", str(destination))
    marker.write_text(source["sha256"] + "\n", encoding="ascii")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    args.source_root.mkdir(parents=True, exist_ok=True)
    resolved = []
    deferred_submodules = []
    for source in lock["sources"]:
        if source["role"] in {"reference-only", "toolchain-runtime"}:
            continue
        if source["kind"] == "git-submodule":
            deferred_submodules.append(source)
            continue
        destination = args.source_root / source["id"]
        if source["kind"] == "git":
            materialize_git(source, destination)
            resolved_revision = run("git", "-C", str(destination), "rev-parse", "HEAD")
            resolved.append({"id": source["id"], "kind": source["kind"], "revision": resolved_revision, "path": source["id"]})
        else:
            materialize_archive(source, destination, args.cache)
            resolved.append({"id": source["id"], "kind": source["kind"], "sha256": source["sha256"], "path": source["id"]})
    for source in deferred_submodules:
        parent = args.source_root / source["parent"]
        destination = parent / source["path"]
        run("git", "-C", str(parent), "submodule", "update", "--init", "--recursive", "--", source["path"])
        actual = run("git", "-C", str(destination), "rev-parse", "HEAD")
        if actual != source["revision"]:
            raise RuntimeError(f"{source['id']}: resolved {actual}, expected {source['revision']}")
        resolved.append({"id": source["id"], "kind": source["kind"], "revision": actual, "path": f"{source['parent']}/{source['path']}"})
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps({"lock_id": lock["lock_id"], "sources": resolved}, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(resolved)} locked inputs")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        if isinstance(error, subprocess.CalledProcessError) and error.output:
            print(error.output.rstrip(), file=sys.stderr)
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)

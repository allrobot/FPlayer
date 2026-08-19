#!/usr/bin/env python3
"""Collect hashes and ELF metadata for a completed T02 prefix."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path


def run(*args: str) -> str:
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prefix", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    source_manifest = json.loads(args.source_manifest.read_text(encoding="utf-8"))
    source_paths = {source["id"]: args.source_root / source["path"] for source in source_manifest["sources"]}
    args.output.mkdir(parents=True, exist_ok=True)
    libraries = sorted(args.prefix.glob("lib/*.so"))
    hashes = []
    elf = []
    readelf = "llvm-readelf"
    forbidden_path = str(args.source_root.parent).encode()
    for library in libraries:
        if forbidden_path in library.read_bytes():
            raise RuntimeError(f"{library.name}: embeds the generated work directory")
        hashes.append(f"{sha256(library)}  {library.relative_to(args.prefix).as_posix()}")
        header = run(readelf, "-h", str(library))
        dynamic = run(readelf, "-d", str(library))
        program_headers = run(readelf, "-lW", str(library))
        needed = re.findall(r"Shared library: \[(.*?)\]", dynamic)
        soname = next(iter(re.findall(r"SONAME.*\[(.*?)\]", dynamic)), None)
        machine = next(iter(re.findall(r"Machine:\s+(.*)", header)), "unknown").strip()
        if "AArch64" not in machine:
            raise RuntimeError(f"{library.name}: unexpected ELF machine {machine}")
        alignments = [int(line.split()[-1], 16) for line in program_headers.splitlines() if line.lstrip().startswith("LOAD ")]
        if not alignments or min(alignments) < 16384:
            raise RuntimeError(f"{library.name}: LOAD alignment is below 16 KiB")
        elf.append({"path": library.relative_to(args.prefix).as_posix(), "sha256": sha256(library), "soname": soname, "machine": machine, "needed": needed, "load_alignments": alignments})
    expected = set(lock["expected_artifacts"]["apk_shared_libraries"])
    actual = {library.name for library in libraries}
    missing = expected - actual
    if missing:
        raise RuntimeError(f"missing expected shared libraries: {sorted(missing)}")
    unexpected_artifacts = actual - expected
    if unexpected_artifacts:
        raise RuntimeError(f"unexpected shared libraries: {sorted(unexpected_artifacts)}")
    allowed_needed = expected | set(lock["expected_artifacts"]["allowed_android_system_libraries"])
    unexpected_needed = {name for item in elf for name in item["needed"]} - allowed_needed
    if unexpected_needed:
        raise RuntimeError(f"unreviewed DT_NEEDED libraries: {sorted(unexpected_needed)}")
    (args.output / "checksums.sha256").write_text("\n".join(hashes) + "\n", encoding="ascii")
    (args.output / "elf-dependencies.json").write_text(json.dumps({"lock_id": lock["lock_id"], "libraries": elf}, indent=2) + "\n", encoding="utf-8")
    license_root = args.output / "licenses"
    for source in lock["sources"]:
        if source["role"] in {"reference-only", "toolchain-runtime"}:
            continue
        base = source_paths[source["id"]]
        for notice in source["notice_files"]:
            source_file = base / notice
            if not source_file.is_file():
                raise RuntimeError(f"{source['id']}: missing notice file {notice}")
            destination = license_root / source["id"] / notice
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source_file, destination)
    for notice in lock["target"]["ndk_package_notice_files"]:
        source_file = args.ndk / notice
        if not source_file.is_file():
            raise RuntimeError(f"android-ndk: missing package notice {notice}")
        destination = license_root / "android-ndk" / notice
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_file, destination)
    toolchain = {
        "ndk_package": lock["target"]["ndk_package"],
        "clang": run("clang", "--version").splitlines()[0],
        "lld": run("ld.lld", "--version").splitlines()[0],
        "meson": run("meson", "--version"),
        "ninja": run("ninja", "--version"),
        "python": run("python3", "--version"),
    }
    (args.output / "toolchain-manifest.json").write_text(json.dumps(toolchain, indent=2) + "\n", encoding="utf-8")
    print(f"Collected evidence for {len(libraries)} shared libraries")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}")
        raise SystemExit(1)

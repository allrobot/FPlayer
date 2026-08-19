#!/usr/bin/env python3
"""Validate the pinned native source and license lock without network access."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "native-build" / "sources.lock.json"
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def fail(message: str) -> None:
    raise ValueError(message)


def main() -> int:
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if lock.get("schema_version") != 1:
        fail("schema_version must be 1")

    policy = lock.get("policy", {})
    if policy.get("native_license_mode") != "GPL-3.0-or-later":
        fail("native_license_mode must remain GPL-3.0-or-later")
    if policy.get("allow_nonfree") is not False:
        fail("nonfree native inputs must remain disabled")

    target = lock.get("target", {})
    if target.get("abis") != ["arm64-v8a"]:
        fail("T02 is locked to arm64-v8a only")
    if target.get("ndk_package") != "ndk;29.0.14206865":
        fail("unexpected NDK package")
    if target.get("meson_version") != "1.11.2":
        fail("unexpected Meson version")
    if not HEX40.fullmatch(target.get("ndk_commit", "")):
        fail("ndk_commit must be a full lowercase Git revision")
    if target.get("ndk_package_notice_files") != ["NOTICE", "NOTICE.toolchain"]:
        fail("NDK package NOTICE files must remain explicit")

    sources = lock.get("sources", [])
    ndk_source = next((source for source in sources if source.get("id") == "android-ndk"), None)
    if not ndk_source or ndk_source.get("revision") != target.get("ndk_commit"):
        fail("android-ndk source revision must match target.ndk_commit")
    ids = [source.get("id") for source in sources]
    if len(ids) != len(set(ids)):
        fail("source ids must be unique")

    for source in sources:
        source_id = source.get("id", "<missing>")
        for field in ("id", "kind", "role", "url", "license_expression"):
            if not source.get(field):
                fail(f"{source_id}: missing {field}")
        if not source.get("notice_files"):
            fail(f"{source_id}: notice_files must not be empty")
        if source["kind"] in {"git", "git-submodule"}:
            if not HEX40.fullmatch(source.get("revision", "")):
                fail(f"{source_id}: revision must be 40 lowercase hex characters")
        elif source["kind"] == "archive":
            if not HEX64.fullmatch(source.get("sha256", "")):
                fail(f"{source_id}: sha256 must be 64 lowercase hex characters")
        else:
            fail(f"{source_id}: unsupported source kind {source['kind']}")

    source_ids = set(ids)
    builds = lock.get("builds", [])
    build_ids = {build.get("id") for build in builds}
    required_builds = {
        "mbedtls",
        "dav1d",
        "freetype",
        "fribidi",
        "harfbuzz",
        "libunibreak",
        "libxml2",
        "fontconfig",
        "libplacebo",
        "ffmpeg",
        "libass",
        "curl",
        "lua",
        "mpv",
    }
    if build_ids != required_builds:
        fail(f"build ids differ from required closure: {sorted(build_ids)}")
    for build in builds:
        missing = set(build.get("source_ids", [])) - source_ids
        if missing:
            fail(f"{build['id']}: unknown source ids {sorted(missing)}")
        if not build.get("options"):
            fail(f"{build['id']}: build options must not be empty")

    by_build = {build["id"]: build for build in builds}
    ffmpeg_options = set(by_build["ffmpeg"]["options"])
    for required in ("--enable-gpl", "--enable-version3", "--enable-shared", "--disable-debug"):
        if required not in ffmpeg_options:
            fail(f"ffmpeg: missing {required}")
    for forbidden in by_build["ffmpeg"].get("forbidden_options", []):
        if forbidden in ffmpeg_options:
            fail(f"ffmpeg: forbidden option enabled: {forbidden}")

    mpv_options = set(by_build["mpv"]["options"])
    for required in ("-Dgpl=true", "-Dlibmpv=true", "-Dcplayer=false"):
        if required not in mpv_options:
            fail(f"mpv: missing {required}")

    expected = lock.get("expected_artifacts", {})
    if "libmpv.so" not in expected.get("apk_shared_libraries", []):
        fail("expected artifact list must include libmpv.so")
    if "libc++_shared.so" not in expected.get("apk_shared_libraries", []):
        fail("T02 must account for libc++_shared.so")
    required_system_libraries = {
        "libandroid.so",
        "libc.so",
        "libdl.so",
        "libEGL.so",
        "libGLESv2.so",
        "liblog.so",
        "libm.so",
        "libmediandk.so",
        "libOpenSLES.so",
        "libz.so",
    }
    if set(expected.get("allowed_android_system_libraries", [])) != required_system_libraries:
        fail("Android system library allowlist differs from the reviewed closure")
    if "elf-dependencies.json" not in expected.get("required_build_evidence", []):
        fail("T02 must record the resolved ELF dependency graph")
    if "reproducibility.sha256" not in expected.get("required_build_evidence", []):
        fail("T02 must compare two clean build outputs")

    print(
        f"OK: {LOCK_PATH.relative_to(ROOT)} contains "
        f"{len(sources)} locked sources and {len(builds)} build recipes"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)

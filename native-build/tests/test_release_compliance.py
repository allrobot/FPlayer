"""Contract tests for the T32 release compliance inventory."""

from __future__ import annotations

import importlib.util
import json
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


GENERATOR = Path(__file__).resolve().parents[1] / "generate-license-matrix.py"
SPEC = importlib.util.spec_from_file_location("generate_license_matrix", GENERATOR)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import contract
    raise ImportError(f"cannot load {GENERATOR}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

ASSEMBLER = Path(__file__).resolve().parents[1] / "assemble-notices.py"
ROOT = Path(__file__).resolve().parents[2]


def _load_hygiene_scanner():
    scanner = ROOT / "native-build" / "scan-release-hygiene.py"
    spec = importlib.util.spec_from_file_location("scan_release_hygiene", scanner)
    if spec is None or spec.loader is None:  # pragma: no cover - import contract
        raise ImportError(f"cannot load {scanner}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _load_assembler():
    spec = importlib.util.spec_from_file_location("assemble_notices", ASSEMBLER)
    if spec is None or spec.loader is None:  # pragma: no cover - import contract
        raise ImportError(f"cannot load {ASSEMBLER}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class LicenseMatrixTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.lock = self.root / "sources.lock.json"
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        (self.evidence / "licenses" / "runtime").mkdir(parents=True)
        (self.evidence / "licenses" / "reference").mkdir(parents=True)
        (self.evidence / "licenses" / "runtime" / "LICENSE").write_text(
            "runtime license\n", encoding="utf-8"
        )
        (self.evidence / "licenses" / "reference" / "LICENSE").write_text(
            "reference license\n", encoding="utf-8"
        )
        lock = {
            "schema_version": 1,
            "lock_id": "fixture-lock",
            "sources": [
                {
                    "id": "runtime",
                    "kind": "git",
                    "role": "runtime-shared",
                    "revision": "a" * 40,
                    "license_expression": "MIT",
                    "selected_license": "MIT",
                    "notice_files": ["LICENSE"],
                },
                {
                    "id": "reference",
                    "kind": "git",
                    "role": "reference-only",
                    "revision": "b" * 40,
                    "license_expression": "MIT",
                    "notice_files": ["LICENSE"],
                },
            ],
            "builds": [{"id": "runtime", "source_ids": ["runtime"], "linkage": "shared"}],
            "expected_artifacts": {"apk_shared_libraries": ["libruntime.so"]},
        }
        self.lock.write_text(json.dumps(lock), encoding="utf-8")
        self._write_json(
            "source-manifest.json",
            {
                "lock_id": "fixture-lock",
                "sources": [{"id": "runtime", "revision": "a" * 40}],
            },
        )
        self._write_json(
            "build-options.json",
            {
                "lock_id": "fixture-lock",
                "sources": [
                    {"id": "runtime", "revision": "a" * 40},
                    {"id": "reference", "revision": "b" * 40},
                ],
            },
        )
        self._write_json(
            "elf-dependencies.json",
            {
                "lock_id": "fixture-lock",
                "libraries": [
                    {
                        "soname": "libruntime.so",
                        "path": "lib/libruntime.so",
                        "sha256": "c" * 64,
                    }
                ],
            },
        )
        self._write_json("toolchain-manifest.json", {"ndk_package": "fixture"})
        self.report = self.root / "gradle-resolution.json"
        self.report.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "components": [
                        {
                            "id": "com.example:fixture:1.0",
                            "version": "1.0",
                            "license_expression": "Apache-2.0",
                            "selected_license": "Apache-2.0",
                            "notice_paths": ["licenses/runtime/LICENSE"],
                            "runtime_scope": "runtime",
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _write_json(self, name: str, value: object) -> None:
        (self.evidence / name).write_text(json.dumps(value), encoding="utf-8")

    def _generate(self, report: Path | None = None) -> dict:
        output = self.root / "matrix.json"
        MODULE.generate_license_matrix(
            self.lock, self.evidence, report or self.report, output
        )
        return json.loads(output.read_text(encoding="utf-8"))

    def test_all_lock_sources_reference_scope_and_elf_evidence(self) -> None:
        matrix = self._generate()
        ids = [entry["id"] for entry in matrix["entries"]]
        self.assertEqual(ids.count("native:runtime"), 1)
        self.assertEqual(ids.count("native:reference"), 1)
        reference = next(entry for entry in matrix["entries"] if entry["id"] == "native:reference")
        self.assertEqual(reference["runtime_scope"], "reference-only")
        artifact = next(entry for entry in matrix["entries"] if entry["id"] == "artifact:libruntime.so")
        self.assertEqual(artifact["revision_or_hash"], "c" * 64)

    def test_missing_notice_raises(self) -> None:
        (self.evidence / "licenses" / "runtime" / "LICENSE").unlink()
        with self.assertRaises(MODULE.LicenseMatrixError):
            self._generate()

    def test_revision_mismatch_raises(self) -> None:
        self._write_json(
            "source-manifest.json",
            {"lock_id": "fixture-lock", "sources": [{"id": "runtime", "revision": "d" * 40}]},
        )
        with self.assertRaises(MODULE.LicenseMatrixError):
            self._generate()

    def test_missing_gradle_report_fails_closed(self) -> None:
        with self.assertRaises(MODULE.LicenseMatrixError):
            self._generate(self.root / "missing-report.json")


class NoticeAssemblyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.module = _load_assembler()
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.license_root = self.root / "licenses"
        self.license_root.mkdir()
        for component, filename, text in (
            ("alpha", "COPYING", "alpha first\n"),
            ("alpha", "NOTICE", "alpha second\n"),
            ("reference", "LICENSE", "reference only\n"),
            ("zulu", "LICENSE", "zulu text\n"),
        ):
            path = self.license_root / component / filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        self.project_license = self.root / "LICENSE"
        self.project_license.write_text("project license\n", encoding="utf-8")
        self.matrix = self.root / "license-matrix.json"
        self.notice = self.root / "NOTICE"
        self.third_party = self.root / "THIRD_PARTY_LICENSES"
        self.entries = [
            self._entry("native:zulu", ["licenses/zulu/LICENSE"]),
            self._entry(
                "native:reference",
                ["licenses/reference/LICENSE"],
                runtime_scope="reference-only",
            ),
            self._entry(
                "native:alpha",
                ["licenses/alpha/NOTICE", "licenses/alpha/COPYING"],
            ),
        ]
        self._write_matrix(self.entries)

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def _entry(
        component_id: str,
        notice_paths: list[str],
        *,
        runtime_scope: str = "runtime",
    ) -> dict:
        return {
            "id": component_id,
            "source_role": "runtime-static",
            "revision_or_hash": "a" * 40,
            "linkage": "static",
            "license_expression": "MIT",
            "selected_license": "MIT",
            "notice_paths": notice_paths,
            "runtime_scope": runtime_scope,
            "evidence_refs": ["evidence/source-manifest.json"],
        }

    def _write_matrix(self, entries: list[dict]) -> None:
        self.matrix.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "status": "PASS",
                    "lock_id": "fixture-lock",
                    "generation_inputs": {},
                    "entries": entries,
                }
            ),
            encoding="utf-8",
        )

    def _assemble(self) -> None:
        self.module.assemble_notices(
            self.matrix,
            self.license_root,
            self.notice,
            self.third_party,
            self.project_license,
        )

    def test_stable_order_preserves_runtime_notice_files(self) -> None:
        self._assemble()
        notice = self.notice.read_text(encoding="utf-8")
        bundle = self.third_party.read_text(encoding="utf-8")
        self.assertLess(notice.index("native:alpha"), notice.index("native:reference"))
        self.assertLess(notice.index("native:reference"), notice.index("native:zulu"))
        self.assertLess(bundle.index("licenses/alpha/COPYING"), bundle.index("licenses/alpha/NOTICE"))
        self.assertLess(bundle.index("licenses/alpha/NOTICE"), bundle.index("licenses/zulu/LICENSE"))
        self.assertIn("alpha first", bundle)
        self.assertIn("alpha second", bundle)
        self.assertIn("zulu text", bundle)
        self.assertNotIn("reference only", bundle)

    def test_duplicate_component_notice_pair_is_rejected(self) -> None:
        duplicate = self._entry(
            "native:alpha",
            ["licenses/alpha/COPYING", "licenses/alpha/COPYING"],
        )
        self._write_matrix([duplicate])
        with self.assertRaises(self.module.NoticeAssemblyError):
            self._assemble()

    def test_missing_or_empty_license_file_is_rejected(self) -> None:
        missing = self.license_root / "zulu" / "LICENSE"
        missing.unlink()
        with self.assertRaises(self.module.NoticeAssemblyError):
            self._assemble()
        missing.write_text("", encoding="utf-8")
        with self.assertRaises(self.module.NoticeAssemblyError):
            self._assemble()

    def test_repeated_runs_are_byte_identical(self) -> None:
        self._assemble()
        first_notice = self.notice.read_bytes()
        first_bundle = self.third_party.read_bytes()
        self._assemble()
        self.assertEqual(first_notice, self.notice.read_bytes())
        self.assertEqual(first_bundle, self.third_party.read_bytes())


class DocumentationTests(unittest.TestCase):
    DOCUMENTS = (
        ROOT / "docs" / "compliance" / "source-attribution.md",
        ROOT / "docs" / "privacy" / "privacy.md",
        ROOT / "docs" / "release" / "release-gates.md",
    )

    def test_release_documents_cover_ownership_privacy_and_blocked_gates(self) -> None:
        attribution, privacy, gates = [
            path.read_text(encoding="utf-8") for path in self.DOCUMENTS
        ]
        self.assertIn("GPL-3.0-or-later", attribution)
        self.assertIn("docs/compliance/license-matrix.json", attribution)
        self.assertIn("semantic-reference", attribution)
        self.assertIn("REF_TCODE_FIRMWARE", attribution)
        self.assertIn("Keystore", privacy)
        self.assertIn("no analytics upload", privacy.lower())
        self.assertRegex(privacy.lower(), r"(redact|sanitiz).*(log|diagnostic)")
        self.assertIn("TEST_TABLET", privacy)
        self.assertIn("FUNSCRIPT_TEST_LIBRARY", privacy)
        self.assertIn("RELEASE-TAG", gates)
        self.assertIn("RELEASE-SIGN", gates)
        self.assertIn("BLOCKED", gates)
        self.assertRegex(gates, r"missing annotated release tag.*BLOCKED")
        self.assertRegex(gates, r"missing production signing identity.*BLOCKED")

    def test_release_documents_use_only_relative_paths_and_logical_resources(self) -> None:
        forbidden = (
            re.compile(r"(?i)\b[A-Z]:[\\/]"),
            re.compile(r"(?i)/(?:Users|home|mnt)/"),
            re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b"),
            re.compile(r"(?i)\bCOM\d+\b|/dev/tty"),
            re.compile(r"(?i)password\s*[:=]|username\s*[:=]"),
            re.compile(r"(?i)\.(?:mp4|mkv|avi|mov|funscript)\b"),
        )
        for path in self.DOCUMENTS:
            text = path.read_text(encoding="utf-8")
            for pattern in forbidden:
                with self.subTest(path=path.name, pattern=pattern.pattern):
                    self.assertIsNone(pattern.search(text))


class HygieneScannerTests(unittest.TestCase):
    """The scanner must reject release-tree leakage without echoing secrets."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        subprocess.run(
            ["git", "init", "--quiet", "--initial-branch", "fixture"],
            cwd=self.root,
            check=True,
            capture_output=True,
            text=True,
        )
        self.module = _load_hygiene_scanner()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _write(self, relative: str, contents: str | bytes) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(contents, bytes):
            path.write_bytes(contents)
        else:
            path.write_text(contents, encoding="utf-8")

    def _track(self, *relative: str) -> None:
        subprocess.run(["git", "add", "--", *relative], cwd=self.root, check=True)

    def test_rejects_sensitive_text_and_untracked_artifacts(self) -> None:
        credential = "pass" + "word" + "=" + '"synthetic-secret"'
        absolute_path = "Q" + ":" + "\\private\\fixture.txt"
        private_key = "-----BEGIN " + "PRIVATE KEY-----"
        device_address = "10" + ".42.0.9"
        bluetooth_address = ":".join(("12", "34", "56", "78", "9A", "BC"))
        serial_port = "C" + "OM42"
        media_name = '"private-title 2026.' + "mkv" + '"'
        self._write(
            "tracked.txt",
            "\n".join(
                (
                    credential,
                    absolute_path,
                    private_key,
                    device_address,
                    bluetooth_address,
                    serial_port,
                    media_name,
                )
            ),
        )
        self._track("tracked.txt")
        self._write("untracked." + "apk", b"PK\x03\x04fixture")
        self._write("native." + "so", b"\x7fELFfixture")
        self._write("leftover.tmp.t32", "temporary")

        findings = self.module.scan_release_hygiene(self.root)
        categories = {finding.category for finding in findings}
        self.assertTrue(
            {
                "credential",
                "absolute-path",
                "private-key",
                "device-address",
                "serial-port",
                "media-filename",
                "untracked-binary",
                "temporary-file",
            }.issubset(categories)
        )
        diagnostics = self.module.format_diagnostics(findings)
        self.assertNotIn("synthetic-secret", diagnostics)
        self.assertNotIn(device_address, diagnostics)
        self.assertTrue(all(re.fullmatch(r"[0-9a-f]{64}", finding.digest) for finding in findings))

    def test_logical_test_identifiers_and_selected_results_are_allowed(self) -> None:
        self._write(
            "README.md",
            "TEST_TABLET TEST_OSR_DEVICE TEST_OSR_WIFI FUNSCRIPT_TEST_LIBRARY\n",
        )
        self._track("README.md")
        self._write(
            "docs/qa/t32-release-compliance-report.md",
            "RELEASE-LICENSE: BLOCKED\nRELEASE-HYGIENE: PASS\n",
        )
        self._write(
            "docs/release/release-gate-result.json",
            '{"schema_version": 1, "gates": []}\n',
        )

        findings = self.module.scan_release_hygiene(
            self.root,
            allow=(
                "docs/qa/t32-release-compliance-report.md",
                "docs/release/release-gate-result.json",
            ),
        )
        self.assertEqual([], findings)

    def test_selected_result_with_leak_is_still_rejected(self) -> None:
        self._write(
            "docs/qa/t32-release-compliance-report.md",
            "token" + "=" + '"synthetic-secret"\n',
        )
        findings = self.module.scan_release_hygiene(
            self.root,
            allow=("docs/qa/t32-release-compliance-report.md",),
        )
        self.assertIn("credential", {finding.category for finding in findings})

    def test_source_constructs_and_neutral_fixture_names_are_allowed(self) -> None:
        self._write(
            "source.kt",
            "\n".join(
                (
                    'val passwordValue = System.getenv("FPLAYER_T32_SMB_PASSWORD")',
                    "val token = state.onPointerUp()!!",
                    "policy path /data/data is intentionally documented",
                    '<component name="fixture" version="3.41.2.2">',
                    'const val media = "t31-synthetic.mp4"',
                )
            ),
        )
        self._track("source.kt")
        self.assertEqual([], self.module.scan_release_hygiene(self.root))

    def test_rejects_key_material_and_tracked_binary_but_skips_build_cache(self) -> None:
        self._write("release-signing.jks", b"fixture-key-material")
        self._write("tracked.aar", b"fixture-library")
        self._track("release-signing.jks", "tracked.aar")
        self._write("build/cache.apk", b"generated-cache")

        findings = self.module.scan_release_hygiene(self.root)
        categories = {finding.category for finding in findings}
        self.assertIn("key-material", categories)
        self.assertIn("binary-artifact", categories)
        self.assertNotIn("untracked-binary", categories)


if __name__ == "__main__":
    unittest.main()

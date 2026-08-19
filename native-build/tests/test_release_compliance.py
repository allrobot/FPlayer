"""Contract tests for the T32 release compliance inventory."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


GENERATOR = Path(__file__).resolve().parents[1] / "generate-license-matrix.py"
SPEC = importlib.util.spec_from_file_location("generate_license_matrix", GENERATOR)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - import contract
    raise ImportError(f"cannot load {GENERATOR}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


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


if __name__ == "__main__":
    unittest.main()

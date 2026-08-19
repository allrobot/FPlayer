"""Tests for the fail-closed T32 release input verifier."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


VERIFIER = Path(__file__).resolve().parents[1] / "verify-release-inputs.py"
SPEC = importlib.util.spec_from_file_location("verify_release_inputs", VERIFIER)
if SPEC is None or SPEC.loader is None:  # pragma: no cover
    raise ImportError(f"cannot load {VERIFIER}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
REPO_ROOT = Path(__file__).resolve().parents[2]


class InputVerificationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self._write("gradle/libs.versions.toml", '[versions]\nfixture = "1.2.3"\n')
        self._write(
            "settings.gradle.kts",
            "dependencyResolutionManagement { repositories { google(); mavenCentral() } }\n",
        )
        self._write_json(
            "native-build/sources.lock.json",
            {
                "lock_id": "fixture-lock",
                "target": {
                    "ndk_package": "ndk;fixture",
                    "meson_version": "1.2.3",
                },
            },
        )
        evidence = self.root / "native-build/out-arm64/evidence"
        for name in (
            "source-manifest.json",
            "build-options.json",
            "elf-dependencies.json",
        ):
            self._write_json(f"native-build/out-arm64/evidence/{name}", {})
        self._write("native-build/out-arm64/evidence/checksums.sha256", "0  fixture\n")
        self._write("native-build/out-arm64/evidence/reproducibility.sha256", "0  fixture\n")
        self._write("native-build/out-arm64/evidence/licenses/fixture/LICENSE", "fixture\n")
        self.toolchain = evidence / "toolchain-manifest.json"
        self._write_json(
            "native-build/out-arm64/evidence/toolchain-manifest.json",
            {
                "ndk_package": "ndk;fixture",
                "clang": "fixture",
                "lld": "fixture",
                "meson": "1.2.3",
                "ninja": "fixture",
                "python": "fixture",
            },
        )
        self.matrix = self.root / "docs/compliance/license-matrix.json"
        self._write_json(
            "docs/compliance/license-matrix.json",
            {
                "schema_version": 1,
                "lock_id": "fixture-lock",
                "status": "PASS",
                "entries": [{"id": "fixture"}],
            },
        )
        self.verification = self.root / "gradle/verification-metadata.xml"
        self._write(
            "gradle/verification-metadata.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
  <configuration><verify-metadata>true</verify-metadata></configuration>
  <components>
    <component group="example" name="fixture" version="1.0">
      <artifact name="fixture.jar"><sha256 value="00" /></artifact>
    </component>
  </components>
</verification-metadata>
""",
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _write(self, relative: str, contents: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding="utf-8")

    def _write_json(self, relative: str, value: object) -> None:
        self._write(relative, json.dumps(value))

    def _verify(self) -> list[str]:
        return MODULE.verify_release_inputs(
            self.root,
            self.verification,
            self.matrix,
            self.toolchain,
        )

    def test_complete_pinned_fixture_passes(self) -> None:
        self.assertEqual([], self._verify())

    def test_missing_verification_metadata_is_blocked(self) -> None:
        self.verification.unlink()
        self.assertTrue(any("verification metadata is missing" in item for item in self._verify()))

    def test_dynamic_version_is_blocked(self) -> None:
        self._write("gradle/libs.versions.toml", '[versions]\nfixture = "1.+"\n')
        self.assertTrue(any("dynamic dependency version" in item for item in self._verify()))

    def test_unapproved_repository_is_blocked(self) -> None:
        self._write(
            "settings.gradle.kts",
            'dependencyResolutionManagement { repositories { maven("https://example.invalid/repo") } }\n',
        )
        self.assertTrue(any("unapproved repository host" in item for item in self._verify()))

    def test_lock_mismatch_and_incomplete_toolchain_are_blocked(self) -> None:
        matrix = json.loads(self.matrix.read_text(encoding="utf-8"))
        matrix["lock_id"] = "wrong-lock"
        self.matrix.write_text(json.dumps(matrix), encoding="utf-8")
        toolchain = json.loads(self.toolchain.read_text(encoding="utf-8"))
        del toolchain["clang"]
        self.toolchain.write_text(json.dumps(toolchain), encoding="utf-8")
        failures = self._verify()
        self.assertTrue(any("lock_id does not match" in item for item in failures))
        self.assertTrue(any("toolchain manifest is missing clang" in item for item in failures))

    def test_absent_reproducibility_evidence_is_blocked(self) -> None:
        (self.root / "native-build/out-arm64/evidence/reproducibility.sha256").unlink()
        self.assertTrue(any("reproducibility.sha256" in item for item in self._verify()))


class ReleaseSigningContractTests(unittest.TestCase):
    def test_release_build_uses_explicit_identity_and_signing_gate(self) -> None:
        build = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        for name in (
            "FPLAYER_APPLICATION_ID",
            "FPLAYER_VERSION_CODE",
            "FPLAYER_VERSION_NAME",
            "FPLAYER_RELEASE_STORE_FILE",
            "FPLAYER_RELEASE_STORE_PASSWORD",
            "FPLAYER_RELEASE_KEY_ALIAS",
            "FPLAYER_RELEASE_KEY_PASSWORD",
        ):
            self.assertIn(name, build)
        self.assertIn("validateReleaseConfiguration", build)
        self.assertIn('signingConfigs.getByName("release")', build)
        self.assertNotIn('signingConfigs.getByName("debug")', build)

    def test_signing_document_prohibits_debug_key_and_records_verification(self) -> None:
        signing = (REPO_ROOT / "docs/release/signing.md").read_text(encoding="utf-8")
        self.assertIn("must never use the debug", signing)
        self.assertIn("zipalign -c -P 16 -v 4", signing)
        self.assertIn("apksigner verify --verbose --print-certs", signing)
        self.assertIn("SHA-256", signing)
        self.assertIn("BLOCKED", signing)


if __name__ == "__main__":
    unittest.main()

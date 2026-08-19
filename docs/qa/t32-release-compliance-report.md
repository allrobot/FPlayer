# T32 Release Compliance Report

Status: BLOCKED. This report records the final gate attempt without claiming a
release-ready build.

## Gate summary

| Gate | Status | Evidence | Reason |
| --- | --- | --- | --- |
| RELEASE-LICENSE | BLOCKED | `docs/compliance/license-matrix.json` | Matrix is BLOCKED and lacks complete dependency entries/evidence. |
| RELEASE-REPRO | BLOCKED | `docs/release/reproducible-build.md` | No annotated release tag or approved production application identity. |
| RELEASE-SIGN | BLOCKED | `docs/release/signing.md` | No approved production signing identity or organization/application identity. |
| RELEASE-HYGIENE | FAIL | `native-build/scan-release-hygiene.py` | One absolute-path category and nine untracked-native-binary findings remain. |
| RELEASE-TAG | BLOCKED | `docs/release/release-gates.md` | No annotated release tag exists. |
| RELEASE-DEVICE | BLOCKED | `docs/qa/t32-acceptance-matrix.md` | Approved production device/profile and physical release-device run are absent. |

## Verification record

- `python native-build/tests/test_release_compliance.py`: PASS, 15 tests.
- `python native-build/verify-lock.py`: PASS; locked source and build recipe inventory validated.
- `python native-build/verify-release-inputs.py --root . --verification gradle/verification-metadata.xml --matrix docs/compliance/license-matrix.json --toolchain native-build/out-arm64/evidence/toolchain-manifest.json`: BLOCKED by the matrix status, missing dependency entries, and missing required evidence.
- `python native-build/scan-release-hygiene.py --root . --allow docs/qa/t32-release-compliance-report.md --allow docs/release/release-gate-result.json`: FAIL; one absolute-path category and nine untracked-native-binary findings remain. The ignored generated JNI output could not be removed by the available cleanup command and is not a release artifact.
- JDK 17 Gradle release/test commands were not rerun in this final gate because production identity, tag, signing, and device prerequisites are absent; no release artifact is claimed.
- `git diff --check`: PASS.

The scanner reports only categories and SHA-256 digests. This report contains no
credentials, endpoints, serial ports, media names, absolute paths, binaries, or
artifact hashes. No production tag, identity, key, device session, or motion
output was created. The generated JSON result is validated against schema
version 1 in `docs/release/release-gate-result.json`.

## Owner decisions required

Provide an approved application/organization identity, production signing
identity through secret storage, an annotated release tag, complete dependency
license evidence, and the approved physical release-device/profile prerequisite.

# T32 Release Compliance Report

This is a detail record for `RELEASE-*`; the current gate states are authoritative only in `docs/qa/t32-acceptance-matrix.md`. The machine-readable result remains `docs/release/release-gate-result.json`.

Status: BLOCKED for public release; personal-use release signing is separately
verified below without claiming a publisher-ready build.

## Gate summary

| Gate | Status | Evidence | Reason |
| --- | --- | --- | --- |
| RELEASE-LICENSE | BLOCKED | `docs/compliance/license-matrix.json` | Matrix is BLOCKED and lacks complete dependency entries/evidence. |
| RELEASE-REPRO | BLOCKED | `docs/release/reproducible-build.md` | No annotated release tag or approved public publisher identity/reproducibility evidence. |
| RELEASE-SIGN | PASS (personal-use scope) | `T32-PERSONAL-RELEASE-20260820` | User-approved personal identity and local signature verify; public publisher certificate remains out of scope. |
| RELEASE-HYGIENE | PASS | `native-build/scan-release-hygiene.py` | Tracked text and selected generated metadata passed the fail-closed scan. |
| RELEASE-TAG | BLOCKED | `docs/release/release-gates.md` | No annotated release tag exists. |
| RELEASE-DEVICE | BLOCKED | `docs/qa/t32-acceptance-matrix.md` | Approved production device/profile and physical release-device run are absent. |

## Personal-use release verification

The user approved a self-use release identity: application id
`io.github.fplayer.android`, version code `1`, and version name `1.0.0`. A local
untracked self-signed keystore was generated outside the repository and supplied
only through process environment variables. Under JDK 17:

- `:app:assembleRelease --no-configuration-cache --max-workers=1`: PASS.
- `zipalign -c -P 16 -v 4`: PASS.
- `apksigner verify --verbose --print-certs`: PASS; APK Signature Scheme v2.
- APK SHA-256: `4859F181827FF1235D734E484B277A5EE16676588C277B2145B9F90788481785`.

The keystore password is retained only in a user-private DPAPI-protected local
credential file and is not recorded here. This evidence does not satisfy public
publisher identity, release tag, reproducibility, complete license closure, or
release-device gates.

## Verification record

- `python native-build/tests/test_release_compliance.py`: PASS, 15 tests.
- `python native-build/verify-lock.py`: PASS; locked source and build recipe inventory validated.
- `python native-build/verify-release-inputs.py --root . --verification gradle/verification-metadata.xml --matrix docs/compliance/license-matrix.json --toolchain native-build/out-arm64/evidence/toolchain-manifest.json`: BLOCKED by the matrix status, missing dependency entries, and missing required evidence.
- `python native-build/scan-release-hygiene.py --root . --allow docs/qa/t32-release-compliance-report.md --allow docs/release/release-gate-result.json`: PASS; no forbidden literals or artifacts found.
- Public release remains blocked because tag, publisher identity, complete dependency evidence, and release-device prerequisites are absent; the separately documented personal-use release build is not treated as public release evidence.
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

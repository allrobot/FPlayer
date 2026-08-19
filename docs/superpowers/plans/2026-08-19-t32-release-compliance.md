# T32 Release Compliance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce auditable release-compliance assets for FPlayer, including a complete dependency/license matrix, notices, source attribution, privacy and build documentation, and a release/signing gate that never substitutes a debug key or an untagged tree for production evidence.

**Architecture:** Treat `native-build/sources.lock.json` and the native evidence directory as the authoritative native input, and derive the Gradle side from resolved component reports plus dependency verification metadata. Small deterministic Python tools generate machine-readable inventories and stable notice bundles; human-facing documents reference those artifacts and the existing ADR/security policy. Release configuration reads identity and signing inputs only from untracked local configuration or CI-provided environment, while a final gate validates a clean tag rebuild and records `BLOCKED` when the repository or production identity is unavailable.

**Tech Stack:** Gradle 9.5.0, AGP 9.3.0, Kotlin 2.3.21, Compose BOM 2026.06.00, JDK 17, Android SDK 37, NDK `ndk;29.0.14206865`, Meson 1.11.2, Python 3, PowerShell, Bash, `llvm-readelf`, `zipalign`, `apksigner`, SHA-256 manifests.

**Spec:** `TASKS.md` T32, `docs/decisions/0002-license.md`, `docs/compliance/native-dependencies.md`, `native-build/sources.lock.json`, `AGENTS.md`, and `docs/security/test-device-policy.md`.

## Global Constraints

- FPlayer-owned source remains `GPL-3.0-or-later`; every third-party license and copyright/NOTICE text required by its terms ships with the release assets.
- `native-build/sources.lock.json` is the only authority for native source revisions, archive hashes, roles, options, expected shared libraries, and the reviewed Android system-library allowlist.
- `native-build/out-arm64/evidence/` is evidence input only; a missing, stale, or lock-mismatched manifest fails the matrix rather than being silently regenerated from an unreviewed checkout.
- Gradle dependencies must be resolved from the declared repositories and recorded with SHA-256 dependency verification; dynamic versions, unreviewed repositories, and missing license metadata fail the gate.
- Repository documents contain only relative repository paths and logical identifiers (`TEST_TABLET`, `TEST_OSR_DEVICE`, `TEST_OSR_WIFI`, `FUNSCRIPT_TEST_LIBRARY`); no credentials, real media names, device addresses, usernames, drive letters, serial-port names, or absolute local paths.
- Credentials and signing material enter only through process environment, untracked local configuration, Android Keystore, or CI secret storage; no secret value is written to source, reports, APK metadata, or logs.
- Real media and device data are not copied into the repository. Evidence stores counts, statuses, component IDs, hashes, and sanitized command summaries only.
- A production release requires a repository commit and annotated release tag, a declared application/organization identity, and a non-debug signing key. With no `HEAD`/tag or production key, `RELEASE-REPRO` and `RELEASE-SIGN` remain `BLOCKED`.
- Every new file is first written as the task-specific `.tmp` file, read back, scanned, and atomically renamed. Do not modify unrelated worktree files or create a commit as part of this plan.

## File Map

| File | Responsibility |
| --- | --- |
| `native-build/generate-license-matrix.py` | Deterministically join native lock/evidence and Gradle resolution into a machine-readable license matrix. |
| `native-build/assemble-notices.py` | Validate and assemble stable `NOTICE` and `THIRD_PARTY_LICENSES` bundles from matrix entries and original texts. |
| `native-build/scan-release-hygiene.py` | Fail-closed scan for secrets, absolute paths, device/SMB identifiers, real-media leakage, untracked binaries, and temporary files. |
| `native-build/tests/test_release_compliance.py` | TDD coverage for matrix joins, notice ordering/duplicate rejection, and hygiene allowlist behavior. |
| `docs/compliance/license-matrix.json` | Generated, reviewable dependency/license/linkage inventory. |
| `NOTICE` | Stable top-level attribution index for the application and runtime dependencies. |
| `THIRD_PARTY_LICENSES` | Stable concatenation of required original license/NOTICE texts. |
| `docs/compliance/source-attribution.md` | Ownership, copied/modified code, and reference-only attribution record. |
| `docs/privacy/privacy.md` | Offline-first data handling, credentials, logs, SAF/SMB, and media retention statement. |
| `docs/release/reproducible-build.md` | Pinned toolchain, lock verification, clean-build comparison, archive, and tag rebuild procedure. |
| `docs/release/signing.md` | Release identity, key injection, APK verification, and explicit debug-key prohibition. |
| `docs/release/release-gates.md` | Required PASS/BLOCKED gate definitions and evidence retention/cleanup rules. |
| `gradle/verification-metadata.xml` | Generated SHA-256 verification metadata for all resolved Gradle components/plugins. |
| `app/build.gradle.kts` | Release variant identity/signing wiring that consumes local/CI properties without embedding secrets. |

### Task 0: Establish the compliance inventory contract

**Files:**
- Create: `native-build/tests/test_release_compliance.py`
- Create: `native-build/generate-license-matrix.py`
- Create: `docs/compliance/license-matrix.json`
- Read: `native-build/sources.lock.json`, `native-build/out-arm64/evidence/source-manifest.json`, `native-build/out-arm64/evidence/build-options.json`, `native-build/out-arm64/evidence/elf-dependencies.json`, `native-build/out-arm64/evidence/toolchain-manifest.json`, `gradle/libs.versions.toml`, all `build.gradle.kts` and `settings.gradle.kts`

**Interfaces:**
- `generate_license_matrix(lock_path: Path, native_evidence: Path, gradle_report: Path, output_path: Path) -> None` reads JSON reports and writes stable UTF-8 JSON.
- CLI: `python native-build/generate-license-matrix.py --lock native-build/sources.lock.json --native-evidence native-build/out-arm64/evidence --gradle-report build/reports/t32/gradle-resolution.json --output docs/compliance/license-matrix.json`.
- Matrix entries expose `id`, `source_role`, `revision_or_hash`, `linkage`, `license_expression`, `selected_license`, `notice_paths`, `runtime_scope`, and `evidence_refs`; `status` is `PASS` only when all required fields and evidence agree.

- [ ] **Step 1: Write failing matrix tests**

  In `native-build/tests/test_release_compliance.py`, add tests that pass a small temporary lock/evidence/report fixture to `generate_license_matrix` and assert: every lock source appears exactly once; `reference-only` is retained with `runtime_scope="reference-only"`; every expected `.so` is represented by the ELF evidence; a missing notice path raises `LicenseMatrixError`; and a revision mismatch raises `LicenseMatrixError`.

- [ ] **Step 2: Run the focused tests and verify failure**

  Run `python -m unittest native-build.tests.test_release_compliance -v`. Expected: collection or assertion failures because the generator and error type do not yet exist.

- [ ] **Step 3: Implement the deterministic generator**

  Parse JSON with `object_pairs_hook` disabled, normalize repository-relative paths to POSIX separators, sort entries by `id`, reject duplicate IDs, compare lock revisions/hashes to source and build evidence, and emit a schema version plus generation inputs. Do not include source URLs, host paths, credentials, media names, or raw Gradle cache paths in output.

- [ ] **Step 4: Generate and validate the real matrix**

  First create a sanitized Gradle resolution report with `./gradlew dependencies --configuration releaseRuntimeClasspath --no-configuration-cache` and a companion parser output under `build/reports/t32/` (build output is not a release asset). Run the CLI above. Expected: 20 locked native sources, all expected shared libraries, and every resolved Gradle component either has license metadata or is reported as `BLOCKED`.

- [ ] **Step 5: Re-run tests and hygiene checks**

  Run `python -m unittest native-build.tests.test_release_compliance -v`, `python native-build/verify-lock.py`, and `git diff --check`. Expected: tests pass, lock validation prints `OK`, and no whitespace errors occur.

### Task 1: Generate NOTICE and third-party license assets

**Files:**
- Create: `native-build/assemble-notices.py`
- Create: `NOTICE`
- Create: `THIRD_PARTY_LICENSES`
- Modify: `native-build/tests/test_release_compliance.py`
- Read: `docs/compliance/license-matrix.json`, `native-build/out-arm64/evidence/licenses/`, `LICENSE`

**Interfaces:**
- `assemble_notices(matrix_path: Path, license_root: Path, notice_path: Path, third_party_path: Path) -> None`.
- CLI: `python native-build/assemble-notices.py --matrix docs/compliance/license-matrix.json --license-root native-build/out-arm64/evidence/licenses --project-license LICENSE --notice NOTICE --third-party THIRD_PARTY_LICENSES`.
- `NOTICE` contains the project license pointer, matrix schema/hash, and one stable attribution row per component; `THIRD_PARTY_LICENSES` contains labelled original texts separated by `----- BEGIN NOTICE -----` and `----- END NOTICE -----` markers with component/path metadata lines.

- [ ] **Step 1: Write failing notice tests**

  Assert stable ID ordering, preservation of all notice files listed in the matrix, rejection of duplicate `(id, notice_path)` pairs, rejection of missing or empty license files, and byte-identical output for repeated runs over the same inputs.

- [ ] **Step 2: Run focused tests to verify failure**

  Run `python -m unittest native-build.tests.test_release_compliance.NoticeAssemblyTests -v`. Expected: failures until the assembler exists.

- [ ] **Step 3: Implement fail-closed assembly**

  Resolve only repository-relative license paths below `license_root`, normalize line endings to LF, preserve license text verbatim apart from final newline normalization, sort by component ID then notice path, and write via temporary sibling files. Reference-only entries remain indexed in `NOTICE` but their text is included only when the matrix marks it legally required.

- [ ] **Step 4: Generate assets and verify coverage**

  Run the CLI above, then assert that each non-reference runtime/build source in the lock has at least one corresponding labelled text and that the NDK package notices are present. Expected: no missing notice, duplicate, or unreviewed closure error.

- [ ] **Step 5: Scan and re-run tests**

  Run `python -m unittest native-build.tests.test_release_compliance.NoticeAssemblyTests -v`, `python native-build/scan-release-hygiene.py --root . --include NOTICE --include THIRD_PARTY_LICENSES`. Expected: tests pass and the scan returns no project-leaked sensitive literals.

### Task 2: Document ownership, privacy, and legal boundaries

**Files:**
- Create: `docs/compliance/source-attribution.md`
- Create: `docs/privacy/privacy.md`
- Create: `docs/release/release-gates.md`
- Modify: `native-build/tests/test_release_compliance.py`
- Read: `AGENTS.md`, `docs/decisions/0002-license.md`, `docs/security/test-device-policy.md`, `docs/references/source-map.md`, `SPEC.md`

**Interfaces:**
- Attribution records use `component_id`, `relationship` (`owned`, `copied-or-modified`, `semantic-reference`), `license`, `revision`, `files_or_scope`, and `notice_ref`; every copied/modified entry must point to a source-map logical ID and review evidence.
- Privacy documentation defines `DataClass` categories (media metadata, credentials, playback/recommendation events, diagnostics) and `Retention/Transport` fields; it must state offline default, Keystore protection, no analytics upload, sanitized logs, user-selected SAF/SMB scope, and no repository fixture retention.
- Gate records use `gate_id`, `status` (`PASS`, `FAIL`, `BLOCKED`, `NOT_RUN`), `evidence`, `owner_input`, and `cleanup`; no gate may claim PASS when a required identity or artifact is absent.

- [ ] **Step 1: Write documentation contract tests**

  Add tests that require the documents to contain `GPL-3.0-or-later`, the native matrix reference, Keystore/no-upload/log-redaction statements, the logical test-resource policy, and explicit `BLOCKED` rules for missing tag and production signing identity; reject absolute paths, credentials, network literals, serial-port names, and real-media names.

- [ ] **Step 2: Run tests to verify missing-document failures**

  Run `python -m unittest native-build.tests.test_release_compliance.DocumentationTests -v`. Expected: failures until the three documents exist.

- [ ] **Step 3: Write source attribution**

  Enumerate FPlayer-owned modules under `app/`, `core/`, and `feature/`; reference `REF_*` items only as semantic/observation sources; list native components from the matrix; state that no reference APK/webpage source or assets are shipped. Include a review rule requiring source-map entry, revision, changed files, and license before future copying.

- [ ] **Step 4: Write privacy statement**

  Describe local-only indexing and recommendation state, user-selected SAF and SMB access, Keystore-backed credentials, runtime-only network/device identifiers, redacted diagnostics, deletion/cleanup behavior, and the absence of telemetry or cloud profiling. Use logical identifiers only.

- [ ] **Step 5: Define release gates**

  Define `RELEASE-LICENSE`, `RELEASE-REPRO`, `RELEASE-SIGN`, `RELEASE-HYGIENE`, and `RELEASE-TAG`; specify exact evidence and cleanup for PASS, and record `BLOCKED` when a prerequisite cannot be supplied. Re-run documentation tests and `git diff --check`.

### Task 3: Lock Gradle dependencies and reproducible build inputs

**Files:**
- Create: `gradle/verification-metadata.xml`
- Create: `docs/release/reproducible-build.md`
- Create: `native-build/verify-release-inputs.py`
- Modify: `native-build/tests/test_release_compliance.py`
- Read: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `native-build/verify-reproducible.sh`, native evidence manifests

**Interfaces:**
- `verify_release_inputs(root: Path, verification_metadata: Path, matrix: Path, toolchain_manifest: Path) -> list[str]` returns sorted failures and exits nonzero when nonempty.
- CLI: `python native-build/verify-release-inputs.py --root . --verification gradle/verification-metadata.xml --matrix docs/compliance/license-matrix.json --toolchain native-build/out-arm64/evidence/toolchain-manifest.json`.
- Rebuild document records `SOURCE_DATE_EPOCH`, dependency-lock checks, `native-build/verify-reproducible.sh`, Gradle archive normalization, APK hash/zipalign, and exact output naming without embedding local paths.

- [ ] **Step 1: Write failing input-verification tests**

  Assert rejection of missing verification metadata, unapproved repository IDs, dynamic dependency versions, mismatched native lock IDs, missing toolchain fields, and absent reproducibility evidence; accept the current pinned versions when all evidence is present.

- [ ] **Step 2: Run focused tests to verify failure**

  Run `python -m unittest native-build.tests.test_release_compliance.InputVerificationTests -v`. Expected: failures before the verifier and metadata are added.

- [ ] **Step 3: Generate and review Gradle verification metadata**

  Run `./gradlew --write-verification-metadata sha256 help dependencies --configuration releaseRuntimeClasspath --configuration releaseCompileClasspath --configuration classpath --no-configuration-cache`. Expected: `gradle/verification-metadata.xml` contains checksums for every resolved module/plugin and no ignored artifact rule. Review repository declarations against `settings.gradle.kts`; do not add a new repository merely to make resolution pass.

- [ ] **Step 4: Implement verifier and build procedure**

  Make the verifier compare the matrix lock ID, toolchain versions, verification metadata presence, and evidence filenames. Document two clean native builds using `native-build/verify-reproducible.sh` with fixed `SOURCE_DATE_EPOCH`, followed by `./gradlew clean assembleRelease --no-configuration-cache` twice in isolated output directories, `zipalign -c -P 16 -v 4 build/outputs/apk/release/app-release.apk`, and `sha256sum`/`Get-FileHash`.

- [ ] **Step 5: Execute and classify results**

  Run `python native-build/verify-release-inputs.py --root . --verification gradle/verification-metadata.xml --matrix docs/compliance/license-matrix.json --toolchain native-build/out-arm64/evidence/toolchain-manifest.json`, `python native-build/verify-lock.py`, and the two-build procedure. Expected: input checks pass; reproducibility is `PASS` only if a tag and clean source archive exist. With no `HEAD`/tag, record `RELEASE-REPRO=BLOCKED` and preserve only sanitized diagnostics.

### Task 4: Add release variant identity and signing gate

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `docs/release/signing.md`
- Modify: `native-build/tests/test_release_compliance.py`

**Interfaces:**
- `app/build.gradle.kts` reads `FPLAYER_RELEASE_STORE_FILE`, `FPLAYER_RELEASE_STORE_PASSWORD`, `FPLAYER_RELEASE_KEY_ALIAS`, and `FPLAYER_RELEASE_KEY_PASSWORD` only from Gradle properties/environment at configuration time; missing values make `release` fail with an actionable message rather than falling back to debug signing.
- Release identity inputs are `FPLAYER_APPLICATION_ID`, `FPLAYER_VERSION_CODE`, and `FPLAYER_VERSION_NAME`; defaults remain development-only and cannot satisfy the release gate.
- Signing document specifies `apksigner verify --verbose --print-certs`, `zipalign -c -P 16 -v 4`, SHA-256 calculation, certificate fingerprint recording, and secret cleanup without recording secret values.

- [ ] **Step 1: Write failing signing tests**

  Add a Gradle/configuration test or shell fixture that asserts missing production properties fail, debug signing is rejected for release, invalid version/application identity fails, and a supplied test keystore is never copied into the repository. Add a Python assertion that `signing.md` contains the verification commands and debug-key prohibition.

- [ ] **Step 2: Run the tests to verify failure**

  Run `./gradlew :app:assembleRelease --no-configuration-cache` with no release properties and the focused Python test. Expected: release fails with a missing-production-identity/signing message; no APK is treated as releasable.

- [ ] **Step 3: Wire explicit release signing**

  Configure `signingConfigs.release` from `providers.gradleProperty("FPLAYER_RELEASE_STORE_FILE").orElse(providers.environmentVariable("FPLAYER_RELEASE_STORE_FILE"))` and the analogous exact expressions for `FPLAYER_RELEASE_STORE_PASSWORD`, `FPLAYER_RELEASE_KEY_ALIAS`, and `FPLAYER_RELEASE_KEY_PASSWORD`; validate all four signing values and identity values, and attach it only to `buildTypes.release`. Keep debug signing unchanged for development; add no secret defaults and no keystore file under version control.

- [ ] **Step 4: Verify with an injected local/CI key**

  In a disposable environment outside the repository, inject a non-production test key through the documented properties, run `./gradlew :app:assembleRelease --no-configuration-cache`, then run `zipalign -c -P 16 -v 4 build/outputs/apk/release/app-release.apk` and `apksigner verify --verbose --print-certs build/outputs/apk/release/app-release.apk`. Expected: cryptographic checks pass, but the release gate remains `BLOCKED` unless the key is the approved production identity.

- [ ] **Step 5: Clean and scan**

  Remove only generated APK/report outputs and revoke/discard temporary key material; run the hygiene scanner, `git diff --check`, and `git status --short`. Expected: no secret, key, absolute path, or temporary file enters tracked output.

### Task 5: Run final compliance gate and tag-rebuild audit

**Files:**
- Create: `docs/release/release-gate-result.json`
- Create: `docs/qa/t32-release-compliance-report.md`
- Read: all files from Tasks 0–4, `PROGRESS.md`, T31 report

**Interfaces:**
- `native-build/scan-release-hygiene.py` CLI: `python native-build/scan-release-hygiene.py --root . --allow docs/qa/t32-release-compliance-report.md --allow docs/release/release-gate-result.json` returns exit 0 only when no forbidden literals/artifacts are found.
- Gate result schema: `{ "schema_version": 1, "gates": [{"id": string, "status": "PASS"|"FAIL"|"BLOCKED"|"NOT_RUN", "evidence": [string], "reason": string, "cleanup": [string]}] }`.
- Final report maps each gate to relative evidence, tool versions, command summaries, generated artifact hashes, and unresolved owner decisions; it never includes raw credentials, addresses, media names, or local paths.

- [ ] **Step 1: Write failing hygiene tests**

  Test that the scanner rejects a fixture containing a credential pattern, absolute path, private-key header, device address, serial-port literal, real-media-like filename, untracked APK/native binary, or `.tmp` file; test that documented logical identifiers are allowed.

- [ ] **Step 2: Implement scanner and run it on a fixture**

  Scan tracked text and selected generated metadata, skip build caches only by explicit directory rule, hash but do not print offending file contents, and exit nonzero with category/count diagnostics. Expected: malicious fixture fails; clean repository passes.

- [ ] **Step 3: Execute the complete gate**

  Run `./gradlew testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1`, `./gradlew :app:assembleDebug --no-configuration-cache`, `./gradlew :app:assembleRelease --no-configuration-cache`, `python native-build/verify-lock.py`, `python native-build/verify-release-inputs.py --root . --verification gradle/verification-metadata.xml --matrix docs/compliance/license-matrix.json --toolchain native-build/out-arm64/evidence/toolchain-manifest.json`, `python native-build/scan-release-hygiene.py --root .`, and `git diff --check`. Expected: tests/debug/lock/hygiene pass; release/sign/tag rebuild report the actual environment result.

- [ ] **Step 4: Perform clean tag rebuild when prerequisites exist**

  In a fresh checkout at an annotated release tag, verify the same lock and Gradle metadata, run the native two-build script, build release with approved signing inputs, verify alignment/signature, and compare artifact hashes to the recorded manifest. If there is no commit/tag, no approved application identity, or no production key, set `RELEASE-TAG`, `RELEASE-REPRO`, and/or `RELEASE-SIGN` to `BLOCKED` with the exact missing prerequisite; never manufacture a tag or use the debug key.

- [ ] **Step 5: Write, read back, and clean the result**

  Write `release-gate-result.json` and `t32-release-compliance-report.md` through task-specific temporary files, read them back, validate JSON/schema, scan for sensitive literals, remove only generated reports/cache/APK outputs created by this run, and update `PROGRESS.md` in a separate project checkpoint. Expected: final report distinguishes PASS, FAIL, BLOCKED, and NOT_RUN and contains no sensitive values.

## Self-Review Checklist

- [ ] Every T32 requirement has a task: license matrix, NOTICE/third-party texts, source attribution, privacy, dependency verification, reproducibility, release variant, signing, hygiene, and tag rebuild.
- [ ] Every generated artifact has a deterministic producer, an explicit CLI signature, and a test or gate that fails closed on missing evidence.
- [ ] No task assumes a commit, tag, organization identity, or production key that is absent today; those conditions remain explicit `BLOCKED` gates.
- [ ] All paths are repository-relative and all external resources are logical identifiers; no drive letters, usernames, IPs, credentials, serial ports, or real-media names are present.
- [ ] Final execution must re-read generated files, run `git diff --check`, scan temporary files and sensitive literals, and report cleanup before claiming completion.

# T32 Evidence Index

Date: 2026-08-20

This index stores only repository-relative evidence, stable logical resource identifiers and non-sensitive aggregate results. It does not store external addresses, account names, credentials, physical port names, media names, locators or script content.

The acceptance matrix is the sole current gate-state source. This index is the sole evidence pointer source; it does not replace the matrix or the master plan. Historical execution ledgers under `.superpowers/sdd/` are intentionally not copied here.

| Evidence ID | Matrix IDs | Command or source | Result | Evidence location |
| --- | --- | --- | --- | --- |
| `BASELINE-UNIT-20260819` | all code gates | `gradlew testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1` | 36 XML suites, 199 tests, 0 failures, 0 errors, 1 expected runtime-gated skip; build successful | Generated module `build/test-results/` directories, not tracked |
| `BASELINE-APK-20260819` | `UI-FEED`, `MEDIA-*`, `DEVICE-*` | `gradlew :app:assembleDebug --no-configuration-cache --max-workers=1` | Pass; 56,645,232 bytes; SHA-256 `8185B51C4182465670C9FE9CD0F6FB32E4E2139DFFF45871163EF118CDD035B8` | `app/build/outputs/apk/debug/app-debug.apk`, ignored build artifact |
| `BASELINE-NATIVE-LOCK-20260819` | `RELEASE-LICENSE`, `RELEASE-REPRO` | `python native-build/verify-lock.py` | 20 locked sources and 14 build recipes; pass | `native-build/sources.lock.json`, `native-build/verify-lock.py` |
| `BASELINE-HYGIENE-20260819` | all gates | `git diff --check` and controlled sensitive-value scan | Pass; local execution ledger, build output, credentials and signing material remain ignored | `.gitignore`, Task 0 command output |
| `T31-AUTOMATION` | `DEVICE-*`, `SMB-REAL` | Prior performance and fault-injection acceptance | TCP/UDP/WebSocket loopback and anonymous cross-host SMB passed; authenticated external SMB and physical device remained open | `docs/qa/t31-report.md` |
| `T31-TABLET` | `UI-FEED`, `MEDIA-*` | Prior API 33 background and lifecycle acceptance | Native playback, screen-off loop, notification permission, rotation, trim and cleanup passed with neutral fixture | `docs/qa/t31-report.md` |
| `T32-PLAN-MASTER` | all gates | Canonical T32 task plan | Task 0–7 definitions, dependencies, safety rules and status semantics | `docs/superpowers/plans/2026-08-19-t32-master-acceptance.md` |
| `T32-SMB-REAL-20260820` | `SMB-REAL` | Runtime-gated SMB acceptance review | Pass for both authorized roots: root A `28/28/28` and root B `8/38/8` media/total-scripts/matched-scripts; genuine socket interruption, generation-3 recovery and credential cleanup passed. The separate tablet instrumentation gate was not run; later ADB visibility is not acceptance evidence. | `docs/qa/t32-device-media-acceptance.md` |
| `T32-UI-INTEGRATION-20260820` | `UI-FEED` | JDK 17 focused app/core tests and debug assembly | Surface handoff, indexed-script matching, bounded locator reader, thumbnail owner/cache-root tests and debug assembly passed; approved tablet viewport flow was not run, so physical evidence remains BLOCKED | `0be0df5`, `app/src/test/java/io/github/fplayer/android/IndexedScriptResolverTest.kt`, `app/src/test/java/io/github/fplayer/android/PlaybackSurfaceHandoffTest.kt` |
| `T32-RELEASE-HYGIENE-20260820` | `RELEASE-HYGIENE` | Python unittest and hygiene CLI | 15 release-compliance tests passed; fresh scan returned PASS without printing matched text or paths | `2cdcbcb`, `ad74db0`, `native-build/scan-release-hygiene.py` |
| `T32-INTEGRATION-20260820` | all gates | JDK 17 full regression, debug/release assembly, native lock, hygiene and read-only ADB preflight | 51 suites/288 tests passed with 1 expected runtime-gated skip; debug APK passed at 56,624,071 bytes and SHA-256 `82A2E9552BFF75C405CF83B5D27BB1C1F5C2C90A685503BE7FC290DC274D997C`; release failed closed because production identity/signing inputs are absent; native lock, 15 release-compliance tests, hygiene scan and diff check passed. One online `TEST_TABLET` was visible, but connectivity alone changes no physical gate. | Generated module `build/test-results/` directories and `app/build/outputs/apk/debug/app-debug.apk`, both ignored; canonical result in this row |
| `T32-HANDOFF-20260820` | all gates | Task 7 canonical-document and handoff audit | Plan Task 5/7 status, matrix task mapping, evidence pointers and `PROGRESS.md` next-session entry are synchronized; required detail-report links exist; placeholder/sensitive-value scan and final diff checks passed. JDK 17 release rerun reached `validateReleaseConfiguration` and remained fail-closed for the seven missing production identity/signing inputs. External, physical and owner-provided prerequisites remain `BLOCKED` and no gate state was promoted. | `docs/superpowers/plans/2026-08-19-t32-master-acceptance.md`, `docs/qa/t32-acceptance-matrix.md`, `docs/qa/t32-evidence-index.md`, `PROGRESS.md`, `app/build.gradle.kts` |
| `T32-PERSONAL-RELEASE-20260820` | `RELEASE-SIGN` | User-approved personal-use identity and local signing verification | Pass: `io.github.fplayer.android`, versionCode `1`, versionName `1.0.0`; `:app:assembleRelease` passed under JDK 17; zipalign check passed; `apksigner verify` passed with v2; SHA-256 `4859F181827FF1235D734E484B277A5EE16676588C277B2145B9F90788481785`; no keystore/password material stored in repository | `app/build/outputs/apk/release/app-release.apk` (ignored), `app/build.gradle.kts`, `docs/release/signing.md` |

## Count Authority

Task 5 fresh XML aggregation reports 51 suites and 288 tests, with 0 failures, 0 errors and 1 expected runtime-gated skip. It supersedes the Task 0/T31 36-suite/199-test baseline for current T32 regression decisions while preserving that older result as historical evidence.

## Task Mapping

| Task | Implementation/evidence state | External/final gate state | Primary evidence |
| --- | --- | --- | --- |
| 0 | `COMPLETE` for baseline assets and redaction rules | `COMPLETE` for the documentation asset | `docs/qa/t32-acceptance-matrix.md`, `docs/qa/t32-evidence-index.md` |
| 1 | `COMPLETE` for service/integration boundaries and synthetic tests | `BLOCKED` for approved tablet viewport/Surface evidence | `0be0df5`, `954f516`, `docs/qa/t32-ui-feed-comparison.md` |
| 2 | `COMPLETE` for deterministic estimator/scheduler/range/settings behavior | `BLOCKED` for protocol-specific and real-resource evidence | `d1b47ea`, `docs/qa/t32-script-latency.md` |
| 3 | `PARTIAL`; loopback and authenticated SMB host acceptance passed | `BLOCKED` for SAF and physical WS/BLE/SPP/USB gates | `docs/qa/t32-device-media-acceptance.md` |
| 4 | `PARTIAL`; hygiene and fail-closed gate reporting passed | `BLOCKED` for complete license/repro/tag/sign/device inputs | `docs/qa/t32-release-compliance-report.md`, `docs/release/release-gate-result.json` |
| 5 | `COMPLETE` for fresh cross-system consolidation | `BLOCKED` where a row still requires external, physical or owner-provided evidence | `T32-INTEGRATION-20260820`, `docs/qa/t32-acceptance-matrix.md` |
| 6 | `BLOCKED` | `BLOCKED` | `docs/release/`, `docs/security/test-device-policy.md` |
| 7 | `COMPLETE`; canonical handoff artifacts are synchronized | `PENDING` for still-blocked external and release gates | `T32-HANDOFF-20260820`, `PROGRESS.md` |

## Evidence Rules

- A `PASS` requires direct evidence for the whole matrix row.
- Synthetic, injected-backend or loopback results are prerequisites, not substitutes for a row that explicitly requires a real source or target.
- Runtime credentials are cleared after use and never copied into this index.
- Real media evidence is limited to aggregate counts, match rates, state transitions and stable non-reversible summaries.
- Generated test XML and APK files remain ignored; only their aggregate result and cryptographic hash are recorded.

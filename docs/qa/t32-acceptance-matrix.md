# T32 Acceptance Matrix

Date: 2026-08-20

Allowed states: `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN`.

This matrix is the sole current T32 gate index. A gate changes state only when its stated expectation has direct evidence. Loopback or synthetic evidence does not replace a required physical-source result. Task completion and implementation progress are recorded in the master plan; detail reports do not override this table.

| ID | State | Prerequisite | Acceptance input | Expected result | Evidence | Current blocker or failure | Cleanup |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `UI-FEED` | `BLOCKED` | Debug APK and synthetic indexed media | Current Feed, grid, overlay and four viewports | One active video Surface, vertical settled paging, progress or heatmap, no overlap or clipping | `0be0df5`, `app/src/test/java/io/github/fplayer/android/PlaybackSurfaceHandoffTest.kt`, `T32-INTEGRATION-20260820` | Service Surface/script/thumbnail wiring and the fresh debug build pass; ADB availability alone does not prove the approved tablet viewport/Surface flow, so the physical gate remains BLOCKED | Remove only synthetic screenshots and app-owned fixture cache |
| `SCRIPT-LATENCY` | `BLOCKED` | Deterministic media clock and recording device sink | Multi-axis and single-axis synthetic scripts | Signed manual offset, response-based automatic estimate, per-axis output range, generation-safe scheduler stream | `docs/qa/t32-script-latency.md`, `d1b47ea`, `T32-INTEGRATION-20260820` | Deterministic implementation and fresh regression tests pass; protocol-specific response samples, physical transports and real media remain unverified | Stop scheduler and clear recorded frames |
| `DEVICE-WS` | `BLOCKED` | Loopback green, unique runtime profile and physical safety gate | `TEST_OSR_WIFI` WebSocket endpoint | Connect, identify profile, bounded single-axis frame, disconnect and emergency stop without stale replay | `docs/qa/t31-report.md`, `docs/qa/t32-device-media-acceptance.md` | Loopback passed; physical endpoint/profile has not been uniquely accepted | Stop output, disconnect transport, restore prior network, force-stop project package |
| `DEVICE-BLE` | `BLOCKED` | Loopback green, unique advertised service and physical safety gate | `TEST_OSR_DEVICE` BLE profile | Permission, discovery, GATT write or response, disconnect and emergency stop pass | `docs/qa/t31-report.md`, `docs/qa/t32-device-media-acceptance.md` | No unique BLE candidate/profile has been accepted | Stop output, close GATT, revoke temporary permission, force-stop project package |
| `DEVICE-SPP` | `BLOCKED` | Loopback green, paired unique RFCOMM target and physical safety gate | `TEST_OSR_DEVICE` SPP profile | Connect after discovery cancellation, bounded frame, disconnect and emergency stop pass | `docs/qa/t31-report.md`, `docs/qa/t32-device-media-acceptance.md` | No unique paired SPP target/profile has been accepted | Stop output, close socket, revoke temporary permission, force-stop project package |
| `DEVICE-USB` | `BLOCKED` | Loopback green, unique USB descriptor and physical safety gate | `TEST_OSR_SERIAL` USB/OTG profile | Permission, serial parameters, bounded frame, detach and emergency stop pass | `docs/qa/t31-report.md`, `docs/qa/t32-device-media-acceptance.md` | A host-side port name alone is not an accepted Android USB/OTG profile | Stop output, close port, release USB permission, force-stop project package |
| `MEDIA-SD-MULTIAXIS` | `BLOCKED` | User-selected read-only SAF tree | `TEST_TABLET_MEDIA_MULTIAXIS` | App scan, video prepare, axis match, seek, speed, EOF and recording-sink playback pass | `docs/qa/t32-device-media-acceptance.md` | Source exists, but the app has not received and consumed its SAF grant | Stop playback, retain only the user-approved SAF grant, clear app fixture cache |
| `MEDIA-SD-SINGLEAXIS` | `BLOCKED` | User-selected read-only SAF tree | `TEST_TABLET_MEDIA_SINGLEAXIS` | App scan, single-axis match, missing-axis fallback, seek, speed, EOF and recording-sink playback pass | `docs/qa/t32-device-media-acceptance.md` | Source exists, but the app has not received and consumed its SAF grant | Stop playback, retain only the user-approved SAF grant, clear app fixture cache |
| `SMB-REAL` | `PASS` | Runtime-only authenticated source configuration | `FUNSCRIPT_TEST_LIBRARY` | Both authorized roots: authenticated metadata scan, separate total/matched script counts, genuine disconnect, recovery and old-generation retention pass without secret logging | `docs/qa/t32-device-media-acceptance.md` | Host roots A/B passed; the separate tablet instrumentation gate was not run, and later ADB visibility does not substitute for that evidence | Close sessions, clear credential buffers, remove only app-owned cache and temporary rules |
| `RELEASE-LICENSE` | `BLOCKED` | Final native and Gradle dependency closure | Locked dependency set | Complete license matrix, root NOTICE, third-party license texts and source attribution | `docs/release/release-gate-result.json`, `docs/qa/t32-release-compliance-report.md` | Root release notices and the complete Gradle/native matrix are not present | Remove generated staging files after verified atomic replacement |
| `RELEASE-HYGIENE` | `PASS` | Tracked tree and selected generated metadata | Fail-closed sensitive/artifact scan | No credentials, endpoints, media names, absolute paths or release binaries | `native-build/scan-release-hygiene.py`, `native-build/tests/test_release_compliance.py` | None in the fresh scan; generated JNI directories are explicit build-input exclusions | No task-specific temporary files retained |
| `RELEASE-REPRO` | `BLOCKED` | Clean tracked baseline, dependency verification and release tag | Release source archive | Two clean builds are reproducible and the release artifact is rebuildable from its tag | `docs/qa/t32-release-compliance-report.md`, `docs/release/reproducible-build.md` | No release tag or completed reproducibility evidence exists | Remove isolated build worktrees only after hashes are recorded |
| `RELEASE-SIGN` | `BLOCKED` | Final package identity and production signing secret | Release APK | Correct identity, zip alignment, production signature verification and recorded SHA-256 | `docs/qa/t32-release-compliance-report.md`, `docs/release/signing.md` | Final application identity and production signing material are not supplied | Clear signing environment and delete untracked signing staging files |
| `RELEASE-TAG` | `BLOCKED` | Annotated release tag and clean checkout | Tag-rebuild source | The exact tagged source is available for audit and rebuild | `docs/qa/t32-release-compliance-report.md`, `docs/release/release-gates.md` | No annotated release tag exists | Do not create a tag during a blocked gate |
| `RELEASE-DEVICE` | `BLOCKED` | Approved production device/profile and physical safety conditions | Release-device run | Device/profile acceptance and cleanup audit pass without unbounded motion | `docs/qa/t32-release-compliance-report.md`, `docs/security/test-device-policy.md` | Approved profile and physical release-device run are absent | No device session or motion output was initiated |

## Fresh Task 5 Consolidation

- JDK 17 `testDebugUnitTest --rerun-tasks --max-workers=1 --no-configuration-cache`: 51 XML suites, 288 tests, 0 failures, 0 errors, 1 expected runtime-gated skip; 247 tasks executed.
- `:app:assembleDebug --no-configuration-cache`: pass; APK 56,624,071 bytes, SHA-256 `82A2E9552BFF75C405CF83B5D27BB1C1F5C2C90A685503BE7FC290DC274D997C`.
- `:app:assembleRelease --no-configuration-cache`: blocked at `validateReleaseConfiguration` because explicit production identity and signing inputs are absent; no release result is inferred from debug.
- Native lock: 20 sources and 14 build recipes; pass. Release-compliance tests: 15 passed. Fail-closed hygiene scan and `git diff --check`: pass.
- Exactly one online `TEST_TABLET` was visible through a read-only ADB preflight; this is environment availability only and changes no viewport, SAF, protocol-response or physical-device gate.

## Task Status Mapping

The master plan owns task definitions; this matrix owns gate states. The current mapping is:

| Task | Implementation/evidence | External/final gate |
| --- | --- | --- |
| 0 | `COMPLETE` | `COMPLETE` for baseline assets |
| 1 | `COMPLETE` for integration and synthetic tests | `BLOCKED` for tablet viewport/Surface evidence |
| 2 | `COMPLETE` for deterministic timing, range and lifecycle behavior | `BLOCKED` for protocol-specific and real-resource evidence |
| 3 | `PARTIAL`; loopback and authenticated SMB host pass | `BLOCKED` for SAF and physical transports |
| 4 | `PARTIAL`; hygiene pass and fail-closed reports | `BLOCKED` for license, tag, reproducibility, signing and release device |
| 5 | `COMPLETE` for fresh regression, debug build, release fail-closed, lock and hygiene consolidation | `BLOCKED` where the matrix still requires external, physical or owner-provided evidence |
| 6 | `BLOCKED` | `BLOCKED` |
| 7 | `PARTIAL`; public handoff synchronization in progress | `PENDING` |

## Physical Motion Gate

No physical motion frame may be sent until the target is unique, the mechanism is unloaded and visible, immediate power removal is available, loopback is green, and the first action is one axis near center with each command no longer than 500 ms.

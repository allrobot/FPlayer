# T32 Acceptance Matrix

Date: 2026-08-19

Allowed states: `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN`.

This matrix is the stable T32 gate index. A gate changes state only when its stated expectation has direct evidence. Loopback or synthetic evidence does not replace a required physical-source result.

| ID | State | Prerequisite | Acceptance input | Expected result | Evidence | Current blocker or failure | Cleanup |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `UI-FEED` | `FAIL` | Debug APK and synthetic indexed media | Current Feed, grid, overlay and four viewports | One active video Surface, vertical settled paging, progress or heatmap, no overlap or clipping | `docs/superpowers/plans/2026-08-19-t32-ui-feed-comparison.md` | Current main Feed still renders the empty placeholder and does not bind the existing paging/progress state machines to a real Surface | Remove only synthetic screenshots and app-owned fixture cache |
| `SCRIPT-LATENCY` | `FAIL` | Deterministic media clock and recording device sink | Multi-axis and single-axis synthetic scripts | Signed manual offset, response-based automatic estimate, per-axis output range, generation-safe scheduler stream | `docs/superpowers/plans/2026-08-19-t32-funscript-latency.md` | Automatic latency and script-level per-axis ranges are not implemented | Stop scheduler and clear recorded frames |
| `DEVICE-WS` | `BLOCKED` | Loopback green, unique runtime profile and physical safety gate | `TEST_OSR_WIFI` WebSocket endpoint | Connect, identify profile, bounded single-axis frame, disconnect and emergency stop without stale replay | `docs/qa/t31-report.md`, `docs/superpowers/plans/2026-08-19-t32-device-media-acceptance.md` | Loopback passed; physical endpoint/profile has not been uniquely accepted | Stop output, disconnect transport, restore prior network, force-stop project package |
| `DEVICE-BLE` | `BLOCKED` | Loopback green, unique advertised service and physical safety gate | `TEST_OSR_DEVICE` BLE profile | Permission, discovery, GATT write or response, disconnect and emergency stop pass | `docs/qa/t31-report.md`, `docs/superpowers/plans/2026-08-19-t32-device-media-acceptance.md` | No unique BLE candidate/profile has been accepted | Stop output, close GATT, revoke temporary permission, force-stop project package |
| `DEVICE-SPP` | `BLOCKED` | Loopback green, paired unique RFCOMM target and physical safety gate | `TEST_OSR_DEVICE` SPP profile | Connect after discovery cancellation, bounded frame, disconnect and emergency stop pass | `docs/qa/t31-report.md`, `docs/superpowers/plans/2026-08-19-t32-device-media-acceptance.md` | No unique paired SPP target/profile has been accepted | Stop output, close socket, revoke temporary permission, force-stop project package |
| `DEVICE-USB` | `BLOCKED` | Loopback green, unique USB descriptor and physical safety gate | `TEST_OSR_SERIAL` USB/OTG profile | Permission, serial parameters, bounded frame, detach and emergency stop pass | `docs/qa/t31-report.md`, `docs/superpowers/plans/2026-08-19-t32-device-media-acceptance.md` | A host-side port name alone is not an accepted Android USB/OTG profile | Stop output, close port, release USB permission, force-stop project package |
| `MEDIA-SD-MULTIAXIS` | `BLOCKED` | User-selected read-only SAF tree | `TEST_TABLET_MEDIA_MULTIAXIS` | App scan, video prepare, axis match, seek, speed, EOF and recording-sink playback pass | `docs/superpowers/plans/2026-08-19-t32-device-media-acceptance.md` | Source exists, but the app has not received and consumed its SAF grant | Stop playback, retain only the user-approved SAF grant, clear app fixture cache |
| `MEDIA-SD-SINGLEAXIS` | `BLOCKED` | User-selected read-only SAF tree | `TEST_TABLET_MEDIA_SINGLEAXIS` | App scan, single-axis match, missing-axis fallback, seek, speed, EOF and recording-sink playback pass | `docs/superpowers/plans/2026-08-19-t32-device-media-acceptance.md` | Source exists, but the app has not received and consumed its SAF grant | Stop playback, retain only the user-approved SAF grant, clear app fixture cache |
| `SMB-REAL` | `PASS` | Runtime-only authenticated source configuration | `FUNSCRIPT_TEST_LIBRARY` | Metadata scan, script match, disconnect, recovery and old-generation retention pass without secret logging | `docs/qa/t32-device-media-acceptance.md`, `docs/superpowers/plans/2026-08-19-t32-device-media-acceptance.md` | None for host acceptance; tablet instrumentation remains blocked by no ADB-visible `TEST_TABLET` | Close sessions, clear credential buffers, remove only app-owned cache and temporary rules |
| `RELEASE-LICENSE` | `FAIL` | Final native and Gradle dependency closure | Locked dependency set | Complete license matrix, root NOTICE, third-party license texts and source attribution | `docs/compliance/native-dependencies.md`, `docs/superpowers/plans/2026-08-19-t32-release-compliance.md` | Root release notices and the complete Gradle/native matrix are not present | Remove generated staging files after verified atomic replacement |
| `RELEASE-REPRO` | `BLOCKED` | Clean tracked baseline, dependency verification and release tag | Release source archive | Two clean builds are reproducible and the release artifact is rebuildable from its tag | `docs/superpowers/plans/2026-08-19-t32-release-compliance.md` | No release tag or completed reproducibility evidence exists | Remove isolated build worktrees only after hashes are recorded |
| `RELEASE-SIGN` | `BLOCKED` | Final package identity and production signing secret | Release APK | Correct identity, zip alignment, production signature verification and recorded SHA-256 | `docs/superpowers/plans/2026-08-19-t32-release-compliance.md` | Final application identity and production signing material are not supplied | Clear signing environment and delete untracked signing staging files |

## Baseline

- Fresh `testDebugUnitTest --rerun-tasks --max-workers=1 --no-configuration-cache`: 36 XML suites, 199 tests, 0 failures, 0 errors, 1 expected runtime-gated skip.
- Fresh `:app:assembleDebug --no-configuration-cache`: pass.
- Debug APK: 56,645,232 bytes; SHA-256 `8185B51C4182465670C9FE9CD0F6FB32E4E2139DFFF45871163EF118CDD035B8`.
- Native lock: 20 sources and 14 build recipes; pass.
- `git diff --check`: pass.

## Physical Motion Gate

No physical motion frame may be sent until the target is unique, the mechanism is unloaded and visible, immediate power removal is available, loopback is green, and the first action is one axis near center with each command no longer than 500 ms.

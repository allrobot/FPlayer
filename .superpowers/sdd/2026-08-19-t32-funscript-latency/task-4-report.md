# Task 4 Report: Response Samples, Scheduler Lifecycle, and Acceptance Evidence

## Status

Implemented and verified in the Task 4 worktree. Real-device and real-media gates remain blocked by the sibling plan as required.

## Changes

- Added `TransportTimingListener` and an explicit `DeviceTransport.setTimingListener` hook. The callback accepts validated monotonic pairs only.
- Added client-originated WebSocket control probes with an 8-byte correlated id, monotonic send timestamp, pong matching, bounded pending probes, and reconnect/close clearing. No TCP/UDP/BLE/SPP/USB RTT is inferred without a backend-provided correlated echo.
- Extended `DeviceSession` with serialized estimator access, redacted timing diagnostics, timing forwarding, connection-loss reset, controller safety handling, and lifecycle callbacks.
- Added `SerializedScriptPlaybackCoordinator` in `core:script` and a process-scoped `DevicePlaybackCoordinator` registry in `feature:device`. The route registers the session/controller; the service uses the same coordinator and can replace its clock with the real player snapshot clock.
- Added service tick scheduling and explicit binder APIs for script loading/manual axis submission. Pause, seek, speed, loop, stop, device loss, service destruction, and coordinator clear paths stop/advance before later output.
- Added focused script/device integration tests and redacted `docs/qa/t32-script-latency.md`.

## TDD and Verification

The first focused test run was RED on the missing serialized coordinator contract. Subsequent GREEN runs passed the focused integration tests.

Commands run with the required JDK 17:

```text
gradlew :core:device:testDebugUnitTest --tests '*NetworkDeviceTransportsTest' :core:script:testDebugUnitTest --tests '*ScriptLatencyRecordingIntegrationTest' :feature:device:testDebugUnitTest --tests '*ManualAxisOutputIntegrationTest' --no-configuration-cache --rerun-tasks --max-workers=1
PASS

gradlew :core:script:testDebugUnitTest :core:device:testDebugUnitTest :feature:device:testDebugUnitTest :feature:feed:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
PASS

gradlew :app:assembleDebug --no-configuration-cache --rerun-tasks --max-workers=1
PASS

git diff --check
PASS
```

The redacted evidence document scan passed; it contains no credentials, locators, serial names, media names, raw frames, or local paths.

## Concerns and Gates

- Physical WS/BLE/SPP/USB loopback, authorized media, and hardware safety remain `BLOCKED / NOT RUN`; no real transport or media was opened.
- Automatic RTT remains `UNMEASURED` for transports without an explicit correlated response. Write completion, queue age, GATT acknowledgement, UI timing, wall clock, and arbitrary inbound bytes are intentionally excluded.
- The service binder now exposes the scheduler entry points, but media-to-script matching is still owned by the sibling playback integration plan; absent a loaded `ScriptBundle`, no targets are produced.

## Round 1 Review Fixes

- Routed session emergency stop, safe-test submit/drain, connection-loss stop/disconnect, and controller release through the process-scoped coordinator's serialized executor. Sessions without a coordinator use one session-local serialized controller boundary.
- Detached the session controller before serialized teardown so queued work is identity-checked and rejected after disconnect. Added coverage that records no post-disconnect normal output.
- Added a blocking controller regression test proving scheduler submit and session controller operations never overlap (`maximumConcurrentOperations=1`).
- Moved WebSocket timing probes to a fixed-rate scheduler independent of inbound reads. A connection epoch prevents a scheduled probe from repopulating pending state after close/reconnect.
- Added a chatty loopback test that continuously sends inbound text frames while the client still produces a correlated ping/pong timing sample.
- Made session close deliver `onConnectionLost` once for the active connection and `onSessionClosed` once, without the coordinator translating session close into a second connection-loss callback.

Final focused command run with JDK 17:

```text
JAVA_HOME=<JDK17> gradlew :core:device:testDebugUnitTest --tests '*NetworkDeviceTransportsTest' :core:script:testDebugUnitTest --tests '*ScriptLatencyRecordingIntegrationTest' :feature:device:testDebugUnitTest --tests '*DevicePlaybackCoordinatorTest' --tests '*DeviceSessionTest' --tests '*ManualAxisOutputIntegrationTest' --rerun-tasks --no-daemon --max-workers=1
BUILD SUCCESSFUL in 1m 5s
65 actionable tasks: 65 executed
```

Focused results:

```text
NetworkDeviceTransportsTest: 12 tests, 0 failures, 0 errors, 0 skipped
ScriptLatencyRecordingIntegrationTest: 4 tests, 0 failures, 0 errors, 0 skipped
DevicePlaybackCoordinatorTest: 1 test, 0 failures, 0 errors, 0 skipped
DeviceSessionTest: 9 tests, 0 failures, 0 errors, 0 skipped
ManualAxisOutputIntegrationTest: 2 tests, 0 failures, 0 errors, 0 skipped
git diff --check: PASS
```

Independent re-review follow-up:

- Removed the coordinator `onConnectionLost` early return that could skip scheduler clearing while a controller was still attached. Direct lifecycle callback coverage now observes one `CONNECTION_LOST` stop.
- Added once-per-connection notification state and remote-loss transport detachment/close. Remote loss followed by session close emits `onConnectionLost` exactly once.

Re-review focused command (JDK 17) passed with 30 tests and zero failures/errors/skips:

```text
JAVA_HOME=<JDK17> gradlew :core:device:testDebugUnitTest --tests '*NetworkDeviceTransportsTest' :core:script:testDebugUnitTest --tests '*ScriptLatencyRecordingIntegrationTest' :feature:device:testDebugUnitTest --tests '*DevicePlaybackCoordinatorTest' --tests '*DeviceSessionTest' --tests '*ManualAxisOutputIntegrationTest' --rerun-tasks --no-daemon --max-workers=1
BUILD SUCCESSFUL in 55s
NetworkDeviceTransportsTest: 12; ScriptLatencyRecordingIntegrationTest: 4; DevicePlaybackCoordinatorTest: 2; DeviceSessionTest: 10; ManualAxisOutputIntegrationTest: 2
```

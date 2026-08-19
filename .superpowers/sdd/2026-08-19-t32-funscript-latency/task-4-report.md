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

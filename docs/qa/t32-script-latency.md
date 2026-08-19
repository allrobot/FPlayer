# T32 Script Latency QA

## Scope

This record covers deterministic estimator, scheduler, device-session, and service lifecycle behavior. Tests use synthetic clocks, fake controllers/transports, and an in-process WebSocket loopback only. No physical transport, real media, SMB source, or hardware was opened.

## Evidence

| Gate | Result | Evidence |
| --- | --- | --- |
| Correlated WebSocket ping/pong | PASS | A client-generated 8-byte probe id is matched to the pong id; the callback receives only the monotonic send/receive pair. The focused transport test records `100 -> 220` and produces one sample. |
| No correlated response | PASS | A fresh estimator remains `UNMEASURED` with zero automatic offset. TCP, UDP, BLE, SPP, and USB do not infer RTT from writes, queue time, acknowledgements, UI timing, wall clock, or arbitrary inbound bytes. |
| Estimator and scheduler recording | PASS | Correlated sample becomes `MEASURED`; the recording contains state, sample count, signed offset, generation, media time, axis, position, and duration. Media time stays based on the player snapshot. |
| Serialized lifecycle | PASS | Coordinator operations run on one serial executor. Pause, seek, speed, loop, connection loss, and clear stop active output and advance generation. Repeated clear is idempotent. |
| Session disconnect | PASS | Session clears the estimator, calls controller connection-loss handling, detaches transport, and emits redacted latency diagnostics. |
| Service lifecycle | PASS | Service ticks from the player snapshot loop and clears before pause, stop, discontinuity, task/service destruction, and device loss. Binder exposes explicit script load/manual submission entry points. |
| Range and safety path | PASS | Manual and scripted targets use the scheduler and then `DeviceSafetyController`; no direct transport frame path is exposed to UI. |
| Real-resource gates | BLOCKED / NOT RUN | The sibling plan must supply physical WS/BLE/SPP/USB loopback and authorized media evidence before any real-device claim. |
| Final deterministic verification | PASS | Clean, single-worker JDK 17 execution re-ran the scheduler, estimator, range, settings, device-session, and service tests. Expected recordings assert sorted axis order, targets, durations, generations, media timestamps, signed effective offsets, and stop sequences. The automatic-offset test changes script position while preserving the media timestamp. |
| Queue and lifecycle cleanup | PASS | Safety/controller tests cover clamped `0..100` targets, script/device-range intersection, stale-generation rejection, and idempotent pause, disconnect, and release stops. |
| Protocol-specific estimate | BLOCKED | Only the synthetic correlated WebSocket ping/pong hook has evidence. TCP, UDP, BLE, SPP, and USB require a validated transport response sample; write completion, acknowledgements, and arbitrary inbound bytes remain invalid substitutes. |
| Physical transport and real media | NOT RUN / BLOCKED | This verification did not access devices or media. Physical WS/BLE/SPP/USB and authorized real-media claims remain gated on sibling-plan evidence under the test-device policy. |

## Commands

All commands used the required JDK 17 environment and single-worker Gradle execution.

```text
gradlew :core:script:testDebugUnitTest :core:device:testDebugUnitTest :feature:device:testDebugUnitTest --tests '*ScriptLatencyRecordingIntegrationTest' --tests '*ManualAxisOutputIntegrationTest' --no-configuration-cache --rerun-tasks --max-workers=1
PASS

gradlew :core:script:testDebugUnitTest :core:device:testDebugUnitTest :feature:device:testDebugUnitTest :feature:feed:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
PASS

gradlew :app:assembleDebug --no-configuration-cache --max-workers=1
PASS

git diff --check
PASS

gradlew :core:script:testDebugUnitTest :core:device:testDebugUnitTest :feature:device:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1 --no-daemon
PASS (BUILD SUCCESSFUL; 196 actionable tasks executed)

gradlew :app:assembleDebug --no-configuration-cache --max-workers=1 --no-daemon
PASS (BUILD SUCCESSFUL; 221 actionable tasks, 7 executed)

Get-ChildItem -Recurse -File | Where-Object { $_.Name -like '*.tmp.*' } | Select-Object -ExpandProperty FullName
PASS (no matching artifacts)
```

The first clean test attempt encountered an OS file lock left by competing Gradle daemons before any test task ran. After `gradlew --stop`, the documented command completed successfully with a single-use daemon; this is an environment cleanup event, not a product test failure.

## Redaction

Evidence contains only state names, counts, signed offsets, synthetic axis IDs, generations, media times, durations, and stop reasons. It contains no credentials, network locators, serial names, media names, raw frames, or local filesystem paths.

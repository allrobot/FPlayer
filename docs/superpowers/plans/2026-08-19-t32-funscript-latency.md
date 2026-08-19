# T32 Funscript Latency, Axis Limits, and Manual Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add deterministic Funscript timing compensation, user and device axis limits, and safe real-time manual axis control without bypassing the media-clock scheduler or `DeviceSafetyController`.

**Architecture:** Keep parsing, matching, latency estimation, range composition, and scheduling as pure Kotlin contracts. The scheduler samples `PlayerSnapshot.positionMs`, combines the existing signed `ScriptSchedulerConfig.offsetMs` with a transport-provided automatic estimate, applies script-level ranges before submitting `DeviceTarget`, and leaves device-profile clamping, reversal, rate limiting, queue bounds, and emergency stop to `DeviceSafetyController`. UI state in `feature/settings` exposes the controls and diagnostics; the feed/service only supplies playback snapshots and receives recorded frames.

**Tech Stack:** Kotlin/JVM unit tests, Android library modules, JUnit 4, existing `ScriptInterpolator`, `MediaClockScriptScheduler`, `DeviceController`, `DeviceSafetyController`, and Compose settings wiring.

**Spec:** `TASKS.md` T04-T08 and T32 Task 2; `SPEC.md` sections 2.1, 2.3, 2.4, and 3; `docs/architecture/funscript-format.md`; `docs/architecture/script-scheduler.md`; `docs/architecture/device-layer.md`; `docs/security/test-device-policy.md`.

## Global Constraints

- The media clock is `PlayerSnapshot.positionMs`; no scheduler or estimator may advance from wall-clock/UI frame time.
- Positive offset means script delay and negative offset means script advance: `scriptTime = mappedMediaTime - offsetMs`.
- Default user script range is `0..100` for every axis; the effective output must be the intersection of the script range and the device profile range.
- Every target, including manual control, passes through `DeviceController`; UI and transport code may not write TCode directly.
- Automatic compensation is enabled only with validated response samples, uses a robust median RTT-derived estimate, has bounded magnitude and expiry, and reports `UNMEASURED` when no response is available.
- Real WS/BLE/SPP/USB and real media are sibling-plan gates. This plan proves loopback, synthetic clocks, recording sinks, and redacted diagnostics before any real-device action.
- Do not write credentials, addresses, SSIDs, COM names, physical paths, media names, script contents, screenshots, or raw high-frequency frames to the repository.
- New plan output is written to a same-directory temporary file, read back, checked for sensitive values and trailing whitespace, then atomically renamed; source code and `PROGRESS.md` are outside this plan's scope.

## File Map

Files are listed before task execution so an implementer can preserve module boundaries.

- Modify `core/script/src/main/java/io/github/fplayer/core/script/ScriptContracts.kt` only if the new latency/range data contracts do not fit existing scheduler contracts.
- Modify `core/script/src/main/java/io/github/fplayer/core/script/MediaClockScriptScheduler.kt` to consume a deterministic offset provider and apply per-axis script ranges before submission.
- Create `core/script/src/main/java/io/github/fplayer/core/script/LatencyCompensation.kt` for samples, estimator, effective offset, and expiry diagnostics.
- Create `core/script/src/main/java/io/github/fplayer/core/script/ScriptAxisOutputRange.kt` for script limits, axis clamping, and manual targets.
- Add tests beside those classes and extend `core/script/src/test/java/io/github/fplayer/core/script/MediaClockScriptSchedulerTest.kt` only for scheduler integration.
- Modify `core/device/src/main/java/io/github/fplayer/core/device/DeviceContracts.kt` only if a shared manual-target or range adapter is required; preserve `AxisLimit`, `DeviceTarget`, and `DeviceController` compatibility.
- Extend `core/device/src/test/java/io/github/fplayer/core/device/DeviceSafetyControllerTest.kt` for the second safety layer and manual-output route; do not duplicate device clamping in the UI.
- Create `feature/settings/src/main/java/io/github/fplayer/feature/settings/ScriptPlaybackSettings.kt` for persisted/in-memory UI state and reducer.
- Create `feature/settings/src/test/java/io/github/fplayer/feature/settings/ScriptPlaybackSettingsTest.kt` for defaults, signed integer input, range editing, and diagnostics.
- Modify `feature/feed/src/main/java/io/github/fplayer/feature/feed/LongPressPlaybackSettingsState.kt` only when the playback settings panel must expose the new settings snapshot; keep gesture arbitration unchanged unless a focused test fails.
- Modify `app/src/main/java/io/github/fplayer/android/PlaybackService.kt` only to connect the scheduler to the service's media-clock callbacks and to stop the scheduler/active device output on lifecycle failure; no device transport construction moves into the service.

## Interfaces

The following signatures are the plan's stable contracts. Implementers may add private helpers but must not rename or weaken these public/package-visible meanings.

```kotlin
enum class LatencyMeasurementState { UNMEASURED, MEASURING, MEASURED, EXPIRED }

data class LatencySample(
    val sentAtMonotonicMs: Long,
    val receivedAtMonotonicMs: Long,
) {
    val roundTripMs: Long
        get() = receivedAtMonotonicMs - sentAtMonotonicMs
}

data class LatencyCompensationConfig(
    val automaticEnabled: Boolean = true,
    val manualOffsetMs: Long = 0,
    val maximumAutomaticAdvanceMs: Long = 250,
    val sampleWindowSize: Int = 9,
    val expiryMs: Long = 10_000,
)

data class LatencyEstimate(
    val state: LatencyMeasurementState,
    val automaticOffsetMs: Long,
    val medianRoundTripMs: Long?,
    val sampleCount: Int,
    val measuredAtMonotonicMs: Long?,
)

class LatencyEstimator(private val config: LatencyCompensationConfig) {
    fun recordSample(sample: LatencySample): LatencyEstimate
    fun estimate(nowMonotonicMs: Long): LatencyEstimate
    fun reset(): LatencyEstimate
}

fun effectiveOffsetMs(
    config: LatencyCompensationConfig,
    estimate: LatencyEstimate,
): Long

data class ScriptAxisOutputRange(val minimum: Int = 0, val maximum: Int = 100)

data class ScriptOutputLimits(val perAxis: Map<AxisId, ScriptAxisOutputRange>) {
    fun rangeFor(axis: AxisId): ScriptAxisOutputRange
}

data class ManualAxisTarget(val axis: AxisId, val position: Int, val durationMs: Long = 250)

fun intersectAxisRanges(
    script: ScriptAxisOutputRange,
    device: AxisLimit,
): AxisLimit

fun applyScriptRange(position: Int, range: ScriptAxisOutputRange): Int
```

`effectiveOffsetMs` is defined as `manualOffsetMs + (automaticEnabled ? estimate.automaticOffsetMs : 0)`, with saturated addition. `ScriptSchedulerConfig` remains source-compatible with `offsetMs`; the implementation adds `scriptOutputLimits`, `deviceOutputLimits`, and no-argument-safe defaults. `MediaClockScriptScheduler` receives a third constructor parameter `automaticOffsetProvider: () -> Long = { 0L }`; the provider is called only from the serial tick context and supplies the current automatic offset. The existing signed `offsetMs` is the manual component. The scheduler must emit `DeviceTarget.mediaTimeMs` from media time, never from offset-adjusted script time.

The scheduler additions have these exact shapes:

```kotlin
data class ScriptSchedulerConfig(
    val offsetMs: Long = 0,
    val lookAheadMediaMs: Long = 100,
    val maxCommandDurationMs: Long = 500,
    val slice: PlaybackSlice? = null,
    val scriptOutputLimits: ScriptOutputLimits = ScriptOutputLimits(emptyMap()),
    val deviceOutputLimits: Map<AxisId, AxisLimit> = emptyMap(),
)

class MediaClockScriptScheduler(
    clock: PlaybackClock,
    controller: DeviceController,
    automaticOffsetProvider: () -> Long = { 0L },
) {
    fun submitManual(target: ManualAxisTarget, allowWhenPaused: Boolean = false): Boolean
}
```

`submitManual` returns `false` without submission when no script is loaded or when the scheduler is paused and `allowWhenPaused` is false. It returns `true` after the target is accepted and submitted through the same script/device range path. `deviceOutputLimits` is a pure test/integration view of the active device profile; `DeviceSafetyController` remains the final authoritative clamp and reversal layer.

### Task 1: Define latency measurement and range contracts

**Files:**
- Create: `core/script/src/main/java/io/github/fplayer/core/script/LatencyCompensation.kt`
- Create: `core/script/src/main/java/io/github/fplayer/core/script/ScriptAxisOutputRange.kt`
- Create: `core/script/src/test/java/io/github/fplayer/core/script/LatencyCompensationTest.kt`
- Create: `core/script/src/test/java/io/github/fplayer/core/script/ScriptAxisOutputRangeTest.kt`
- Modify: `core/script/src/main/java/io/github/fplayer/core/script/ScriptContracts.kt` only if `AxisId` import/export is needed

**Interfaces:**
- Consumes: `AxisId`, `AxisLimit`, signed integer settings, and response samples supplied by a transport adapter.
- Produces: the exact contracts above for scheduler, settings, and device integration.

- [ ] **Step 1: Write failing estimator tests.**

  Add tests with a fake monotonic timeline:

  ```kotlin
  @Test fun `median RTT becomes bounded negative half RTT automatic offset`() {
      val estimator = LatencyEstimator(LatencyCompensationConfig(maximumAutomaticAdvanceMs = 250))
      listOf(80L, 100L, 120L).forEach { rtt ->
          estimator.recordSample(LatencySample(0L, rtt))
      }
      val result = estimator.estimate(nowMonotonicMs = 1_000L)
      assertEquals(LatencyMeasurementState.MEASURED, result.state)
      assertEquals(50L, result.medianRoundTripMs)
      assertEquals(-50L, result.automaticOffsetMs)
  }

  @Test fun `invalid and expired samples never enable automatic compensation`() {
      val estimator = LatencyEstimator(LatencyCompensationConfig(expiryMs = 100L))
      assertFails { estimator.recordSample(LatencySample(10L, 9L)) }
      assertEquals(LatencyMeasurementState.UNMEASURED, estimator.estimate(0L).state)
      estimator.recordSample(LatencySample(0L, 20L))
      assertEquals(LatencyMeasurementState.EXPIRED, estimator.estimate(121L).state)
      assertEquals(0L, estimator.estimate(121L).automaticOffsetMs)
  }

  @Test fun `manual signed offset is added to automatic value`() {
      val config = LatencyCompensationConfig(automaticEnabled = true, manualOffsetMs = 35L)
      val estimate = LatencyEstimate(LatencyMeasurementState.MEASURED, -50L, 100L, 3, 10L)
      assertEquals(-15L, effectiveOffsetMs(config, estimate))
      assertEquals(35L, effectiveOffsetMs(config.copy(automaticEnabled = false), estimate))
  }
  ```

  Also test sample-window trimming, even/odd median ordering, a zero RTT, a single sample, and automatic advance clamping at `-maximumAutomaticAdvanceMs`. All timestamps are explicitly monotonic test values.

- [ ] **Step 2: Run the failing tests.**

  Run:

  ```powershell
  .\gradlew.bat :core:script:testDebugUnitTest --tests '*LatencyCompensationTest' --no-configuration-cache --rerun-tasks --max-workers=1
  ```

  Expected: compilation/test failure because the new contracts do not yet exist; no Android device or network is involved.

- [ ] **Step 3: Implement the minimal estimator.**

  Validate `sentAtMonotonicMs >= 0`, `receivedAtMonotonicMs >= sentAtMonotonicMs`, positive config bounds, and `roundTripMs` within a finite configured sample domain. Retain at most `sampleWindowSize` samples, sort RTTs, choose the lower middle for an even count, calculate `automaticOffsetMs = -(medianRoundTripMs / 2)` with integer rounding toward zero, and clamp to `[-maximumAutomaticAdvanceMs, 0]`. `estimate(now)` is `UNMEASURED` without samples and `EXPIRED` when `now - measuredAt > expiryMs`; expiry must not retain an old automatic offset.

- [ ] **Step 4: Write failing range and manual-target tests.**

  ```kotlin
  @Test fun `script range clamps target and intersection never widens device range`() {
      val script = ScriptAxisOutputRange(20, 80)
      assertEquals(20, applyScriptRange(0, script))
      assertEquals(80, applyScriptRange(100, script))
      assertEquals(AxisLimit(40, 60), intersectAxisRanges(script, AxisLimit(40, 60)))
      assertEquals(AxisLimit(40, 60), intersectAxisRanges(ScriptAxisOutputRange(0, 100), AxisLimit(40, 60)))
  }

  @Test fun `range defaults cover every supported axis and reject inverted values`() {
      assertEquals(ScriptAxisOutputRange(), ScriptOutputLimits(emptyMap()).rangeFor(AxisId("L0")))
      assertFails { ScriptAxisOutputRange(80, 20) }
      assertFails { ManualAxisTarget(AxisId("L0"), 101) }
  }
  ```

- [ ] **Step 5: Implement range helpers and run focused tests.**

  Keep `ScriptAxisOutputRange` restricted to `0..100`; `rangeFor` returns `0..100` for an absent axis. `intersectAxisRanges` must throw a deterministic `NO_OUTPUT_RANGE` error when the intersection is empty rather than widen or silently disable an axis. `ManualAxisTarget.position` is a script-space value and must be clamped by `applyScriptRange` before it is submitted.

  Run:

  ```powershell
  .\gradlew.bat :core:script:testDebugUnitTest --tests '*LatencyCompensationTest' --tests '*ScriptAxisOutputRangeTest' --no-configuration-cache --max-workers=1
  ```

  Expected: PASS for both new classes.

### Task 2: Integrate offset compensation and script limits into the scheduler

**Files:**
- Modify: `core/script/src/main/java/io/github/fplayer/core/script/MediaClockScriptScheduler.kt`
- Modify: `core/script/src/test/java/io/github/fplayer/core/script/MediaClockScriptSchedulerTest.kt`
- Modify: `core/device/src/main/java/io/github/fplayer/core/device/DeviceContracts.kt` only if a `DeviceTarget` adapter is needed
- Modify: `core/device/src/test/java/io/github/fplayer/core/device/DeviceSafetyControllerTest.kt` for integrated range assertions

**Interfaces:**
- Consumes: `ScriptBundle`, `PlaybackClock`, `LatencyCompensationConfig`, `LatencyEstimate`, `ScriptOutputLimits`, and the existing `DeviceController`.
- Produces: deterministic `DeviceTarget` sequences with correct `generation`, media timestamp, signed effective offset, and script-range clamping.

- [ ] **Step 1: Add failing scheduler tests for offset provider and ranges.**

  Extend the existing recording-controller fixture:

  ```kotlin
  @Test fun `automatic offset is applied in script space while media timestamp stays unchanged`() {
      val scheduler = MediaClockScriptScheduler(clock, controller, automaticOffsetProvider = { -100L })
      scheduler.load(
          singleAxisBundle(),
          ScriptSchedulerConfig(offsetMs = 0, lookAheadMediaMs = 100),
      )
      scheduler.tick()
      assertEquals(Event.Target("L0", 20, 100, 1, 100), controller.events.single())
  }

  @Test fun `script limits clamp every axis before device safety limits`() {
      scheduler.load(
          twoAxisBundle(),
          ScriptSchedulerConfig(
              scriptOutputLimits = ScriptOutputLimits(
                  mapOf(AxisId("L0") to ScriptAxisOutputRange(30, 40), AxisId("R0") to ScriptAxisOutputRange(60, 70)),
              deviceOutputLimits = mapOf(
                  AxisId("L0") to AxisLimit(35, 90), AxisId("R0") to AxisLimit(0, 65),
              ),
          ),
      )
      scheduler.tick()
      assertEquals(listOf(35, 65), controller.events.filterIsInstance<Event.Target>().map { it.position })
  }

  @Test fun `manual target is submitted with current generation and bounded duration`() {
      scheduler.load(singleAxisBundle())
      assertTrue(scheduler.submitManual(ManualAxisTarget(AxisId("L0"), 120, durationMs = 900), allowWhenPaused = true))
      assertEquals(Event.Target("L0", 100, 500, 1, 0), controller.events.single())
  }
  ```

  The existing offset and generation tests must continue to pass alongside these exact fields and assertions.

- [ ] **Step 2: Run focused tests to prove the new behavior is absent.**

  Run:

  ```powershell
  .\gradlew.bat :core:script:testDebugUnitTest --tests '*MediaClockScriptSchedulerTest' --no-configuration-cache --rerun-tasks --max-workers=1
  ```

  Expected: FAIL at compile time or assertions for the three new cases, while existing scheduler tests identify any accidental behavior change.

- [ ] **Step 3: Implement scheduler integration.**

  Preserve `futureMediaTime`, `commandDuration`, slice mapping, pause/buffering/end handling, and axis sorting. Compute `effectiveOffsetMs` once per tick from the injected provider/estimate and call `mapToScriptTime(futureMediaTime, slice, effectiveOffsetMs)`. For each track, clamp interpolated position to its `ScriptAxisOutputRange`; if device output limits are passed into the scheduler for the pure integration path, clamp through `intersectAxisRanges` before submission. Keep `DeviceTarget.mediaTimeMs = futureMediaTime`, `durationMs <= 500`, and `generation` unchanged. A manual target calls the same range/safety submission path and never changes the media clock.

  Any timing/range configuration change that can affect queued output must call `stopAndAdvance(StopReason.SCRIPT_CHANGED)` before taking effect. A manual target while paused is allowed only if the caller explicitly requests it; otherwise the scheduler returns a deterministic `MANUAL_OUTPUT_REQUIRES_ACTIVE_SESSION` result and does not submit.

- [ ] **Step 4: Run scheduler and device regression.**

  ```powershell
  .\gradlew.bat :core:script:testDebugUnitTest :core:device:testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
  .\gradlew.bat :core:script:assembleDebug :core:device:assembleDebug --no-configuration-cache --max-workers=1
  ```

  Expected: all existing and new tests pass; recorded targets have monotonic media times, no target exceeds `0..100`, and old generations are stopped/dropped. No scheduler test opens a transport.

### Task 3: Add settings state, automatic/manual controls, and manual-axis routing

**Files:**
- Create: `feature/settings/src/main/java/io/github/fplayer/feature/settings/ScriptPlaybackSettings.kt`
- Create: `feature/settings/src/test/java/io/github/fplayer/feature/settings/ScriptPlaybackSettingsTest.kt`
- Modify: `feature/settings/build.gradle.kts` only if it needs `:core:script` API access
- Modify: `feature/feed/src/main/java/io/github/fplayer/feature/feed/LongPressPlaybackSettingsState.kt` only to carry a `ScriptPlaybackSettingsSnapshot`
- Modify: `app/src/main/java/io/github/fplayer/android/MainActivity.kt` only to render controls through the settings reducer

**Interfaces:**
- Consumes: `LatencyCompensationConfig`, `LatencyEstimate`, `ScriptOutputLimits`, `ManualAxisTarget`, and `AxisId`.
- Produces:

  ```kotlin
  data class ScriptPlaybackSettingsState(
      val latency: LatencyCompensationConfig = LatencyCompensationConfig(),
      val estimate: LatencyEstimate = LatencyEstimate(UNMEASURED, 0, null, 0, null),
      val outputLimits: ScriptOutputLimits = ScriptOutputLimits(emptyMap()),
      val selectedManualAxis: AxisId = AxisId("L0"),
      val manualPosition: Int = 50,
  )

  sealed interface ScriptPlaybackSettingsAction {
      data class SetAutomaticLatency(val enabled: Boolean) : ScriptPlaybackSettingsAction
      data class SetManualOffsetText(val text: String) : ScriptPlaybackSettingsAction
      data class SetAxisRange(val axis: AxisId, val minimum: Int, val maximum: Int) : ScriptPlaybackSettingsAction
      data class SetManualAxis(val axis: AxisId) : ScriptPlaybackSettingsAction
      data class SetManualPosition(val position: Int) : ScriptPlaybackSettingsAction
      data object ResetLatency : ScriptPlaybackSettingsAction
  }

  fun reduce(
      state: ScriptPlaybackSettingsState,
      action: ScriptPlaybackSettingsAction,
  ): ScriptPlaybackSettingsState
  ```

- [ ] **Step 1: Write failing reducer tests.**

  Cover default `automaticEnabled=true`, manual offset `0`, every absent axis range `0..100`, accepted negative/positive integer strings, ignored fractional/non-numeric text, inverted range correction, and manual position clamping. Assert a disabled automatic switch leaves the last measured estimate visible for diagnostics but `effectiveOffsetMs` uses only the manual value.

  ```kotlin
  @Test fun `signed offset and axis ranges are deterministic`() {
      val initial = ScriptPlaybackSettingsState()
      val configured = reduce(initial, SetManualOffsetText("-125"))
      assertEquals(-125L, configured.latency.manualOffsetMs)
      val limited = reduce(configured, SetAxisRange(AxisId("L0"), 80, 20))
      assertEquals(ScriptAxisOutputRange(20, 80), limited.outputLimits.rangeFor(AxisId("L0")))
  }
  ```

- [ ] **Step 2: Run settings tests before implementation.**

  ```powershell
  .\gradlew.bat :feature:settings:testDebugUnitTest --tests '*ScriptPlaybackSettingsTest' --no-configuration-cache --max-workers=1
  ```

  Expected: missing-type/implementation failure; no real settings or device state is changed.

- [ ] **Step 3: Implement reducer and diagnostics.**

  Parse only a complete signed base-10 integer with `toLongOrNull`; clamp manual offset to an explicit UI range of `-10_000..10_000` before storing. For ranges, clamp endpoints to `0..100`, reorder the two slider values so `minimum <= maximum`, and preserve selected axis. Manual position is clamped to the selected axis script range before emitting `ManualAxisTarget`. Display only `UNMEASURED`, `MEASURING`, `MEASURED`, or `EXPIRED`, sample count, median RTT, and signed offset; do not expose transport locator or media name.

- [ ] **Step 4: Wire controls through existing playback settings surfaces.**

  Add a switch labeled for automatic network-delay calculation, a signed integer offset field, a status/median diagnostic, per-axis `0..100` dual sliders defaulting to `0`/`100`, and a selected-axis position slider/control. The Compose layer dispatches reducer actions and passes manual targets to the scheduler/device session callback; it must not invoke `DeviceTransport.write`, `TCodeEncoder`, or a socket. Preserve existing playback speed/rotation/mute/heatmap behavior and stable 48 dp touch targets.

- [ ] **Step 5: Run settings/feed/app regression.**

  ```powershell
  .\gradlew.bat :feature:settings:testDebugUnitTest :feature:feed:testDebugUnitTest :app:testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
  .\gradlew.bat :app:assembleDebug --no-configuration-cache --max-workers=1
  ```

  Expected: zero failures; at 1.3x font and narrow viewport the signed field, switch, ranges, and diagnostic text remain inside their parents. No UI path can produce a target outside the configured range.

### Task 4: Connect response samples, scheduler lifecycle, and acceptance evidence

**Files:**
- Modify: `core/device/src/main/java/io/github/fplayer/core/device/DeviceTransport.kt` or transport-specific adapters only to expose validated timing samples through a callback; do not put estimator logic in socket/BLE/USB implementations.
- Modify: `feature/device/src/main/java/io/github/fplayer/feature/device/DeviceSession.kt` to forward transport response samples and to stop/clear on disconnect.
- Modify: `app/src/main/java/io/github/fplayer/android/PlaybackService.kt` to drive scheduler ticks from the existing player snapshot loop and to stop it on pause, seek, speed change, service destroy, and device loss.
- Create: `core/script/src/test/java/io/github/fplayer/core/script/ScriptLatencyRecordingIntegrationTest.kt`
- Create: `feature/device/src/test/java/io/github/fplayer/feature/device/ManualAxisOutputIntegrationTest.kt`
- Create: `docs/qa/t32-script-latency.md`

**Interfaces:**
- Consumes: `LatencyEstimator`, scheduler, `DeviceSession`, and `RecordingDeviceFrameSink`/fake `DeviceController`.
- Produces: redacted evidence showing sample state, effective offset, target generation/media time/axis/position/duration, stop reason, and all blocking gates.

- [ ] **Step 1: Write integration tests with injectable response/clock sources.**

  Use a fake transport that records normal/emergency frames and returns response samples, plus a `MutableClock` that exposes `PlayerSnapshot`. Assert WS echo samples can become `MEASURED`, a transport with no response remains `UNMEASURED`, and SPP/BLE/USB adapters can only call `recordSample` with monotonic send/receive timestamps supplied by their backend. Assert pause, seek, speed, loop, connection loss and scheduler clear each stop once and advance generation.

- [ ] **Step 2: Run loopback integration tests before Android wiring.**

  ```powershell
  .\gradlew.bat :core:script:testDebugUnitTest :core:device:testDebugUnitTest :feature:device:testDebugUnitTest --tests '*ScriptLatencyRecordingIntegrationTest' --tests '*ManualAxisOutputIntegrationTest' --no-configuration-cache --max-workers=1
  ```

  Expected: newly added tests fail until adapters and lifecycle hooks are present; existing transport tests remain green.

- [ ] **Step 3: Add a transport-neutral timing sample callback.**

  Define a callback such as `fun interface TransportTimingListener { fun onRoundTripSample(sentAtMonotonicMs: Long, receivedAtMonotonicMs: Long) }` in `core/device`. The callback accepts only validated monotonic sample data; WS ping/pong or application echo is the only automatic source for network transports, while SPP/BLE/USB report `UNMEASURED` when no response/echo exists. Never infer latency from UI frames, write enqueue time alone, or wall clock.

- [ ] **Step 4: Connect estimator and scheduler without bypassing stop state.**

  `DeviceSession` owns the transport listener and publishes a redacted diagnostic; the scheduler receives an immutable `LatencyEstimate`/offset provider on the serial playback context. On any timing/range change, call scheduler stop/advance before applying the next configuration. On disconnect, `DeviceSession` calls `DeviceSafetyController.onConnectionLost()` and the scheduler receives `clear(StopReason.CONNECTION_LOST)`; service destroy calls `clear(StopReason.SERVICE_DESTROYED)` and closes the controller.

- [ ] **Step 5: Run integration tests, full relevant build, and write evidence.**

  ```powershell
  .\gradlew.bat :core:script:testDebugUnitTest :core:device:testDebugUnitTest :feature:device:testDebugUnitTest :feature:feed:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
  .\gradlew.bat :app:assembleDebug --no-configuration-cache --max-workers=1
  git diff --check
  rg -n "(password|passwd|secret|token|[A-Za-z]:\\|COM[0-9]+|[0-9]{1,3}(\.[0-9]{1,3}){3})" docs/qa/t32-script-latency.md
  ```

  Expected: all relevant tests pass, `git diff --check` passes, and the sensitive-value scan returns no matches. The report records only counts, state names, signed offsets, axis IDs, and stop reasons.

- [ ] **Step 6: Execute real-resource gates only through the sibling device/media plan.**

  Do not open a real transport or read real media from this plan. Before any real action, the sibling plan must show `DEVICE-WS/BLE/SPP/USB` loopback PASS, `MEDIA-SD-*` SAF authorization, and a unique `TEST_OSR_DEVICE` profile. If any prerequisite is absent, record `BLOCKED` and stop; never replace a missing response sample or physical safety condition with a synthetic PASS.

### Task 5: Final verification and cleanup

**Files:**
- Modify: `docs/qa/t32-script-latency.md`
- Read: `docs/architecture/script-scheduler.md`, `docs/architecture/device-layer.md`, `docs/security/test-device-policy.md`, and this plan

**Interfaces:**
- Consumes: focused unit/integration test output and redacted frame recorder output.
- Produces: a reproducible `SCRIPT-LATENCY` evidence record and explicit PASS/FAIL/BLOCKED decisions.

- [ ] **Step 1: Verify deterministic output properties.**

  Re-run tests with `--rerun-tasks --max-workers=1`. Compare two recordings from the same bundle, clock snapshots, latency samples, ranges, and actions; assert identical target order, positions, durations, generations, media timestamps, effective offset, and stop sequence. Assert changing only manual offset changes script time but not media timestamp or generation.

- [ ] **Step 2: Verify safety and cleanup.**

  Assert no submitted target exceeds `0..100`, no target escapes the script/device intersection, manual control uses the same controller, queue and emergency frames are empty after pause/disconnect/release, and stale generations cannot drain. Delete only this plan's synthetic recording/cache artifacts; do not delete app data, user media, or external-device state.

- [ ] **Step 3: Record gates without overstating real-device status.**

  Mark `SCRIPT-LATENCY` `PASS` only when pure scheduler, estimator, range, settings, and loopback integration tests pass. Mark the protocol-specific automatic estimate `BLOCKED` if a transport cannot produce validated echo/response samples. Mark physical WS/BLE/SPP/USB and real-media claims `BLOCKED`/`NOT_RUN` until the sibling plan supplies its evidence. No debug APK or synthetic fixture substitutes for real hardware evidence.

- [ ] **Step 4: Run final hygiene commands.**

  ```powershell
  .\gradlew.bat :core:script:testDebugUnitTest :core:device:testDebugUnitTest :feature:device:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
  .\gradlew.bat :app:assembleDebug --no-configuration-cache --max-workers=1
  git diff --check
  Get-ChildItem -Recurse -File | Where-Object { $_.Name -like '*.tmp.*' } | Select-Object -ExpandProperty FullName
  ```

  Expected: tests/build/hygiene pass and no temporary plan or evidence files remain after the final plan is archived. A missing JDK/SDK/device/credential/profile is documented as `BLOCKED`, not hidden by a fallback.

## Self-Review

- **Spec coverage:** Existing strict parser/matcher and interpolation remain the source of truth for single/multi-axis scripts; Tasks 1-2 cover signed delay, robust estimate, scheduler offset, generation, speed/seek/pause/loop, two-layer limits, and manual targets; Task 3 covers automatic/manual controls and per-axis ranges; Task 4 covers response hooks and lifecycle; Task 5 covers deterministic evidence and cleanup.
- **Completeness:** All tasks name exact paths, signatures, test cases, commands, expected outcomes, and blocking conditions. No step relies on vague error-handling language or an unresolved implementation decision.
- **Type consistency:** `LatencyCompensationConfig`, `LatencyEstimate`, `LatencyEstimator`, `effectiveOffsetMs`, `ScriptAxisOutputRange`, `ScriptOutputLimits`, `ManualAxisTarget`, and range helpers are defined before scheduler/settings consumers. Existing `ScriptSchedulerConfig.offsetMs`, `DeviceTarget`, and `StopReason` semantics are explicitly preserved.
- **Safety review:** No plan content includes real credentials, device addresses, physical paths, serial names, SSIDs, media names, or raw frame logs. Real-device execution remains gated by the sibling plan and test-device policy.

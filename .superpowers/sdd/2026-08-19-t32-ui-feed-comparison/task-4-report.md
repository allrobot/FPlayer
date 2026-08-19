# Task 4 Review Fix Round 1 Report

Status: DONE_WITH_OPEN_INTEGRATION_GAPS

## Changes

- The activity now selects Feed and grid media through the service-owned playback boundary. The feed attaches a lifecycle-bound `SurfaceView` to the existing service-owned libmpv engine and detaches it when the surface is destroyed; Compose does not access device transport APIs.
- Seek requests now carry the progress token to the service. The service validates the token against an observed engine position, reports explicit success, replacement, rejection, or timeout, and the progress interaction remains settling until that service confirmation arrives.
- A service observer replaces diagnostics polling. It publishes current engine clock state and the loaded `ScriptHeatmap` only while an activity observer is attached.
- The progress surface consumes the injected heatmap render model and draws its traces. Feed drag handling stops when progress interaction blocks paging.
- Indexed thumbnail cache keys are now surfaced through catalog/grid models. Thumbnail rendering is asynchronous, loads only a validated cache key from the app cache boundary, and distinguishes loading, error, unavailable, and ready states without exposing a raw media locator as an image key.

## Tests

- RED/GREEN: `PlaybackSeekCoordinatorTest` verifies token matching, engine-position tolerance, stale-token rejection, and failure clearing.
- RED/GREEN: `PlaybackProgressBindingTest` verifies a failed seek releases the Feed-paging block.
- Existing heatmap renderer tests cover empty-data BAR fallback.
- JDK 17 focused Feed verification passed with dependency verification explicitly disabled because a concurrent untracked verification marker is malformed:

  ```powershell
  .\gradlew.bat :feature:feed:testDebugUnitTest --tests '*PlaybackProgressBindingTest' --tests '*PlaybackSeekCoordinatorTest' --no-configuration-cache --rerun-tasks --max-workers=1 --dependency-verification=off
  ```

## Follow-up Review Fix

- `da99cd2 fix: preserve selected media in feed` ensures a catalog or folder-grid selection
  initializes the Feed at that media rather than replacing it with item zero. Feed paging now
  keeps the catalog's current-media context in sync, and the reducer test covers selection of a
  non-first item followed by a page change.

## Open Integration Findings

- The thumbnail UI consumes committed cache keys, but the Android app has no production
  `MetadataThumbnailPipeline` owner that writes to the cache root read by the UI. A valid key can
  therefore still render the error state until that producer/root contract is implemented.
- Surface teardown remains dependent on the `SurfaceHolder` callback after the activity unbinds.
  Background playback needs an explicit service-owned detach handoff to avoid retaining an old
  MPV surface during lifecycle races.
- The heatmap is published when `PlaybackService.loadScript()` receives a bundle, but normal
  library selection does not yet resolve and load a matching script. Library playback therefore
  continues to use the bar fallback unless another caller loads a script.

## External Build Blocker

- A concurrent untracked `gradle/verification-metadata.xml` causes normal Gradle configuration to fail XML parsing. It was not modified.
- A concurrent uncommitted release-validation change in `app/build.gradle.kts` requires real production signing inputs while configuring debug test and debug assemble tasks. No credentials were supplied or fabricated, so the final requested app/library/assemble command is blocked after this round. The prior UI round's full JDK 17 app/library/feed tests and debug assemble passed before those concurrent changes landed.

## Remaining Concerns

- Runtime Surface/decoder and screenshot validation still require an ADB-visible device and a synthetic media fixture; no real media or device transport was used in this round.

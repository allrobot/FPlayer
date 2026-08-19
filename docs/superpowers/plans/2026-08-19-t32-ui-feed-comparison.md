# T32 UI/Feed Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 FPlayer 的实际播放界面、Feed/网格跳转、播放叠层和 Funscript 热力图补齐到已冻结的抖音式工作型交互规格，并用参考应用的可观察行为完成脱敏验收。

**Architecture:** 保留 `FeedPagingStateMachine`、`PlaybackSession`、`PlaybackOverlayState` 和 `ProgressInteractionStateMachine` 作为纯 Kotlin 契约；Compose 只负责手势、Surface/叠层和状态渲染，媒体时钟、脚本调度和设备输出继续由既有播放/设备边界提供。Library 状态通过 `LibraryGridStateMachine` 与 `LibraryCatalogStateMachine` 传递当前媒体和来源上下文，避免 UI 直接访问索引或 transport。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、libmpv Surface adapter、Room index contracts、Android instrumentation/ADB screenshot fixtures、现有 Gradle test tasks。

**Spec:** `docs/ui/interaction-spec.md`、`docs/ui/task-breakdown.md`、`docs/architecture/script-heatmap.md`、`docs/architecture/media-index.md`、`TASKS.md` T22-T28。

## Global Constraints

- 播放背景为黑色，白/灰为主控，青色只表达设备/脚本状态，红色只表达点踩、断连和急停；不复制 TikTok 品牌、素材或私有实现。
- 图标按钮视觉尺寸 32~40 dp，触摸热区至少 48 x 48 dp；固定圆角不超过 8 dp；字间距为 0；所有图标必须有 content description。
- Feed 只允许一个 settled active 媒体实例输出音频/脚本；Surface 消失不能销毁后台播放会话。
- 脚本热力图只消费 `ScriptHeatmap`，不参与调度、限幅或设备安全决策；进度条和热力图共享 `ProgressInteractionStateMachine`。
- 用户真实媒体和外部来源只能通过 `TEST_TABLET`、`TEST_OSR_WIFI`、`FUNSCRIPT_TEST_LIBRARY` 等逻辑标识记录；截图、计划、日志不得含凭据、盘符、IP、SSID、COM 端口、文件名或媒体正文。
- 修改后必须运行相关单测、四 viewport 截图/触摸验收、`git diff --check`；临时 fixture 和截图仅保留中性合成数据。

## Existing Boundaries and Observed Gaps

- `app/src/main/java/io/github/fplayer/android/MainActivity.kt:313` 的 `PlaybackFeedScreen` 当前只显示“暂无媒体/添加文件夹”，仅实现横向拖动阈值，没有纵向 `FeedPagingStateMachine`、真实视频 Surface、进度条或热力图绑定。
- `feature/feed/src/main/java/io/github/fplayer/feature/feed/FeedPagingStateMachine.kt` 已实现触摸 slop、1.25 方向锁、18%/1100 dp/s 翻页、边界阻尼和 settle 后 active promotion；Compose 尚未消费其 snapshot。
- `PlaybackOverlayState.kt`、`ProgressInteractionState.kt`、`PlaybackSession.kt` 已提供纯状态契约；当前 MainActivity 只渲染占位标题和操作按钮，未用播放时钟、脚本轨道或 device session 更新它们。
- `feature/library/src/main/java/io/github/fplayer/feature/library/LibraryGridScreen.kt` 已有固定比例 tile、网格/双列、脚本过滤、刚刚看过定位和进度标记，但封面是 `SmartDisplay` 占位图标；`LibraryCatalogScreen.kt` 已有抽屉、集合、搜索、排序和删除确认。
- `LibraryIndexRepository.kt` 已从 committed Room generation 读取媒体、脚本存在性、播放覆盖率和收藏状态；后续 UI 只消费 `LibraryCatalogSnapshot`/`LibraryGridSnapshot`，不得在 Composable 中重新扫描 SAF/SMB。
- 参考可观察行为：`REF_TRYMOSA` 提供每轴实时位置、每轴输出上下限和运动可视化；`REF_JOYTECH_PLAYER` 提供媒体/脚本选择、WS/蓝牙/串口入口、全局/每轴延迟和轴范围；`REF_FUNWISP` 提供文件库、脚本曲线热力图、duration/actions/speed 和 L0/L1/L2/R0/R1/R2 轴选择。它们只作为行为对照，不复制品牌、代码或内容。

### Task 1: 建立 UI 行为基线和脱敏对比记录

**Files:**
- Create: `docs/qa/t32-ui-feed-comparison.md`
- Read: `docs/ui/interaction-spec.md`, `docs/architecture/script-heatmap.md`, `feature/feed/src/test/java/io/github/fplayer/feature/feed/*.kt`, `feature/library/src/test/java/io/github/fplayer/feature/library/*.kt`
- Test: `:feature:feed:testDebugUnitTest`, `:feature:library:testDebugUnitTest`

**Interfaces:**
- Consumes: `FeedPagingSnapshot`, `PlaybackOverlayState`, `ProgressInteractionSnapshot`, `LibraryGridSnapshot`, `LibraryCatalogSnapshot`。
- Produces: 脱敏的 `UI-FEED` 缺口表，固定记录 `PASS/FAIL/BLOCKED/NOT_RUN`、测试命令、截图逻辑标识和后续任务 ID；不记录外部地址或媒体名。

- [ ] **Step 1: 运行现有纯状态回归并记录结果**

  ```powershell
  .\gradlew.bat :feature:feed:testDebugUnitTest :feature:library:testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
  ```

  预期：Feed 阈值/方向锁/边界、播放叠层 action、进度 seek token、长按设置和网格当前位置测试全部通过；失败项逐项写入 `docs/qa/t32-ui-feed-comparison.md`。

- [ ] **Step 2: 记录参考行为对照而不采集内容**

  在报告中使用逻辑标识 `REF_TRYMOSA`、`REF_JOYTECH_PLAYER`、`REF_FUNWISP`、`REF_CYBER_ELECTRONIC_1_0_5`，逐项记录来源切换、媒体/脚本列表、热力图轴选择、每轴限幅、播放/暂停/倍速、清屏和横竖屏行为。只保留控件语义、状态转换和是否具备该能力。

- [ ] **Step 3: 固定当前 APK 的可见缺口**

  报告必须明确：真实 Surface 未接入；默认 Feed 没有已索引媒体时以占位态显示；垂直分页和单 active promotion 未接 Compose；进度/热力图未渲染；网格封面仍为占位图标；设备连接入口属于 `feature/device` 及设备子计划，不在本计划实现。

### Task 2: 接入真实播放 Surface 与垂直 Feed

**Files:**
- Modify: `app/src/main/java/io/github/fplayer/android/MainActivity.kt`
- Modify: `feature/feed/src/main/java/io/github/fplayer/feature/feed/FeedPagingStateMachine.kt` only if an existing contract test exposes a specification mismatch
- Modify: `feature/feed/src/main/java/io/github/fplayer/feature/feed/PlaybackSession.kt`
- Create: `feature/feed/src/main/java/io/github/fplayer/feature/feed/PlaybackFeedState.kt`
- Create: `feature/feed/src/main/java/io/github/fplayer/feature/feed/PlaybackFeedStateTest.kt`
- Test: `feature/feed/src/test/java/io/github/fplayer/feature/feed/FeedPagingStateMachineTest.kt`, new feed state test, existing playback session tests

**Interfaces:**
- Consumes: `FeedPagingStateMachine(pageCount, pageExtentPx, config)`, `PlaybackSessionSnapshot`, and a media Surface binder supplied by the app/libmpv adapter.
- Produces: `PlaybackFeedState(items: List<PlaybackFeedItem>, activeIndex: Int?, settledIndex: Int?, session: PlaybackSessionSnapshot?)`; `PlaybackFeedReducer.reduce(state, FeedPagingEvent)` must promote only a settled target and expose one active item.

- [ ] **Step 1: Write deterministic promotion tests**

  Add tests asserting that a vertical move under 18% returns to the original index, a move over threshold enters `SETTLING`, and `onSettleProgress(0.95f)` changes exactly one active index. Assert rapid next/previous events never produce two active items and a horizontal threshold emits `OPEN_GRID` without changing the active media.

- [ ] **Step 2: Run the new tests before rendering**

  ```powershell
  .\gradlew.bat :feature:feed:testDebugUnitTest --tests '*PlaybackFeedStateTest' --tests '*FeedPagingStateMachineTest' --no-configuration-cache
  ```

  Expected: the new tests fail until the feed state/reducer is present, while existing state tests remain green.

- [ ] **Step 3: Implement the smallest feed state/reducer and Surface ownership**

  Define `sealed interface FeedPagingEvent` with `Down`, `Move(dxPx, dyPx)`, `Up(velocityX, velocityY)`, `Settle(progress)`, and `ReplaceItems(items)`. In `PlaybackFeedReducer.reduce`, forward gesture events to the state machine, set `settledIndex` only at configured completion, and pass the selected media to one `PlaybackSession`; Compose must reuse the session Surface binder across Activity recomposition and display a neutral empty state only when the list is empty.

- [ ] **Step 4: Render vertical paging and horizontal grid entry in MainActivity**

  Replace the placeholder gesture block with a full-viewport pager that reports cumulative deltas and velocities to `PlaybackFeedReducer`. Render the current libmpv Surface behind overlays, preload only adjacent item metadata, and call `onOpenGrid` only on `OPEN_GRID`. Keep media/audio/device output ownership in `PlaybackSession`/service; no device API calls from the Composable.

- [ ] **Step 5: Run focused tests and debug build**

  ```powershell
  .\gradlew.bat :feature:feed:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-configuration-cache --max-workers=1
  ```

  Expected: zero failures; empty library still renders an actionable SAF picker; one-item and multi-item fixtures show exactly one active Surface; fast swipes cannot overlap playback sessions.

### Task 3: Add playback overlay, progress bar, and heatmap renderer

**Files:**
- Modify: `app/src/main/java/io/github/fplayer/android/MainActivity.kt`
- Create: `feature/feed/src/main/java/io/github/fplayer/feature/feed/PlaybackOverlayLayout.kt`
- Create: `feature/feed/src/main/java/io/github/fplayer/feature/feed/ProgressHeatmapRenderer.kt`
- Create: `feature/feed/src/test/java/io/github/fplayer/feature/feed/ProgressHeatmapRendererTest.kt`
- Read: `feature/feed/src/main/java/io/github/fplayer/feature/feed/ProgressInteractionState.kt`, `core/script/src/main/java/**/ScriptHeatmap*.kt` (actual package path resolved before implementation)
- Test: progress/overlay/heatmap unit tests and four viewport instrumentation screenshots

**Interfaces:**
- Consumes: `PlaybackOverlayState`, `PlaybackOverlayReducer`, `ProgressInteractionStateMachine`, `ScriptHeatmap`, `HeatmapWindow`, and a media clock position callback.
- Produces: `PlaybackOverlayLayout(...)` with top bar/right rail/metadata/clean controls; `ProgressHeatmapRenderer(heatmap, snapshot, onSeekPreview, onSeekCommit)`; no scheduler or device calls.

- [ ] **Step 1: Write renderer contract tests**

  Test that `ProgressHeatmapRenderer` maps each axis in `AxisId.value` order, clips samples to the viewport window, keeps multi-axis traces distinguishable by luminance/line style, and renders the bar mode when heatmap data is absent. Test progress drag commits only on pointer-up and blocks Feed paging while pressed/dragging/settling.

- [ ] **Step 2: Implement fixed geometry and accessible overlay layout**

  Use a stable bottom progress/heatmap height, 48 dp touch target, `Text("current / total")` preview above the pointer, and window insets. In normal mode render the seven top controls, right rail actions, three-line metadata and bottom navigation; in clean mode render only exit, seek-back, play/pause and speed, while a device-stop/connection failure remains visible above all modes.

- [ ] **Step 3: Bind media clock and seek generation**

  On media clock updates call `updateMediaPosition`; on pointer move update preview only; on pointer-up call `onPointerUp`, send the returned token to the playback session, and accept only matching `onSeekConfirmed`. Consume start/end haptic edges once and do not let progress gestures enter Feed paging.

- [ ] **Step 4: Run unit tests and four viewport screenshot automation**

  ```powershell
  .\gradlew.bat :feature:feed:testDebugUnitTest :app:assembleDebug --no-configuration-cache --max-workers=1
  adb shell am force-stop io.github.fplayer.android
  adb shell am start -n io.github.fplayer.android/.debug.DeviceScreenshotActivity
  ```

  Capture phone portrait/landscape and `TEST_TABLET` portrait/landscape using the existing debug screenshot activities. Expected: no overlap/truncation/system-bar collision at default and 1.3x font; heatmap and metadata do not change fixed control geometry; clean mode still exposes emergency stop.

### Task 4: Connect Library grid/catalog to real thumbnails and playback context

**Files:**
- Modify: `feature/library/src/main/java/io/github/fplayer/feature/library/LibraryGridScreen.kt`
- Modify: `feature/library/src/main/java/io/github/fplayer/feature/library/LibraryCatalogScreen.kt`
- Modify: `app/src/main/java/io/github/fplayer/android/MainActivity.kt`
- Modify: `feature/library/src/main/java/io/github/fplayer/feature/library/LibraryGridState.kt` only for a tested jump/context contract change
- Create: `feature/library/src/test/java/io/github/fplayer/feature/library/LibraryPlaybackContextTest.kt`
- Test: existing library state/catalog/grid tests and Compose screenshot fixture

**Interfaces:**
- Consumes: `LibraryGridSnapshot`, `LibraryCatalogSnapshot`, `LibraryCatalogMedia`, `LibraryGridItem`, `LibraryJumpTarget`, and `onOpenMedia(mediaId)`.
- Produces: fixed 3:2 thumbnail tile with optional script badge, stable progress overlay, “刚刚看过” state, folder context, and an `onHomePressed()` transition of `FOLDER_PLAYBACK -> FOLDER_GRID -> DEFAULT_FEED`.

- [ ] **Step 1: Add state tests for folder/playback/home round trips**

  Assert opening a tile stores the selected media, returning to Home first exposes the originating folder grid, the second Home action exposes default Feed, and a jump target scrolls once without changing selection. Assert scripted-only filtering never displays media without `hasScript`.

- [ ] **Step 2: Replace placeholder tile artwork with an injected thumbnail model**

  Keep thumbnail loading outside the state machine; pass a stable `thumbnailKey`/loader callback into `LibraryGridScreen` and `LibraryCatalogScreen`. Render a neutral error/placeholder state on failure, retain fixed 3:2 geometry, and do not persist or log real media bytes.

- [ ] **Step 3: Preserve catalog controls and wire open-media context**

  Keep drawer collections, search, sort and delete confirmation in `LibraryCatalogScreen`; update `MainActivity` so selecting a catalog/grid item calls the feed reducer with the originating folder and current media ID. The first Home press returns to that folder grid and a second press returns to default Feed.

- [ ] **Step 4: Verify with synthetic large-library and viewport fixtures**

  ```powershell
  .\gradlew.bat :feature:library:testDebugUnitTest :app:assembleDebug --no-configuration-cache --max-workers=1
  adb shell am start -n io.github.fplayer.android/.debug.LibraryScreenshotActivity
  adb shell am start -n io.github.fplayer.android/.debug.CatalogScreenshotActivity
  ```

  Expected: 2,000 synthetic items scroll without duplicate keys or layout jumps; grid and double-column tiles retain stable dimensions; search empty state, folder back, current progress and script badge remain visible at four viewports.

### Task 5: Full UI acceptance, comparison, and cleanup

**Files:**
- Modify: `docs/qa/t32-ui-feed-comparison.md`
- Read: `docs/qa/t27-screenshots/`, `docs/qa/t28-screenshots/`, `docs/architecture/script-heatmap.md`, `docs/architecture/media-index.md`
- Test: all feed/library unit tests, debug APK screenshot automation, `git diff --check`

**Interfaces:**
- Consumes: completed feed reducer, overlay/heatmap renderer, library context contracts, and screenshot evidence.
- Produces: final `UI-FEED` result with each requirement marked `PASS`, `FAIL`, or `BLOCKED`; evidence paths are repository-relative and contain only synthetic fixtures.

- [ ] **Step 1: Run the complete focused regression**

  ```powershell
  .\gradlew.bat :feature:feed:testDebugUnitTest :feature:library:testDebugUnitTest :app:testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
  .\gradlew.bat :app:assembleDebug --no-configuration-cache --max-workers=1
  ```

  Expected: zero failures/errors; no test starts a device transport or accesses real media.

- [ ] **Step 2: Capture and inspect all required states**

  Capture empty state, one synthetic video, multi-item vertical feed, horizontal grid entry, progress drag, heatmap enabled, clean mode, stop/error overlay, catalog search and folder return on phone/pad portrait/landscape. Inspect for overlap, clipping, 1.3x text, insets, stable tile/control dimensions and one active playback session.

- [ ] **Step 3: Record comparison and stop conditions**

  Mark `PASS` only when the observable control/state is present and its state machine test plus screenshot evidence pass. Mark `BLOCKED` for missing libmpv/Surface fixture or unavailable test device; never substitute a reference APK screenshot or a real external media capture. Explicitly record that WS/BLE/SPP/USB behavior and latency/limit controls are verified by sibling T32 plans.

- [ ] **Step 4: Remove temporary evidence and run hygiene checks**

  Delete only this plan's generated synthetic screenshots/cache after copying hashes and relative evidence metadata into the report. Then run:

  ```powershell
  git diff --check
  rg -n "absolute-path-or-credential-pattern" docs/qa/t32-ui-feed-comparison.md
  ```

  Expected: `git diff --check` passes; the scan produces no sensitive values; no `.tmp.*` files remain.

## Self-Review

- **Spec coverage:** T22 vertical Feed and gesture arbitration are covered by Tasks 2-3; T23 overlay/clean mode and insets by Task 3; T24 progress/heatmap by Task 3; T27 grid/playback context and T28 catalog/search by Task 4; reference comparisons and four viewport evidence by Tasks 1 and 5. Device protocols, latency compensation and physical safety remain explicitly outside this UI plan.
- **Placeholder scan:** every step has concrete files, interfaces, commands and expected results; unavailable Surface/device fixtures are explicit `BLOCKED` outcomes.
- **Type consistency:** `FeedPagingEvent`, `PlaybackFeedState`, `PlaybackFeedReducer`, `PlaybackOverlayLayout`, and `ProgressHeatmapRenderer` are introduced before their consumers; existing snapshot/state names match the current Kotlin files.
- **Safety review:** no credentials, physical paths, IPs, SSIDs, serial names, real filenames, or media content are written to the plan; all external resources are logical identifiers.

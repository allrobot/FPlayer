# T32 UI/Feed Comparison Baseline

本报告只记录逻辑标识、控件语义和聚合测试结果，不记录外部地址、凭据、盘符、端口、真实媒体名或媒体内容。

## Baseline

| Area | Source/fixture | Result | Evidence | Follow-up |
| --- | --- | --- | --- | --- |
| Feed threshold, direction lock, boundary damping | `REF_TRYMOSA`, synthetic state tests | PASS | `:feature:feed:testDebugUnitTest` | T32-UI-2 |
| Playback overlay actions and clean-mode reducer | `REF_FUNWISP`, synthetic state tests | PASS | `:feature:feed:testDebugUnitTest` | T32-UI-3 |
| Progress seek token, haptic edges, paging arbitration | `REF_JOYTECH_PLAYER`, synthetic state tests | PASS | `:feature:feed:testDebugUnitTest` | T32-UI-3 |
| Library grid filtering, progress marker, recent item | `REF_FUNWISP`, synthetic state tests | PASS | `:feature:library:testDebugUnitTest` | T32-UI-4 |
| Catalog drawer/search/sort/delete state | `REF_FUNWISP`, synthetic state tests | PASS | `:feature:library:testDebugUnitTest` | T32-UI-4 |
| SMB/local/favorite/recommendation source semantics | `REF_JOYTECH_PLAYER`, logical behavior comparison | BLOCKED | No external source opened in this baseline | T32-DEVICE |
| WS/BLE/SPP/USB connection entry and TCode output | `REF_JOYTECH_PLAYER`, `REF_CYBER_ELECTRONIC_1_0_5` | BLOCKED | Owned by device/media plan | T32-DEVICE |

## Current APK Gaps

- `PlaybackFeedScreen` still renders an empty-media placeholder and folder-picker action when no indexed media is supplied.
- Compose does not yet consume `FeedPagingStateMachine` for vertical paging or settled single-active promotion.
- No real libmpv Surface binder or media-clock binding is connected to the feed screen; device and transport calls remain outside Composables.
- Playback progress and `ScriptHeatmap` are not rendered in the current feed surface.
- Library tiles retain a neutral display-icon placeholder instead of an injected thumbnail model.
- Catalog/grid media selection returns to Home without carrying folder/current-media playback context.

## Reference Behavior Matrix

| Logical reference | Observed behavior to match | Current status |
| --- | --- | --- |
| `REF_TRYMOSA` | Per-axis live position, output upper/lower limits, motion visualization | Contract exists for device/script layers; UI visualization pending |
| `REF_JOYTECH_PLAYER` | Media/script selection, WS/BLE/serial entry, signed latency control and axis limits | Device and settings contracts exist; real entry/output gate pending |
| `REF_FUNWISP` | Folder library, script curve/axis view, duration/actions/speed, full-page playback controls | Catalog state exists; playback surface/heatmap pending |
| `REF_CYBER_ELECTRONIC_1_0_5` | Wireless, Bluetooth and OTG serial behavior | Comparison identifier only; physical validation pending |

## Commands

```text
JAVA_HOME=<JDK17> gradlew :feature:feed:testDebugUnitTest :feature:library:testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
BUILD SUCCESSFUL; 81 actionable tasks; 0 failures/errors/skips
```

Temporary screenshots and real-resource fixtures were not created. Real-device/media results must be recorded only by the sibling device/media plan using its logical test identifiers.

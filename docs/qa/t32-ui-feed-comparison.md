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

- Empty indexed libraries still render the placeholder and folder-picker action by design.
- Compose does not yet consume `FeedPagingStateMachine` for vertical paging or settled single-active promotion.
- Real libmpv Surface binding, indexed-script loading, thumbnail cache-root loading, and catalog selection are service-owned; physical viewport proof remains blocked because the approved fixture flow was not run this round.

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

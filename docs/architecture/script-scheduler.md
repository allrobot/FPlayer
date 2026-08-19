# Funscript 媒体时钟调度

本文固定 P2/T05 的纯 Kotlin 调度语义。`MediaClockScriptScheduler` 是由播放会话驱动的
状态机，不创建线程、不读取墙钟，也不接触具体串口、蓝牙或网络 API。调用方应在单一
串行执行上下文周期调用 `tick()`。

## 采样与插值

- `PlaybackClock` 每次返回播放器的 `PlayerSnapshot`；`positionMs` 是唯一时间基准。
- 每次 tick 采样短时未来媒体位置，默认前瞻 100 ms。命令墙钟 duration 为
  `ceil(mediaDelta / speed)`，硬限制为 1 到 500 ms。
- 每轴独立线性插值并四舍五入为整数位置。首个 action 之前保持首值，最后 action
  之后保持末值；负脚本时间不输出。
- 轴按 `AxisId.value` 排序提交，使相同输入生成相同命令流。即使目标值未变也继续滚动
  刷新短时命令，设备安全不依赖无限期固件动作。

## 偏移与切片

正 `offsetMs` 表示脚本相对媒体延后，公式为：

```text
scriptTime = mappedMediaTime - offsetMs
```

负偏移表示脚本提前。未配置切片时 `mappedMediaTime` 等于媒体时间。配置
`PlaybackSlice(mediaStartMs, mediaEndExclusiveMs, scriptStartMs)` 后：

```text
mappedMediaTime = scriptStartMs + mediaTime - mediaStartMs
```

切片开始前不输出，达到 exclusive end 时停止。未来采样不会越过媒体 duration 或切片
末端。

## Generation 与停止

首次加载脚本从 generation 1 开始。以下事件增加 generation，并在产生新目标前调用
`DeviceController.stop` 清理旧队列：

| 事件 | stop reason | 恢复方式 |
| --- | --- | --- |
| pause | `PLAYBACK_PAUSED` | 播放快照恢复后重新采样 |
| buffering | `PLAYBACK_BUFFERING` | 缓冲结束后重新采样 |
| seek | `PLAYBACK_SEEK` | 调用 `onDiscontinuity(SEEK)` 后 tick |
| loop | `PLAYBACK_LOOP` | 调用 `onDiscontinuity(LOOP)` 后 tick |
| speed 改变 | `PLAYBACK_SPEED_CHANGED` | 同一次 tick 清队列并按新速度提交 |
| 切片改变/结束 | `SLICE_CHANGED` / `SLICE_ENDED` | 重新加载配置或结束 |
| 脚本替换 | `SCRIPT_CHANGED` | 新 bundle 从新 generation 输出 |
| 播放结束 | `PLAYBACK_ENDED` | 加载下一媒体 |

连续相同 pause、buffering、切片结束或播放结束只产生一次 stop。显式 seek、loop 和切片
变化必须由播放会话通知，不能依赖 tick 间位置差猜测。`DeviceTarget` 携带 generation 和
目标对应的 `mediaTimeMs`；T08 安全层必须拒绝旧 generation，且 stop 必须抢占并清空
普通命令。

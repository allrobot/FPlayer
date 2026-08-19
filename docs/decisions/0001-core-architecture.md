# ADR-0001：libmpv + Android 原生脚本与设备层

- 状态：已接受
- 日期：2026-08-09

## 背景

产品需要全面视频解码、Funscript 媒体时钟同步、多种 TCode transport、2,000+ 媒体索引和锁屏后台循环。Windows Funscript 播放器和 JavaScript TCode 项目能提供协议/交互参考，但不能直接满足 Android 生命周期、权限和后台服务要求。

## 决策

采用 B+C 组合：

- libmpv 作为唯一媒体播放内核，通过独立 adapter 实现 `core/player-api`。
- Funscript 解析、匹配、插值和调度使用 Kotlin 原生实现。
- TCode 协议、安全控制和 TCP/UDP/WebSocket/BLE/SPP/USB transport 使用 Android 原生实现。
- 播放会话由 Foreground Service 持有，Activity 只绑定和展示。
- 相邻源码仓库只读参考；native 库由 Ubuntu 可重复构建脚本产出。

## 原因

- libmpv 已覆盖目标格式、字幕和成熟的媒体时钟/seek 语义。
- 原生设备层能直接处理 Android 蓝牙、USB、网络、权限、前台服务和线程生命周期。
- 核心不依赖 JavaScript runtime 或 Intiface，减少后台运行的中间故障点。
- 接口隔离允许后续增加 Intiface/Handy adapter，不污染首期协议核心。

## 后果

- 需要维护 JNI/native 构建链和许可证清单。
- libmpv 与 Compose Surface、后台无 Surface 播放需要专门适配与测试。
- 多 transport 不能作为一个大任务实现，必须按 `TASKS.md` 分会话交付。
- 设备安全状态机成为发布关键路径，而不是 transport 的附属逻辑。

## 未采用方案

- 只用 ExoPlayer/Media3：格式/字幕和 mpv 参考复用不足，仍需额外 native 解码工作。
- 嵌入 Windows 播放器或 Node/JavaScript runtime：Android 生命周期和外设权限不匹配，体积和故障面更大。
- 首期只接 Intiface：当前目标 OSR SPP 支持不满足，且增加外部服务依赖。

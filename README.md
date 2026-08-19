# FPlayer

FPlayer 是一个面向 Android 的开源本地/SMB 视频播放器。它使用 libmpv 处理媒体播放，自动匹配 Funscript，并通过 Android 原生设备层驱动 TCode 设备。界面交互借鉴短视频播放器的垂直分页、沉浸播放和紧凑控件，但不复制任何品牌素材。

当前仓库处于工程基线阶段：模块边界和核心契约已建立，libmpv、数据库、传输层和完整 UI 尚未实现。

## 模块

| 模块 | 职责 |
| --- | --- |
| `app` | 应用入口、导航组合、前台播放服务装配 |
| `core/model` | 媒体、脚本、播放行为和轴标识模型 |
| `core/player-api` | 与 libmpv 实现隔离的播放器契约 |
| `core/script` | Funscript 解析、匹配、插值和同步策略 |
| `core/device` | TCode、轴限制、调度、安全停止和传输抽象 |
| `core/index` | 媒体索引、扫描、排序和物化顺序契约 |
| `feature/feed` | 垂直播放 Feed、播放控件和手势协调 |
| `feature/library` | 相册、文件夹、网格、搜索和跳转 |
| `feature/device` | 设备发现、连接、配置和诊断 |
| `feature/settings` | 播放、脚本、索引、推荐和安全设置 |

## 构建基线

- JDK 17
- Gradle 9.5.0（Wrapper）
- Android Gradle Plugin 9.3.0
- Android SDK 37 / Build Tools 36.0.0
- Kotlin 2.3.21 / Compose BOM 2026.06.00
- 最低 Android 8.0（API 26）

不要依赖机器全局的 `JAVA_HOME`。在当前 shell 中把 `JAVA_HOME` 指向 JDK 17，或在未跟踪的本地 Gradle 配置中设置 JDK。

```powershell
if (-not $env:JDK_17_HOME) { throw 'Set JDK_17_HOME to a local JDK 17 installation' }
$env:JAVA_HOME = $env:JDK_17_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug
```

## 开发入口

- 产品范围：`SPEC.md`
- 当前状态：`PROGRESS.md`
- 阶段任务：`TASKS.md`
- 总体架构：`docs/architecture/overview.md`
- UI 交互：`docs/ui/interaction-spec.md`
- 真实设备策略：`docs/security/test-device-policy.md`
- 参考源码逻辑标识：`docs/references/source-map.md`

## 安全状态

当前基线没有连接真实 TCode 设备，也没有向真实 Android 设备安装 APK。任何真实设备测试必须先满足安全策略并在 `PROGRESS.md` 中记录目标和验证结果。

## 许可证

项目暂定使用 `GPL-3.0-or-later`。最终发布前必须完成依赖许可证矩阵、源码归属记录和第三方 NOTICE。

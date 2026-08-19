# 参考源码逻辑标识

所有路径以本仓库根目录为基准。参考仓库默认只读；不存在时由对应阶段记录来源 URL 和 commit 后再拉取。禁止把开发机绝对路径写回文档。

| 逻辑标识 | 相对路径 | 用途 |
| --- | --- | --- |
| `REF_MPV_ANDROID` | `../mpv-android` | Android libmpv 集成、Surface、输入和构建参考 |
| `REF_MPV` | `../mpv` | 播放内核和 native 构建输入 |
| `REF_FFMPEG` | `../FFmpeg` | 编解码构建输入与许可证开关 |
| `REF_DAV1D` | `../dav1d-master` | AV1 解码依赖 |
| `REF_LIBASS` | `../libass` | ASS/SSA 字幕渲染 |
| `REF_LIBPLACEBO` | `../libplacebo` | GPU 视频渲染依赖 |
| `REF_AYVA_JS` | `../ayvajs` | TCode 行为和命名语义参考 |
| `REF_AYVA_WS_HUB` | `../ayva-websocket-hub` | raw WebSocket 桥接互操作参考 |
| `REF_OSR_EMULATOR` | `../osr-emu` | 设备运动模拟/可视化参考 |
| `REF_DORO_PLAYER` | `../DoroPlayer` | Android Funscript 播放器参考，不视为正确实现 |
| `REF_MULTI_FUN_PLAYER` | `../MultiFunPlayer` | Windows 多轴脚本、同步和 transport 设计参考 |
| `REF_INTIFACE_CENTRAL` | `../intiface-central` | 后续 adapter 参考，首期不集成 |
| `REF_TCODE_FIRMWARE` | `../TCodeESP32-master` | TCode 版本、轴、BLE/SPP/TCP/UDP/WebSocket 固件语义 |
| `REF_FUNSCRIPT_FLOW_UP` | `../../FunscriptFlow-Up` | Windows Funscript 播放逻辑参考 |

`REF_TCODE_FIRMWARE` 当前来源为 `allrobot/TCodeFirmware-Up` fork，基于 `jcfain/TCodeESP32`；目标分支由本地 checkout 和 T01/T07 的 commit 锁确认，不按目录名猜测项目身份。该仓库可能有用户未提交改动，任何会话不得整理、恢复或覆盖。

`REF_DAV1D` 当前是无 `.git` 元数据的源码快照，只用于只读核对。T01 已在
`native-build/sources.lock.json` 固定官方仓库的 1.5.4 commit；T02 必须从锁定
origin 获取，不得把本地快照当作可追溯构建输入。

跨盘的大型真实脚本/媒体库使用逻辑标识 `FUNSCRIPT_TEST_LIBRARY`，通过本地未跟踪配置解析。自动化测试只使用仓库内生成的中性小型 fixture，不提交真实库内容。

外部运行环境：

| 逻辑标识 | 解析方式 | 约束 |
| --- | --- | --- |
| `JDK_17` | 本地环境或未跟踪配置 | Gradle 构建 JDK |
| `ANDROID_SDK` | `local.properties` 或环境 | 不提交绝对路径 |
| `NDK_R29` | native build 本地配置 | T02 锁定确切版本 |
| `LINUX_BUILDER` | 本地 SSH 配置 | 凭据不入库 |
| `TEST_TABLET` | `ANDROID_SERIAL` | 仅本应用边界 |
| `TEST_OSR_DEVICE` | 本地串口/连接 profile | 不刷固件，先低幅测试 |

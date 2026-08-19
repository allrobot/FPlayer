# Progress

## 当前状态
- 状态：T31 自动化、实际媒体运行时接线、最新 `TEST_TABLET`、本机及 Android 跨主机真实 SMB2 验收通过；T32 已建立矩阵，当前 `UI-FEED`/`SCRIPT-LATENCY` 为 FAIL，真实媒体/设备与发布身份为 BLOCKED，详见 `docs/qa/t32-acceptance-matrix.md`
- 当前阶段：先完成 T32 四份子计划和 Task 0 证据资产，再按 UI、脚本、设备媒体、发布合规顺序实施；不改固件、native 锁、设备持久配置或无关模块
- 输入与验收：`TASKS.md` T32、`SPEC.md`、架构/安全文档、最早“方案定制”结论及用户授权的 `TEST_TABLET`、`TEST_OSR_WIFI`、`FUNSCRIPT_TEST_LIBRARY`；真实媒体只在 SAF 只读授权后读取
- 上次 checkpoint：T31 Android SMBJ 跨主机扫描及断线旧 generation 保持通过；服务、规则、fixture、测试包和进程已清零，认证外部 SMB 与物理设备仍未完成，详见 `docs/qa/t31-report.md`
- [2026-08-20] T32 设备媒体预检：运行时门控 SMB 主机/仪器测试改用 `FPLAYER_T32_SMB_*` 与 `t32-*` 逻辑标识；新增合成 SMB I/O 失败测试，确认失败码脱敏、工作 generation 标记 incomplete 且旧 current snapshot 保持不变。JDK 17 `:core:index:testDebugUnitTest --tests '*Smb*' --tests '*LocalSafScannerTest'`（48 Gradle tasks）及 `:core:device:testDebugUnitTest :feature:device:testDebugUnitTest`（60 Gradle tasks）通过。未访问凭据、真实 SMB、SAF、设备或外部资源。
- [2026-08-20] 修复真实 SMB 断线验收 harness 的 flat-share race：代理在首个目录 listing 返回后关闭连接，但无后续 listing 时扫描可能错误提交；wrapper 现在在关闭代理后注入稳定 `SMB_CONNECTION_CUT` I/O failure，并以合成 flat disconnect fixture 回归旧 snapshot 保持。报告中的 `CONNECTION_CUT_FAILED_AssertionError` 根因已覆盖；运行时 gate 未在本 shell 重跑，凭据未读取。
- 用户授权（2026-08-11/19）：允许按安全策略读写真实 SMB/媒体/设备并调用 ADB/transport；平板提供多轴/单轴逻辑来源，允许临时 `TEST_OSR_WIFI` WebSocket 和独立 SPP，凭据只在运行时使用
- 环境预检：平板可移除存储来源待 SAF 授权（盘点约 129/411 与 124/124 文件计数）；本机未确认唯一 `TEST_OSR_SERIAL`，串口/实物 gate 仍阻塞

## 已完成
- [2026-08-09] 确认 B+C 架构：libmpv + Android 原生 Funscript/TCode 设备层。
- [2026-08-09] 将后台锁屏循环播放纳入首期，确定 Foreground Service + MediaSession + 通知急停。
- [2026-08-09] 首期传输确定为 TCP、UDP、WebSocket、BLE、经典蓝牙 SPP、USB 串口。
- [2026-08-09] 排除首期 Intiface 和 Handy/Handyplug，保留后续适配接口。
- [2026-08-09] 推荐确定为可解释评分 + 时间衰减 + MMR + 5%~10% 探索，不采用模型。
- [2026-08-09] 循环事件拆为主动重播、前台自动循环、后台自动循环，不等同完成度。
- [2026-08-09] 创建 `app`、`core/*`、`feature/*` 多模块骨架和核心契约。
- [2026-08-09] 创建规格、架构、UI、安全、工具链和阶段任务文档。
- [2026-08-09] 用 JDK 17 完成 `testDebugUnitTest` 和 `:app:assembleDebug`；生成独立开发 APK。
- [2026-08-09] 空库“添加文件夹”已连接 SAF 目录选择，只获取本应用的持久只读授权。
- [2026-08-09] T01 锁定 20 个 native 源码/工具链输入、14 个构建配方、arm64-v8a/API 26、NDK r29 和 GPLv3 组合模式。
- [2026-08-09] 锁定文件为 `native-build/sources.lock.json`，人工审阅矩阵为 `docs/compliance/native-dependencies.md`，离线校验入口为 `native-build/verify-lock.py`。
- [2026-08-09] 新增 `native-build/prepare-inputs.py`、`native-build/build-arm64.sh` 和 `native-build/collect-evidence.py`：只从锁定 origin/commit 或 SHA-256 归档准备源码，构建 arm64-v8a，并收集 source/toolchain/ELF/checksum/licenses 证据。
- [2026-08-09] 修正 dav1d `1.5.4` annotated tag 对象，锁定实际 commit `54706fc6bc0cdecab7e9593974a4039cc038fca7`；修正 FreeType `VER-2-14-3` 实际 commit `0a0221a1347e2f1e07c395263540026e9a0aa7c7`。
- [2026-08-09] Ubuntu guest 根分区已扩展至约 78 GB，启用 8 GB 持久 swap；安装 Meson `1.11.2`（隔离 venv）、NDK r29、Clang/LLD、CMake、Ninja 和构建依赖。下载期间使用仅 SSH 会话存活的 loopback 反向代理，不写入仓库或持久环境。
- [2026-08-09] T02 首轮构建实际产出 9 个 AArch64 `.so`；证据检查确认全部 LOAD 段 16 KiB 对齐，DT_NEEDED 仅包含锁定共享库或 Android 系统白名单（含 `libmediandk.so`、`libOpenSLES.so`），25 份许可证/NOTICE 文件已收集。
- [2026-08-09] 为可重复性加入 `$WORK` 编译路径映射、稳定 cross file、FFmpeg `--disable-debug` 和 `.pc`/`.la` 元数据规范化；`verify-reproducible.sh` 两次全新工作树比较通过。
- [2026-08-09] 新增 `core/player-mpv/`：`LibMpvPlayer` 实现 `PlayerEngine`，JNI adapter 负责 mpv 生命周期、播放控制、snapshot 和 Surface `wid`；app 只依赖 adapter 模块，不直接调用 JNI。
- [2026-08-09] T03 AAR 含 adapter + 9 个 T02 `.so`；`libfplayer_mpv.so` 为 AArch64、全部 LOAD 对齐 16 KiB，DT_NEEDED 仅为审核闭包。3 个 JVM 单测与 androidTest APK 编译通过。
- [2026-08-09] T03 在 `TEST_TABLET` 用 2 秒中性合成视频验证 prepare/resume/play/pause/seek/speed/snapshot/release；测试包由 runner 自动清理。app APK 限定 arm64-v8a、`zipalign -P 16` 通过，覆盖安装并启动成功。
- [2026-08-09] T04 新增 `StrictFunscriptParser`：严格 UTF-8/JSON object、整数 `at`/`pos`、非空轨道、严格递增时间、全局重复时间、支持轴、重复轴及根 `L0` 冲突均返回带来源名和 JSON path 的稳定错误代码。
- [2026-08-09] T04 新增 `DefaultScriptMatcher`：同名/轴后缀不区分大小写，支持 11 个 TCode 0.3 轴及冻结别名表；规范轴 ID、别名顺序、无后缀和稳定 locator 构成确定优先级，冲突和后缀/内容不兼容均保留诊断。
- [2026-08-09] T05 新增 `ScriptInterpolator` 和 `MediaClockScriptScheduler`：以 `PlayerSnapshot.positionMs` 为唯一时钟，支持线性插值、正负偏移、切片映射、速度换算和最长 500 ms 短时前瞻。
- [2026-08-09] T05 扩展 `DeviceTarget` 的 generation/media time 及停止原因；pause、buffering、seek、loop、speed、切片和结束均生成可复现 stop/target 流，调度器不创建线程或访问具体 transport。
- [2026-08-09] T06 新增 `ScriptHeatmapDownsampler`：按请求窗口二分定位动作，按时间分桶保留首/末/最小/最大真实点，并为窗口两端生成精确插值点；多轴排序稳定且每轴输出受硬预算限制。
- [2026-08-09] T07 新增 TCode 0.2/0.3/0.4 版本模型、严格版本识别和协商器；查询固定为 `D1` + LF，声明版本与设备响应不一致时稳定拒绝。
- [2026-08-09] T07 新增固件轴注册表和 `TCodeEncoder`：0.2 使用三位位置，0.3/0.4 使用四位位置，百分比半向上舍入，正 duration 编为 `I`，多轴帧以空格分隔并只追加一个 LF。
- [2026-08-09] T08 新增 `DeviceSafetyController`：profile 轴白名单、范围钳制/反转、500 ms duration/未来时域、单调媒体时间、旧 generation 丢弃和按轴合并均在编码前执行。
- [2026-08-09] T08 新增确定性 `drain`：调用方注入墙钟/媒体时钟/generation，普通帧受最小发送间隔、命令数和字节预算约束；拥塞、时间错误、sink 异常、断连和 release 都清队列。
- [2026-08-09] T08 停止策略支持 HOLD、`DSTOP` 和按安全范围 CENTER；停止抢占普通命令，同一活动周期只推进一次 generation/发送一次停止序列，TCode 0.2 禁止不支持的 `DSTOP`。
- [2026-08-09] T09 新增 `DeviceTransport`、状态/故障/接收回调和共享 `BoundedFrameQueue`；普通帧有界排队，急停清除普通积压并置于队首，断开/失败/重连不保留业务重放队列。
- [2026-08-09] T09 新增 `TcpDeviceTransport`、`UdpDeviceTransport` 和 `RawWebSocketDeviceTransport`：连接与写 watchdog、TCP/WS 半开/EOF 监视、UDP connected datagram、RFC 6455 upgrade/masking/ping/close、可选 `wss` 平台证书及主机名校验、epoch 隔离旧连接迟到事件。
- [2026-08-09] T09 app manifest 声明 `INTERNET`；transport 诊断只输出类型、状态和待发数量，不记录地址、URL、凭据或帧内容。
- [2026-08-09] T10 选定 `com.github.mik3y:usb-serial-for-android:3.9.0`（MIT，tag `v3.9.0` / commit `e1018ab31c118b7d2e15fc7c2e9c5b19f33eb1f1`），通过 JitPack 解析；未复制上游源码。
- [2026-08-09] T10 新增 `UsbSerialDeviceTransport`、`UsbSerialDiscovery`、USB profile 参数和稳定故障码；精确 VID/PID + 用户确认 `deviceId` + port index 过滤，动态 Host 权限请求、连接超时、detach 广播关闭和读写循环、无业务帧重放。
- [2026-08-09] T10 新增 5 个注入式 USB transport 单测，覆盖收发、权限拒绝/脱敏、detach 后重连空队列、写超时、普通背压和急停 FIFO；模块测试共 41 个通过。
- [2026-08-09] T10 验证：`testDebugUnitTest` 全工程 80 个测试、0 失败；`:core:device:testDebugUnitTest` 41 个、0 失败；`:app:assembleDebug` 通过。APK 45,191,172 字节，SHA-256 `A225C8CA3EC0B4A339ACF83E557BDB6C4FD66BA4CA4F270F2E65F33E02DD968C`；`python native-build/verify-lock.py` 通过；受控源码/文档输入 79 个无尾随空格、0 临时文件，`git diff --check` 通过。
- [2026-08-09] T10 未执行 ADB、真实 `TEST_TABLET`、真实 `TEST_OSR_DEVICE` 或物理串口；未发送运动命令、烧录固件或写持久设备配置。仅使用注入式后端和本机 Gradle/JVM。
- [2026-08-09] T11 只读核对 `REF_TCODE_FIRMWARE` BLE handler：TCode profile 使用 service `ff1b451d-3070-4276-9c81-5dc5ea1043bc`、write characteristic `c5f1543e-338d-47a0-8525-01e3c621359d`、`WRITE_NR`，不假定该特征支持通知；通用 BLE selector 支持显式 notify/indicate characteristic。
- [2026-08-09] T11 新增 `BleDeviceTransport`、`BleDiscovery`、`TCodeEsp32BleProfile`：Android 12+ `BLUETOOTH_SCAN`/`CONNECT` 与 API 30 以下位置权限模型；已确认 address 直连，否则按 service/name 过滤扫描；GATT service/characteristic 校验、写类型校验、CCCD 订阅、总连接超时、可取消连接、MTU 协商和稳定 BLE 故障码。
- [2026-08-09] T11 BLE 写入按协商 `MTU - 3` 保序分包，异步通知进入 receiver；单包失败/超时、蓝牙关闭、扫描/GATT/订阅/MTU 失败均清空共享有界队列并失败，急停抢占普通帧 FIFO，显式重连从空队列开始，不重放断线动作。
- [2026-08-09] T11 `:core:device:testDebugUnitTest` -> 48 个测试、0 失败；覆盖分包收发、权限/蓝牙/GATT/扫描/订阅/MTU 稳定故障、写超时、背压、急停、detach 等效断连和重连无重放。
- [2026-08-09] T11 JDK 17 `testDebugUnitTest` 全工程 -> 87 个测试、0 失败；`:app:assembleDebug` 通过。APK 45,269,837 bytes，SHA-256 `DAD44D08C7CE4F6416ED90D55E58103FA511AA5B593505F1FC19A1A83AD128B5`。
- [2026-08-09] T11 合并 manifest 含可选 `android.hardware.bluetooth_le`、Android 12+ BLE 权限（`neverForLocation`）和 API 30 以下兼容权限；`python native-build/verify-lock.py` 通过；79 个受控源码/文档输入无尾随空格、0 临时文件，`git diff --check` 通过。
- [2026-08-09] T11 未执行 ADB、真实 `TEST_TABLET`、真实 `TEST_OSR_DEVICE` 或 BLE 扫描/动作；未配对设备、发送运动命令、修改持久配置或固件。仅使用注入式 BLE backend 和本机 Gradle/JVM。
- [2026-08-09] T12 只读核对 `REF_TCODE_FIRMWARE` Bluetooth handler：经典蓝牙设备名为 `TCodeESP32`，使用 Arduino `BluetoothSerial` 读写换行分隔 TCode；参考 checkout HEAD 仍为 `20b1e0e845053f41433cf3b4b565294e14fb3355` 且保持既有脏状态，未写入。
- [2026-08-09] T12 新增 `SppDeviceTransport`、`SppDiscovery`、`TCodeEsp32SppProfile`：Android 12+ `BLUETOOTH_CONNECT`、仅枚举/精确选择 `bondedDevices`、标准 SPP UUID、安全 RFCOMM socket、可取消连接超时和双向流；不扫描、不调用 `createBond`、不连接未知设备或依赖 Intiface。
- [2026-08-09] T12 权限、蓝牙关闭、未配对、socket/连接/读写/EOF 使用稳定故障码；断连清共享有界队列，急停抢占普通积压并保持 FIFO，重连不重放。共享 watchdog 修正写超时关闭 socket 与 monitor EOF 竞态，稳定保留 `WRITE_TIMEOUT`。
- [2026-08-09] T12 `:core:device:testDebugUnitTest` -> 57 个测试、0 失败，其中新增 9 个 SPP 测试；JDK 17 全工程 -> 96 个测试、0 失败；`:app:assembleDebug` 通过。APK 45,277,373 bytes，SHA-256 `E501819AF7F3098C79D5161074E7B798EB55B4B2706C31E76B842A350840F304`。
- [2026-08-09] T12 manifest 将经典蓝牙 feature 声明为可选；native 锁 20 个源码/14 个配方通过；85 个受控源码/文档输入无尾随空格、0 临时文件，`git diff --check` 通过。未调用 ADB、真实平板/蓝牙/TCode 设备，未配对或发送运动命令，未修改固件或持久配置。
- [2026-08-09] T13 新增设备 profile/UI 状态、Android backend 和 `DeviceSession`，app 四栏导航可进入设备页；支持 TCP/UDP/WebSocket/BLE/SPP/USB、动态蓝牙权限、opaque 设备 id、轴启用/40..60 默认范围/反转、5% 默认且 10% 硬上限的单轴测试、确认框和脱敏诊断。
- [2026-08-09] 参考播放器对比修正：连接中和测试中始终可断开；新增保持 transport 连接的无确认 emergency 归中按钮并取消测试；早期连接异常稳定映射 `CONNECT_FAILED`；手机 transport 改为两列三行并明确显示 `SPP 经典蓝牙`，HLS/VR、多设备、延迟/频率和持久固件设置留待对应后续阶段。
- [2026-08-09] T13 `:feature:device:testDebugUnitTest` -> 12 个测试、0 失败；JDK 17 全工程 -> 108 个测试、0 失败；`:app:assembleDebug` 通过。APK 52,347,201 bytes，SHA-256 `E1A45D735D42F30D5E1CE3E267ECA192E12BA5ADC5643B224C98A507166DE8B9`；APK manifest 含 `BLUETOOTH_CONNECT`/可选经典蓝牙 feature，dex 含 SPP transport。
- [2026-08-09] 五张假状态截图覆盖手机/平板横竖屏及安全测试确认，未见重叠/截断；ADB 仅用于 `TEST_TABLET` 安装 debug APK 和渲染截图，蓝牙权限未授予，未调用发现/连接或运动命令，临时截图已删除且 app 已 force-stop。
- [2026-08-09] T13 native 锁 20 个源码/14 个配方通过；94 个受控源码/文档输入无尾随空格、0 临时文件，`git diff --check` 通过；只读参考标签已关闭，外部 Syncopathy 临时 checkout 未修改且未进入仓库。
- [2026-08-09] T14 `core/index` 新增 Room 2.8.4 v2 数据库、11 个逻辑实体和 DAO；扫描版本化行使用逻辑 id + generation 复合主键，只有 `commitScan` 事务切换 `Source.currentScanGeneration`，中断 generation 不影响旧快照。
- [2026-08-09] T14 物化顺序用 staged header/连续去重 rows 原子发布；播放状态、行为事件和推荐聚合独立于扫描 generation。v1→v2 迁移增加推荐复现字段，正式 v1/v2 schema 已导出。
- [2026-08-09] T14 `:core:index:testDebugUnitTest` 4 个测试、0 失败；真实 v1 Room 文件迁移后通过 v2 全 schema 校验，2,500 条合成媒体提交/顺序发布/分页定位约 0.213 s。JDK 17 全工程 112 个测试、0 失败，`:app:assembleDebug` 通过。
- [2026-08-09] T14 native 锁 20 个源码/14 个配方通过；101 个受控源码/文档输入无尾随空格、0 临时文件。APK 52,552,774 bytes，SHA-256 `F4D16494776F7813B957F0C4C1D90D60B6EA2640DF896187C534E62DDE2EF647`；未调用 ADB、SAF/SMB、真实媒体或设备 transport。
- [2026-08-09] T15 新增 `SafPermissionStore`、`AndroidSafDocumentTree` 和 `LocalSafScanner`：只持久化 SAF read grant，只查询 document metadata；按规范路径和 document id 稳定遍历，以 source/document id 哈希生成逻辑 ID，并批量写入工作 generation。
- [2026-08-09] T15 支持同目录 Funscript/轴后缀匹配及新增、修改、删除统计；取消、权限丢失和 IO 失败只放弃工作 generation，恢复使用更高 generation，旧 current 索引、行为数据和缩略图均不删除。
- [2026-08-09] T15 `:core:index:testDebugUnitTest` 7 个测试、0 失败；2,500 条/25 目录 fixture 扫描报告约 5.288 s。JDK 17 全工程 115 个测试、0 失败，`:app:assembleDebug` 和 configuration cache 保存通过。
- [2026-08-09] T15 native 锁 20 个源码/14 个配方通过；106 个受控输入无尾随空格、0 临时文件/敏感本机字面量。APK 52,616,733 bytes，SHA-256 `3A71574D6E0CBDB94CEB202BE5F5DD3A68F24E2753D2D79978CDB00772612625`；未调用 ADB、真实 SAF/SMB、媒体或设备 transport。
- [2026-08-10] T16 新增 `BoundedMediaWorkQueue`、`MediaMetadataProbe`、`MetadataThumbnailPipeline` 和 `ThumbnailCache`：当前/相邻媒体优先、有界可取消处理、失败隔离、metadata 回写、缩略图 key 指纹和同目录原子发布。
- [2026-08-10] T16 清理仅在 DAO 确认目标 generation 为 current 后执行，并按 current `READY` 引用集删除孤儿；取消、未提交 generation 和空/损坏缩略图不会清理旧缓存。
- [2026-08-10] T16 `:core:index:testDebugUnitTest` -> 10 个测试、0 失败；覆盖优先级淘汰、metadata/缩略图发布、取消、失败继续和提交后清理门禁。
- [2026-08-10] T17 新增 `StableMediaSorter` 和 `MaterializedOrderBuilder`：覆盖标题、修改时间、最后播放、状态、时长、大小、分辨率、路径的双向排序，固定 NULLS LAST、稳定次级键及 scope 过滤。
- [2026-08-10] T17 物化顺序继续使用 Room header + contiguous rows 事务发布；排序切换不改变 current media 或播放状态，当前项可通过新 header ordinal 重定位。
- [2026-08-10] T17 `:core:index:testDebugUnitTest` -> 12 个测试、0 失败；全工程 `testDebugUnitTest` 与 `:app:assembleDebug` 均通过。

## 未完成

- 最终 NOTICE/THIRD_PARTY_LICENSES 在 T32 由实际发布构建生成。
- 实际媒体后台长循环仍缺媒体库到 service 的装载及 native prepared/end event 接线；平板当前 0 来源/0 媒体，不能以纯状态机替代。
- 真实 SMB、真机竖屏和低幅 `TEST_OSR_DEVICE` 仍缺可用目标；平板过滤发现 SPP/BLE/USB 均为 0，未连接或发动作。

## 关键决定与原因

- 决定：产品仓库为当前目录，不嵌入相邻上游仓库。原因：隔离许可证、历史和本地脏状态。
- 决定：包名暂定 `io.github.fplayer.android`。原因：提供独立、可安全卸载的开发包边界；发布前可通过 ADR 修改。
- 决定：JDK/SDK/NDK 只通过逻辑标识或本地未跟踪配置解析。原因：禁止提交开发机绝对路径。
- 决定：`compileSdk/targetSdk 37`、AGP 9.3.0、Gradle 9.5.0、JDK 17。原因：与已安装 SDK 及官方兼容矩阵一致。
- 决定：GPL-3.0-or-later 暂定。原因：为计划中的 GPL 组件组合提供保守兼容边界；发布前仍需矩阵审查。
- 决定：native 采用 GPL 路线；FFmpeg 启用 GPL+version3，mpv 启用 GPL，禁止 nonfree/OpenSSL/FDK AAC。原因：与项目许可证及 mbedTLS Apache-2.0 组合兼容。
- 决定：T02 不直接使用相邻参考 checkout，只从锁定官方 origin/commit 或校验过 SHA-256 的归档构建。原因：避免本地状态和上游 HEAD 漂移污染可重复性。
- 决定：首个 native 产物只支持 arm64-v8a/API 26，并要求 16 KiB page 对齐。原因：匹配 T02 范围、产品 minSdk 和当前 Android 发布要求。
- 决定：Funscript 解析不排序、钳制、默认缺失字段或隐式转换小数/字符串。原因：调度和设备安全层必须只接收可复现的已验证输入。
- 决定：根 `actions` 映射 `L0`；嵌入轴限定 TCode 0.3 的 11 个大写 ID；多候选选择必须附带被舍弃 locator 诊断。原因：避免跨播放器命名差异导致静默错轴。
- 决定：脚本调度由播放会话串行调用 tick，不自行持有线程或墙钟；正 offset 延后脚本，speed 只换算命令墙钟 duration。原因：媒体 seek、倍速和循环必须由 libmpv 时钟统一定义。
- 决定：每次不连续事件推进 generation，`DeviceTarget` 带 generation 和媒体目标时间。原因：T08 可证明丢弃 seek/loop 前的晚到命令。
- 决定：热力图按可见窗口即时降采样，不常驻复制多分辨率整轨缓存；额外内存为每轴 `O(maxSamples)`。原因：缩放可恢复局部极值，同时避免长脚本可视化数据无界增长。
- 决定：TCode 编码器拒绝而不钳制未知轴、越界百分比、负或版本不可表示 duration；0.2 只注册 L0..L3/R0..R2/V0..V1，0.3/0.4 只注册当前固件公开的 14 轴。原因：协议解析器可表示的地址不等于设备实现的已知轴，钳制属于 T08 安全层。
- 决定：T07 只编码 interval，不生成依赖设备当前位置的 speed 或 0.4 easing/gradient；0.4 最大 interval 固定为固件 `strtol` 可接受的 `2,147,483,647`。原因：媒体调度器提供 duration，未协商的扩展不能静默改变运动语义。
- 决定：安全控制器不创建线程或读取系统时间，所有 drain 时间均由上层串行上下文注入；队列以轴数为硬容量并只保留每轴最新目标。原因：测试可复现，且慢 transport 不能积累历史动作。
- 决定：控制器异常急停后推进 generation，旧调度目标只能丢弃；断连后 submit/drain 均拒绝，重连必须按控制器 generation 重新采样。原因：禁止错误或断线期间的命令在恢复后重放。
- 被否方案：把后台循环当作高价值完成信号。否决理由：它主要反映设置和锁屏时长，不等于主动偏好。

## 关键路径

- `SPEC.md` -> 产品范围和验收边界
- `TASKS.md` -> 可交给独立 AI 会话的阶段任务
- `docs/architecture/overview.md` -> 模块和运行时数据流
- `docs/architecture/background-playback.md` -> 前后台/设备停止状态机
- `docs/architecture/media-index.md` -> 2,000+ 媒体索引和物化顺序
- `docs/architecture/recommendation.md` -> 纯算法推荐定义
- `docs/ui/interaction-spec.md` -> 手势和控件状态机
- `docs/security/test-device-policy.md` -> 真实设备命令红线
- `docs/references/source-map.md` -> 上游源码逻辑标识
- `docs/compliance/native-dependencies.md` -> T01 许可证选择、链接闭包和 NOTICE 来源
- `native-build/sources.lock.json` -> T02 唯一允许的 native 源码、构建开关和产物证据输入
- `docs/toolchain/native-build.md` -> T02 Linux guest 前置、执行命令、输出和可重复性验收
- `core/player-mpv/` -> T03 libmpv JNI adapter、Gradle native staging 和合成视频测试
- `docs/architecture/funscript-format.md` -> T04 严格字段规则、轴别名表、匹配优先级和诊断语义
- `core/script/` -> T04 解析器、匹配器、错误契约和合成测试向量
- `docs/architecture/script-scheduler.md` -> T05 插值、偏移、切片、前瞻和 stop/generation 语义
- `docs/architecture/script-heatmap.md` -> T06 窗口边界、时间分桶、极值保留和多轴预算
- `docs/architecture/tcode-protocol.md` -> T07 三版本字段、轴注册表、协商、帧和拒绝语义
- `core/device/` -> T07/T08 协议模型、编码器、安全控制器、设备契约及录制帧测试

## 环境事实

- Windows 默认 `JAVA_HOME` 不是 JDK 17，构建命令必须显式选择 `JDK_17`。
- 已安装 Android SDK 37 和 Build Tools 36.0.0；当前进程未发现 `NDK_R29`。
- 现有 Android Studio 版本过旧，命令行构建不依赖它；IDE 使用前需升级。
- `LINUX_BUILDER` 为 VirtualBox Ubuntu 24.04，4 CPU/8 GB；VDI、guest 分区已扩到约 78 GB，8 GB swap 已启用。
- `LINUX_BUILDER` 已安装 NDK `29.0.14206865`（Clang/LLVM revision `5e96669f06077099aa41290cdb4c5e6fa0f59349`）和 Meson `1.11.2`；Meson 版本已进入 T02 锁。
- 用户已授权后续按 `docs/security/test-device-policy.md` 使用 `TEST_TABLET`；T01/T02 不需要安装或访问真实设备。
- `TEST_TABLET` 当前为 arm64/API 33；T03 测试包已由 runner 自动卸载，正式项目包 `io.github.fplayer.android` 已安装并可启动。

## 验证结果

- 工具链基线（JDK 17、AGP 9.3.0、Gradle 9.5.0、SDK 37、arm64-v8a）已核验；`python native-build/verify-lock.py`、敏感字面量扫描、尾随空格扫描和 `git diff --check` 均通过。
- T01-T18 的 native、协议、设备安全、Room/SAF/SMB、缩略图与稳定排序测试和构建结果已在前序 checkpoint 记录；相邻参考仓库保持只读，未保存凭据或真实媒体路径。
- T03/T13/T20/T21 已按安全策略在 `TEST_TABLET` 做过安装、启动、MediaSession/服务与截图验证；真实 TCode 设备、串口动作和固件仍未操作。
- T18 锁定 SMBJ `0.14.0`；新增 `SmbSourceConfig`、`SmbSourceRepository`、`CredentialStore`、Android Keystore AES-GCM 实现、SMBJ metadata-only tree 和 SMB generation scanner。离线状态不推进 current generation，连接/会话/共享按逆序关闭，密码读回后清零。
- T18 `:core:index:testDebugUnitTest` -> 48 个测试全部通过，覆盖既有索引/SAF/T17 测试以及 SMB 配置、凭据副本/删除、离线快照保持、在线 generation 提交；SMBJ 0.14.0 compile classpath 已解析。
- T19 新增 `feature/feed/PlaybackSession.kt`：维护物化顺序引用、当前索引和 PREVIOUS/CURRENT/NEXT 三槽；相邻槽只 prepare，当前槽收到匹配 token 的 prepared 回调后才 play；排序 generation 变更按 mediaId 保持当前项，失效项回退到首个有效项，旧回调和解码错误不会串音；断点通过 `PlaybackCheckpointStore` 读写，媒体循环与自动连播分开。
- T19 `:feature:feed:testDebugUnitTest` -> 5 个测试、0 失败；覆盖三槽准备、快速滑动 token 隔离、排序重定位、失效/解码失败、断点和循环。
- T20 新增 `BackgroundPlaybackController`：后台开关、Activity 可见性、播放/暂停、停止设备和服务销毁统一走可测试状态协调器；后台关闭时 Activity 隐藏会暂停媒体并急停设备，退出后台会停止媒体、设备和服务。
- T20 新增 `app/PlaybackService`：media playback 类型 Foreground Service、平台 MediaSession、低优先级持续通知及播放/暂停、停止设备、退出后台三个 action；服务默认 `START_NOT_STICKY`，销毁时调用统一停止回调。
- T20 `AndroidManifest.xml` 增加 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`POST_NOTIFICATIONS`、`WAKE_LOCK` 与 `PlaybackService` 声明；`MainActivity` 绑定服务并转发可见性，不拥有播放器实例。
- T20 `:feature:feed:testDebugUnitTest` -> 8 个测试、0 失败；`--no-configuration-cache :app:assembleDebug` -> 成功；`git diff --check` -> 通过。
- T21 扩展 `BackgroundPlaybackController`：Activity 可见性恢复、后台开关变化、wake/resource 获取释放、force-stop 终态，以及 stopPlayback 只发送一次；`PlaybackSession` 增加显式 `play()` 入口。
- T21 `PlaybackService` 现在持有单一 `LibMpvPlayer`/`PlaybackSession` 根，当前槽接入 native engine，相邻槽使用无操作播放器；通知 action 通过纯 Kotlin `PlaybackIntentCommands` 解码，`onTaskRemoved` 和 `onDestroy` 走统一停止/释放路径。
- T21 新增 `BackgroundPlaybackControllerTest` 5 项和 `PlaybackIntentCommandTest` 1 项；`:feature:feed:testDebugUnitTest`、`:app:testDebugUnitTest`、`:app:assembleDebug --no-configuration-cache` 全部通过。
- T21 `TEST_TABLET`（API 33）安装当时 debug APK 后启动成功，MediaSession/PlaybackService 可见，过滤日志无 `FATAL EXCEPTION`；Home 后服务仍可查询，随后仅对本项目执行 `am force-stop`，进程和媒体会话终止。
- T22 新增 `feature/feed/FeedPagingStateMachine.kt`：实现 `Idle/Tracking/DirectionLocked/Settling/Active`、1.25 方向锁、18% 页面阈值、1100 dp/s 且至少 24 dp 速度触发、横滑 20% 进网格、95% settle 才提交 Active、边界阻尼且不循环；状态机不直接启动播放器。
- T22 新增 `FeedPagingStateMachineTest` 7 项，覆盖方向锁、回弹、距离/速度阈值、横滑网格、边界和连续快滑；`:feature:feed:testDebugUnitTest` 18 项、全工程 `testDebugUnitTest`、`:app:assembleDebug --no-configuration-cache` 全部通过。
- T23 新增 `PlaybackOverlayState.kt` 与 reducer：普通/清屏模式、播放/暂停、倍速循环、喜欢/收藏/点踩、设备停止状态和顶部/侧栏/底部命令均为可测试纯状态；设备停止标记不被清屏隐藏。
- T23 `MainActivity` Home 接入空库播放叠层原型：顶部工具带、右侧操作、左下三行信息、清屏四控件和 SAF 添加文件夹入口；使用显式 `Saver` 保持 Activity 重建状态，所有图标按钮有 content description。
- T23 `PlaybackOverlayStateTest` 4 项；`:feature:feed:testDebugUnitTest`、`:app:assembleDebug --no-configuration-cache` 通过。`TEST_TABLET` 安装/启动 debug APK 成功，MediaSession 可见，过滤日志无 `FATAL EXCEPTION`；PowerShell 截图重定向损坏，未形成可审阅截图证据，临时文件不在仓库。
- T24 新增 `ProgressInteractionState.kt`：`BAR/HEATMAP` 共用 `PLAYING/PAUSED/PRESSED/DRAGGING/SETTLING` 状态，拖动只更新 preview，抬起生成 seek token，确认必须匹配最新 token；Settling 忽略 position 回调并阻塞 Feed 翻页，0%/100% 触觉边缘各只产生一次。
- T24 新增 `ProgressInteractionStateTest` 4 项，覆盖 preview/confirmation、过期 token、端点触觉、Settling 阻塞和 BAR/HEATMAP 共享状态；全工程 `testDebugUnitTest`、`:app:assembleDebug --no-configuration-cache` 和 `git diff --check` 通过。
- 工具链修复：新增 `native-build/capture-screenshot.ps1`，通过 `System.Diagnostics.Process` 的 `StandardOutput.BaseStream` 直接复制 `adb exec-out screencap -p` 字节，避免 PowerShell `>` 将 PNG 首字节 `0x89` 转为 UTF-8 replacement bytes；脚本会校验 PNG signature，并已在 `TEST_TABLET` 验证可被图像查看器解码。
- T25 新增 `LongPressPlaybackSettingsStateMachine`：中央区域保持 500 ms 后打开播放设置；竖屏映射 bottom sheet、横屏映射 side sheet，旋转只改变 placement 并保留面板设置。
- T25 边缘临时倍速在左右 20% 区域保持 350 ms 后切换到配置倍率（默认 2x），抬手立即恢复；系统返回边缘、方向锁和进度拖动优先并取消长按/边缘计时；所有事件使用注入单调时间。
- T25 新增 `LongPressPlaybackSettingsStateTest` 5 项，覆盖面板/旋转、边缘倍速恢复、方向/进度抢占、系统返回和设置更新；`:feature:feed:testDebugUnitTest`、全工程 `testDebugUnitTest`、`:app:assembleDebug --no-configuration-cache` 通过。
- T26 新增 `CustomPlaybackSettingsStateMachine`：已选/未选稳定分区，加减和无障碍上移/下移/添加/移除共用提交路径；拖拽保持固定行高，以行中点生成全宽插入线，72 px 边缘区提供 180/720 px/s 两级自动滚动，取消或旋转清除未提交预览。
- T26 注入 `CustomPlaybackSettingsStore`，区分未保存与已提交空选择，只在实际顺序变化后持久化；新增 6 项测试。`:feature:feed:testDebugUnitTest` 37 项、全工程 `testDebugUnitTest` 和 `:app:assembleDebug --no-configuration-cache` 通过；APK 56,366,140 bytes，SHA-256 `9EF3F1439B27D41E9382668E4CE0B8C69C41501F4DC28E6117627A2C773C5B59`；源码空白/临时文件扫描和 `git diff --check` 通过。
- T27 新增 `feature/library/LibraryGridState.kt`：文件夹头部、ALL/SCRIPTED 视频筛选、GRID/DOUBLE_COLUMN 布局、媒体 ID 稳定索引、当前/刚刚看过标记、进度 permille 和首页两级返回；定位按 ID 映射为精确 visible index/row，不依赖估算偏移。
- T27 新增 `LibraryGridScreen.kt`：固定 3:2 tile、默认三列/双列两行元数据、脚本标签、刚刚看过半透明中性遮罩、固定底部进度条、无障碍描述和筛选/布局 segmented controls；`MainActivity` 接入 Feed 左滑/网格按钮与首页返回上下文。
- T27 新增 6 项确定性测试，覆盖 2,001 条目两布局跳转、脚本筛选、进度更新、重排后按 ID 重定位、缺失媒体和自适应列数；`:feature:library:testDebugUnitTest`、全工程 `testDebugUnitTest`、`:app:assembleDebug --no-configuration-cache` 均通过。
- T27 Debug 验收新增 `LibraryScreenshotActivity`（仅 debug manifest），使用 2,001 条中性合成条目生成手机/平板横竖四张截图；`TEST_TABLET` API 33 覆盖安装并启动成功，真实 MainActivity 左滑进入网格，无障碍树含“脚本/双列/当前文件夹”，过滤日志无 `FATAL EXCEPTION`。未读取真实媒体、未清除应用数据、未访问其它应用。
- T28 新增 `LibraryCatalogStateMachine`、`LibraryIndexRepository` 和 `LibraryCatalogScreen`：只读聚合所有 current generation，支持所有文件夹/含视频文件夹/全部视频、文件夹下钻、历史/喜欢/收藏/点踩、八字段双向稳定排序、网格/双列、多词搜索空态和独立删除确认；Feed 抽屉/搜索入口与相册导航接通。
- T28 `feature:library` 12 项测试、全工程 `testDebugUnitTest` 和 APK 构建通过；`TEST_TABLET` 主 Activity 无障碍树覆盖三视图/抽屉集合/搜索，过滤日志无崩溃，四 viewport 合成截图人工检查无重叠；未读取或删除真实媒体，测试后 force-stop 本项目包。
- T29/T30 新增 `core:recommendation`：从当前 Room generation 读取媒体/文件夹/脚本、播放状态、交互事件和推荐聚合，执行版本化可解释评分、21 天半衰期、completion/coverage、重播饱和、快跳独立衰减、冷启动分层、MMR（lambda 0.72）、5%~10% 确定性探索和画像重置；点踩候选过滤，结果带分项分数与本地原因；9 项 golden/性质测试及模块/全工程单测、`:app:assembleDebug --no-configuration-cache` 均通过。
- T31 新增故障/长循环/native request 隔离及 JVM/Android 运行时门控 SMB 测试，补齐 Room 队列、libmpv prepared/EOF、实际循环和真实 SMB2 断线快照保持；平板通过 native instrumentation/5 分钟屏幕 OFF 181 次循环、通知门控、约 4 分半断线续播、旋转、trim、cleanup/force-stop 和跨主机 SMB instrumentation。全工程 36 suite/199 项为 0 failure、0 error、1 个预期门控 skip，本机与平板 SMB 门控各 1/1 通过；APK 56,645,232 bytes，SHA-256 `8185B51C4182465670C9FE9CD0F6FB32E4E2139DFFF45871163EF118CDD035B8`。

## 风险与阻塞
- 已核验 VirtualBox `Ubuntu 24.04`：VDI 为 81,920 MB、VM 可 headless 启动、桥接网络邻居和 SSH 22 端口正常；后续命令使用 guest `admin` 会话密码，不写入项目文件。
- 若 T03 adapter 引入额外动态库，必须重新执行 T01 的许可证和 DT_NEEDED 矩阵审查。
- `LibMpvPlayer` 仍是进程内单 native 实例；`playlist_entry_id` request 隔离已通过两项真机 instrumentation 和 5 分钟后台 EOF 重载，后续多实例仍需 per-handle bridge。
- Android 13 测试设备低于 target API；通知权限、蓝牙权限和前台服务需按系统版本分支测试。
- `DeviceFrameSink.write` 当前是同步契约；T09 transport 在自身守护线程边界实现有界写入和 watchdog，失败上报监听器，不能在 sink 内无限阻塞或重放旧帧。
- 0.2 没有固件协议急停，profile 只能 HOLD 或 CENTER；真实设备首次联调仍必须默认低幅安全范围并验证机械停止行为。
- Room DAO 和 `LocalSafScanner` 当前是同步契约；调用方必须在非主线程执行。正式 schema v1/v2 是迁移输入，后续版本不得删除或 destructive fallback。
- SMBJ 同步元数据扫描和本机/Android 跨主机真实协议断线已通过；物理私网 3 个 SMB 主机中 2 个可枚举共 11 个磁盘共享但均不可读/不可写且无匹配凭据，临时 Impacket 用户认证也不能代表真实 NAS，外部认证来源仍阻塞。

## 下一步

1. 执行 UI/Feed、Funscript 延迟/限幅、设备媒体和发布合规四份 T32 子计划。
2. 使用 `docs/qa/t32-acceptance-matrix.md` 逐项更新证据、清理和阻塞状态；完成每个功能变更后提交并推送到远端。
3. 认证外部 SMB、唯一 `TEST_OSR_DEVICE` profile、机械安全现场和生产发布链继续保持 BLOCKED，直到前置条件真实满足。

## 待用户确认

- 首次发布前确认最终应用名称、组织/作者标识、正式包名和生产签名来源；当前值只用于开发隔离。

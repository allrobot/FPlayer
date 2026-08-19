# T32 Device Connection And Real Media Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 先用可重复的 loopback 和录制帧证明六种 transport、SAF/SMB 扫描与停止语义，再在用户授权的 `TEST_TABLET`、`TEST_OSR_WIFI`、`TEST_OSR_DEVICE` 和 `FUNSCRIPT_TEST_LIBRARY` 逻辑资源上完成真实媒体验收；任何凭据、地址、物理端口、真实文件名和脚本内容都不进入仓库。

**Architecture:** 测试通过 `DeviceTransport`/`DeviceSafetyController` 的既有边界记录字节和终态，`DeviceSession` 负责连接生命周期，`LocalSafScanner`/`SmbScanner` 负责 generation 原子提交。真实媒体先绑定 `RecordingDeviceFrameSink` 并关闭实物输出，只有所有 loopback、解析、限幅、停止和帧顺序门通过后，才允许唯一 `TEST_OSR_DEVICE` profile 进入中心附近单轴低幅动作。SMB 凭据仅由运行时环境或未跟踪本地配置加载到 `CredentialStore`/Keystore。

**Tech Stack:** Kotlin/JVM 与 Android instrumentation、Java Room/SAF/SMBJ、Android Bluetooth LE/RFCOMM、Android USB Host、原生 TCP/UDP/WebSocket、Gradle 9.5/AGP 9.3/JDK 17、ADB。

**Spec:** `TASKS.md` T09--T13、T15/T18/T19--T21、T31/T32；`docs/architecture/device-layer.md`；`docs/architecture/media-index.md`；`docs/architecture/script-scheduler.md`；`docs/security/test-device-policy.md`；`docs/qa/t31-report.md`；`docs/superpowers/plans/2026-08-19-t32-master-acceptance.md` Task 3。

## Global Constraints

- 文档和测试只使用 `TEST_TABLET`、`TEST_TABLET_MEDIA_ROOTS`、`TEST_OSR_WIFI`、`TEST_OSR_DEVICE`、`TEST_OSR_SERIAL`、`FUNSCRIPT_TEST_LIBRARY` 等逻辑标识；禁止真实盘符、IP、SSID、密码、用户名、蓝牙地址、串口名、媒体名、locator、帧正文和绝对路径。
- 凭据只在进程环境、Android 系统权限页或未跟踪本地配置中提供；SMB 用户名/密码必须进入 `CredentialStore` 后立即清除原始字符数组，日志只记录认证成功/失败类别。
- 真实媒体只读。SAF 仅持久化本应用 read grant；SMB 仅扫描用户授权来源；测试 fixture 写入并清理本应用私有目录，不写共享盘或其他应用目录。
- 首次真实 TCode 动作必须唯一 `TEST_OSR_DEVICE` profile、机械空载、可目视、可立即断电、只启用一个已确认轴、中心附近、每条命令 `<=500 ms`；任何失败先停止控制器、清空队列、断开 transport。
- 所有设备动作只能经过 `DeviceSafetyController`：轴白名单、`0..100` 位置约束、profile 限幅/反转、最大未来时域 500 ms、最小发送间隔、急停优先级和 generation 丢弃不可被 UI/测试绕过。
- loopback/注入测试必须先于真实设备；loopback 任一失败、目标不唯一、权限未获批、凭据缺失或物理安全条件不满足时状态为 `BLOCKED`，不得以模拟结果替代真实通过。
- 延迟只记录可验证响应样本：WS ping/pong 或 echo，SPP/BLE 写完成/响应；无响应标记 `UNMEASURED`。使用稳健中位数和 `-round(rtt/2)` 提前量，保留正负手工偏移，不把墙钟/UI 帧时间当媒体时间。
- 每个任务结束执行相关单测/instrumentation、`assembleDebug`（涉及发布门时另行执行 release）、`git diff --check`、尾随空白和临时文件扫描；真实测试后撤销临时权限并清理本应用资源。

## File Map

- `core/device/src/test/java/io/github/fplayer/core/device/NetworkDeviceTransportsTest.kt`、`BleDeviceTransportTest.kt`、`SppDeviceTransportTest.kt`、`UsbSerialDeviceTransportTest.kt`：loopback/注入 transport、背压、断连、急停、旧 generation 和诊断脱敏证据。
- `core/device/src/main/java/io/github/fplayer/core/device/DeviceTransport.kt`、`DeviceSafetyController.kt`、各 transport 实现：仅在测试暴露的接口缺口确实阻碍验收时修改；不改变协议或安全默认值。
- `feature/device/src/main/java/io/github/fplayer/feature/device/AndroidDeviceBackend.kt`、`DeviceSession.kt`、`DeviceConfigurationModels.kt` 及对应测试：opaque endpoint、权限、会话终态和安全测试证据。
- `core/index/src/main/java/io/github/fplayer/core/index/saf/`、`core/index/src/main/java/io/github/fplayer/core/index/smb/` 及 `core/index/src/test/`、`src/androidTest/`：SAF/SMB generation、凭据封装、在线/离线/断线状态。
- `app/src/androidTest/`、`app/src/main/java/io/github/fplayer/android/`：`TEST_TABLET` SAF 选择、真实媒体播放/录制 sink、后台循环和清理夹具；只使用应用包边界。
- `docs/qa/t32-device-media-acceptance.md`、`docs/qa/t32-evidence-index.md`：最终脱敏矩阵与证据索引；不得保存原始日志、媒体名或地址。

### Task 1: 锁定 recorder 契约并完成六传输 loopback 矩阵

**Files:**
- Modify: `core/device/src/test/java/io/github/fplayer/core/device/NetworkDeviceTransportsTest.kt`
- Modify: `core/device/src/test/java/io/github/fplayer/core/device/BleDeviceTransportTest.kt`
- Modify: `core/device/src/test/java/io/github/fplayer/core/device/SppDeviceTransportTest.kt`
- Modify: `core/device/src/test/java/io/github/fplayer/core/device/UsbSerialDeviceTransportTest.kt`
- Modify: `feature/device/src/test/java/io/github/fplayer/feature/device/DeviceSessionTest.kt`
- Create: `core/device/src/test/java/io/github/fplayer/core/device/DeviceAcceptanceRecorderTest.kt`

**Interfaces:**
- Consumes: `DeviceTransport.connect()`, `disconnect()`, `write(frame: ByteArray, priority: DeviceFramePriority)`, `TransportListener.onStateChanged(state, failure)`, `DeviceSafetyController.submit(target)`, `drain(wallTimeMs, mediaTimeMs, currentGeneration)` and `stop(reason)`.
- Produces: `RecordedDeviceEvent(state: TransportState, failureCode: TransportFailureCode?, priority: DeviceFramePriority, byteCount: Int, generation: Long?)`（测试专用）和 `DeviceAcceptanceRecorder : DeviceFrameSink`，以及每种 transport 的 `PASS/FAIL` 摘要；录制器不得保存帧正文。

- [ ] **Step 1: 写 recorder 和失败门测试**

  用内存 `DeviceFrameSink` 只保存事件类别、字节数、优先级、generation 和单调时间，断言 `diagnostics` 不含 host、URL、Bluetooth address、串口标识或 payload。

- [ ] **Step 2: 运行 loopback/注入测试确认现状**

  ```powershell
  .\gradlew.bat :core:device:testDebugUnitTest :feature:device:testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
  ```

  预期 TCP/UDP/WS 模拟服务、BLE/SPP/USB 注入 backend 的 connect/write/disconnect 均通过；背压、写超时、远端 EOF、权限/蓝牙关闭/GATT 失败、USB detach、急停 FIFO 和重连无旧帧重放均有稳定故障码。

- [ ] **Step 3: 补齐最小测试缺口并复跑**

  只在失败测试覆盖的边界修改对应 test/backend 或窄接口；不得在 loopback 阶段访问真实网络、蓝牙、USB 或设备。预期 `:core:device:testDebugUnitTest` 与 `:feature:device:testDebugUnitTest` 0 failure/0 error，所有终态均清空 pending queue。

- [ ] **Step 4: 固定 loopback gate**

  将每种 transport 的命令、故障码、重连计数和 recorder 摘要写入 `docs/qa/t32-device-media-acceptance.md` 的 `DEVICE-*` 小节；任一失败将后续真实 gate 标记 `BLOCKED`。

### Task 2: 通过 SAF 扫描 `TEST_TABLET` 两类真实媒体并验证录制播放

**Files:**
- Read/Modify: `core/index/src/main/java/io/github/fplayer/core/index/saf/SafPermissionStore.java`、`LocalSafScanner.java`、`AndroidSafDocumentTree.java`
- Test: `core/index/src/test/java/io/github/fplayer/core/index/saf/LocalSafScannerTest.java`
- Create: `app/src/androidTest/java/io/github/fplayer/android/TestTabletMediaAcceptanceTest.kt`
- Modify: `app/src/main/java/io/github/fplayer/android/MainActivity.kt` only if the folder picker cannot persist two read-only grants

**Interfaces:**
- Consumes: `SafPermissionStore.persistReadOnly(uri: Uri)`, `grantedTrees(): Set<Uri>`, `LocalSafScanner.scan(sourceId: String, generation: Long, tree: SafDocumentTree, cancellation: Cancellation): ScanResult`, `PlaybackSession`, `DeviceAcceptanceRecorder` and the T32 Funscript matcher/scheduler outputs.
- Produces: `MEDIA-SD-MULTIAXIS` and `MEDIA-SD-SINGLEAXIS` counts/status with video/script counts, readable rate, same-stem match rate, unsupported-extension count, committed generation and recorder frame statistics; no filename or locator.

- [ ] **Step 1: 先用合成 SAF fixture 验证双来源和中断恢复**

  在 `LocalSafScannerTest` 使用中性视频/脚本 metadata tree，分别模拟多轴六轨集合和单轴集合；断言扫描中断产生 incomplete generation，旧 current snapshot 不变，恢复扫描使用更高 generation 原子提交。

- [ ] **Step 2: 在 `TEST_TABLET` 通过系统选择器授予两个目录只读权限**

  ```powershell
  $env:ANDROID_SERIAL = (Get-Content Env:ANDROID_SERIAL -ErrorAction Stop)
  if ([string]::IsNullOrWhiteSpace($env:ANDROID_SERIAL)) { throw 'TEST_TABLET serial is required' }
  .\gradlew.bat :app:assembleDebug --no-configuration-cache
  adb -s $env:ANDROID_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s $env:ANDROID_SERIAL shell am force-stop io.github.fplayer.android
  ```

  在系统文件夹选择器中分别选择 `TEST_TABLET_MEDIA_ROOTS` 的多轴与单轴逻辑目录；应用只调用 `persistReadOnly`，不请求 root 或遍历其他目录。预期两个 URI grant 均可读，权限丢失时返回稳定诊断并保留旧 generation。

- [ ] **Step 3: 只读盘点并验证脚本匹配**

  运行 `TestTabletMediaAcceptanceTest`，按 SAF document metadata 统计视频/脚本数量、可读率、匹配率和异常扩展名；将六轴来源期望轴集合限定为 `L0/L1/L2/R0/R1/R2`，单轴来源只要求其实际轴，未知轴必须产生诊断而不静默映射。

  ```powershell
  .\gradlew.bat :app:connectedDebugAndroidTest --tests '*TestTabletMediaAcceptanceTest' --no-configuration-cache
  ```

  预期两个来源均完成扫描并提交 generation，媒体/脚本读取失败按条目隔离；报告只有计数、扩展名类别和脱敏 checksum。

- [ ] **Step 4: 禁用实物输出后播放验证**

  对每类来源选择中性可读项，使用 `DeviceAcceptanceRecorder` 验证 prepare/play/pause/seek/speed/EOF/后台循环、generation 映射、脚本轴集合、停止原因和单轴缺失多轴回退。预期无真实 transport 写入，帧序列可按确定性媒体时钟重放；播放失败不触发设备动作。

### Task 3: 验收 `FUNSCRIPT_TEST_LIBRARY` 的运行时认证 SMB

**Files:**
- Read/Modify: `core/index/src/main/java/io/github/fplayer/core/index/smb/SmbSourceConfig.java`、`SmbSourceRepository.java`、`SmbScanner.java`、`SmbjDocumentTree.java`、`AndroidKeystoreCredentialStore.java`
- Test: `core/index/src/test/java/io/github/fplayer/core/index/smb/RealSmbAcceptanceTest.java`
- Test: `core/index/src/androidTest/java/io/github/fplayer/core/index/smb/RealSmbInstrumentedAcceptanceTest.java`
- Test: `core/index/src/test/java/io/github/fplayer/core/index/smb/CredentialStoreTest.java`、`SmbScannerTest.java`

**Interfaces:**
- Consumes: `SmbSourceConfig.defaults(sourceId, displayName, host, share, credentialRef)`, `SmbSourceRepository.create/update`, `SmbScanner.scan(config, generation, tree, cancellation)`, `CredentialStore.put/get/delete`, `SmbDocumentTree.isOnline/root/children`。
- Produces: `SMB-REAL` evidence with authenticated online scan, video/script stem matching, offline/reconnect behavior, failed generation and preserved old snapshot; credential lifecycle event counts only.

- [ ] **Step 1: 先运行匿名/注入 SMB 回归作为协议基线**

  ```powershell
  .\gradlew.bat :core:index:testDebugUnitTest --tests '*Smb*' --no-configuration-cache --rerun-tasks --max-workers=1
  ```

  预期 `SmbSourceConfig` 拒绝 host/share/root traversal，`SmbScanner` 对 offline/IO failure 返回 `SMB_OFFLINE`/`SMB_IO_FAILED`，未完成 generation 不切换 current。

- [ ] **Step 2: 启用真实认证 gate 并运行主机测试**

  运行时仅设置 `FPLAYER_T32_SMB_ENABLED=true`、`FPLAYER_T32_SMB_HOST`、`FPLAYER_T32_SMB_PORT`、`FPLAYER_T32_SMB_SHARE`、`FPLAYER_T32_SMB_ROOT`、`FPLAYER_T32_SMB_USERNAME`、`FPLAYER_T32_SMB_PASSWORD` 和期望计数环境变量；这些变量不得写入 shell profile、命令示例、报告或 Gradle 文件。

  ```powershell
  .\gradlew.bat :core:index:testDebugUnitTest --tests '*RealSmbAcceptanceTest' --no-configuration-cache --rerun-tasks --max-workers=1
  ```

  预期认证连接和同名视频/脚本扫描通过；认证失败明确为 `BLOCKED`（缺来源/凭据）或 `FAIL`（凭据存在但产品错误），不得用匿名 fixture 代替。

- [ ] **Step 3: 在 `TEST_TABLET` 运行同一认证来源 instrumentation**

  构建并安装 `core:index` instrumentation，使用 `-Pandroid.testInstrumentationRunnerArguments.enabled=true` 及进程内参数注入 host/port/share/root/username/password/expected count；命令输出只保留脱敏状态。预期平板网络能直接建立认证 SMB2 会话，首轮 committed generation 与媒体计数正确，随后受控断开得到 `SMB_IO_FAILED`，旧 current generation/计数保持。

- [ ] **Step 4: 验证凭据擦除和缓存边界**

  断言 `CredentialStore.delete`、数据库 source locator 和诊断不含 username/password；断网期间 UI 只显示旧索引 metadata，恢复后使用新 generation 提交，不能读取未授权共享或把真实文件复制进 fixture。

### Task 4: 在 `TEST_OSR_WIFI` 验收 WS，并保持 SPP 独立

**Files:**
- Read/Modify: `feature/device/src/main/java/io/github/fplayer/feature/device/AndroidDeviceBackend.kt`、`DeviceSession.kt`
- Read: `core/device/src/main/java/io/github/fplayer/core/device/NetworkDeviceTransports.kt`、`SppDeviceTransport.kt`
- Test: `core/device/src/test/java/io/github/fplayer/core/device/NetworkDeviceTransportsTest.kt`、`SppDeviceTransportTest.kt`
- Create: `app/src/androidTest/java/io/github/fplayer/android/TestOsrWifiDeviceAcceptanceTest.kt`

**Interfaces:**
- Consumes: `DeviceConnectionRequest(transportType, selectedDeviceId, host, port, webSocketPath, secureWebSocket, axes)`, `RawWebSocketDeviceTransport`, `SppDeviceTransport`, `TCodeEsp32SppProfile.selector(confirmedAddress)`, `DeviceSession.runSafeTest(plan)` and `DeviceSession.emergencyStop()`。
- Produces: `DEVICE-WS` and `DEVICE-SPP` evidence with endpoint identity check, handshake/echo or write completion, latency samples, recorder byte count, stop/断连结果; WS uses `TEST_OSR_WIFI` network binding, SPP never uses Wi-Fi as its bearer.

- [ ] **Step 1: 完成 WS loopback latency and protocol checks**

  模拟服务必须验证 RFC 6455 upgrade、出站 masking、text frame TCode 语义、ping/pong/echo、close/EOF、超限 frame 和 write timeout；以单调注入时钟记录 RTT 样本，少于最小有效样本数时状态为 `UNMEASURED`。

- [ ] **Step 2: 运行 `TEST_OSR_WIFI` 网络前置检查**

  在平板系统设置中由操作者临时连接 `TEST_OSR_WIFI`，凭据只在系统输入页出现；应用使用 `ConnectivityManager.requestNetwork` 获取网络后绑定进程，再执行唯一 profile/版本识别。预期只访问用户指定 endpoint，网络断开先 `disconnect()`，重连使用新 generation，不重放旧队列。

- [ ] **Step 3: 执行 WS 真实连接但先关闭实物动作**

  发送 TCode identification/echo（不发送运动目标），记录只含响应类别、版本是否匹配、RTT 统计、连接状态和 pending count。若无法确认唯一设备或 profile/TCode 版本不一致，标记 `BLOCKED` 并停止。

- [ ] **Step 4: 独立验收 SPP**

  Android 12+ 仅在授予 `BLUETOOTH_CONNECT` 后读取系统已配对列表，取消 discovery，不创建 bond；用户选择唯一 `TCodeEsp32SppProfile` 后通过安全 RFCOMM 完成写完成/响应样本。蓝牙关闭、取消配对、EOF、写超时均应稳定失败并清空队列。不得把 WS 成功或 Wi-Fi 连接算作 SPP 通过。

- [ ] **Step 5: 通过安全 gate 后才做单轴低幅动作**

  操作者确认空载/目视/急断电，启用一个轴，使用 `DeviceUiState.buildSafeTestPlan()` 生成中心附近三点（默认 250 ms、步间隔 350 ms，幅度不超过 10% 和 profile 范围）；先执行 `runSafeTest`，随后 `emergencyStop()`、软件断开、拔线/断网和物理断电验证。任一步异常立即断电并标记 `FAIL`/`BLOCKED`。

### Task 5: 验收 BLE、USB/OTG 和真实断连停止

**Files:**
- Read/Modify: `core/device/src/main/java/io/github/fplayer/core/device/BleDeviceTransport.kt`、`UsbSerialDeviceTransport.kt`、`DeviceSafetyController.kt`
- Read/Modify: `feature/device/src/main/java/io/github/fplayer/feature/device/AndroidDeviceBackend.kt`、`DeviceSession.kt`
- Test: `core/device/src/test/java/io/github/fplayer/core/device/BleDeviceTransportTest.kt`、`UsbSerialDeviceTransportTest.kt`
- Test: `feature/device/src/test/java/io/github/fplayer/feature/device/DeviceSessionTest.kt`
- Create: `app/src/androidTest/java/io/github/fplayer/android/TestOsrPeripheralAcceptanceTest.kt`

**Interfaces:**
- Consumes: `BleDiscovery.scan(serviceUuid, timeoutMs, name)`, `TCodeEsp32BleProfile.selector(confirmedAddress)`, `UsbSerialDiscovery.discover()`, `UsbSerialSelector(vendorId, productId, confirmedDeviceId, portIndex)`, Android permission callbacks and `DeviceSession` termination handling.
- Produces: `DEVICE-BLE`/`DEVICE-USB` evidence with candidate uniqueness, permission state, descriptor/profile match, MTU/fragmentation or serial parameters, write completion, detach/GATT recovery, queue count and stop summary.

- [ ] **Step 1: 运行 BLE/USB 注入回归**

  ```powershell
  .\gradlew.bat :core:device:testDebugUnitTest --tests '*BleDeviceTransportTest' --tests '*UsbSerialDeviceTransportTest' --no-configuration-cache --rerun-tasks --max-workers=1
  ```

  预期 BLE service/characteristic、write type、MTU-3 分包、权限/蓝牙关闭、GATT 失败和重连无重放全部通过；USB 精确 VID/PID/deviceId/port、用户授权、115200-8-N-1 默认参数、detach 和超时全部通过。

- [ ] **Step 2: 真实 BLE gate**

  在 `TEST_TABLET` 临时授予必要 BLE 权限，按 service UUID/可选名称过滤并要求用户确认唯一候选；记录服务/特征匹配、协商 MTU 和包数，不记录 address、设备名、UUID 或 payload。没有唯一候选、权限拒绝、蓝牙关闭或 profile 不匹配时保持 `BLOCKED`。

- [ ] **Step 3: 真实 USB/OTG gate**

  连接用户确认的 `TEST_OSR_DEVICE` USB 候选，核对 descriptor 与 `UsbSerialSelector`，通过 Android USB Host 权限页后检查串口参数和双向写完成；拔出设备必须进入 `DEVICE_DETACHED`、发送受限停止（若可达）并清空 pending。无候选或当前 `TEST_OSR_SERIAL` 不可见时不得猜测端口，状态为 `BLOCKED`。

- [ ] **Step 4: 真实断连/急停闭环**

  对每个实际通过的 transport 先停止脚本再急停、断开、关闭 session；检查 recorder 最后一条为 emergency/stop 类、旧 generation 不再写入、服务退出无 wake lock/通知/transport 残留。物理行为与预期不符时立即断电并终止后续项目。

### Task 6: 延迟样本、统一证据、权限清理和阻塞门

**Files:**
- Create: `docs/qa/t32-device-media-acceptance.md`
- Modify: `docs/qa/t32-evidence-index.md`
- Test: `core/device/src/test/java/io/github/fplayer/core/device/DeviceAcceptanceRecorderTest.kt`
- Test: `app/src/androidTest/java/io/github/fplayer/android/TestTabletMediaAcceptanceTest.kt`
- Test: `app/src/androidTest/java/io/github/fplayer/android/TestOsrWifiDeviceAcceptanceTest.kt`
- Test: `app/src/androidTest/java/io/github/fplayer/android/TestOsrPeripheralAcceptanceTest.kt`

**Interfaces:**
- Consumes: 各 Task 的 recorder、generation、扫描结果、延迟样本和清理摘要。
- Produces: `DEVICE-*`、`MEDIA-*`、`SMB-REAL` 的 `PASS/FAIL/BLOCKED/NOT_RUN` 记录，以及不含敏感值的最终证据索引。

- [ ] **Step 1: 固定延迟样本报告格式**

  每个 transport 记录 `sampleCount`、`rttMedianMs`、`autoOffsetMs`、`manualOffsetMs`、`effectiveOffsetMs`、`measurementState`、generation 和丢弃计数；样本不足写 `UNMEASURED`，不写时间戳、地址或帧正文。断言自动提前量为 `-round(rttMedianMs/2)` 且受最大绝对值限制，手工偏移可正可负并覆盖自动值。

- [ ] **Step 2: 执行跨模块验收命令**

  ```powershell
  .\gradlew.bat testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
  .\gradlew.bat :app:assembleDebug --no-configuration-cache
  .\gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache
  ```

  预期相关单测、应用 instrumentation 和 debug 构建无 failure/error；未满足真实来源或设备条件的 gate 必须显示 `BLOCKED`，不能被 skip 伪装成 PASS。

- [ ] **Step 3: 执行清理顺序并核对零残留**

  停止播放 -> `DeviceSession.emergencyStop()` -> `disconnect()/close()` -> 删除本应用私有 fixture/cache -> 释放 SAF grant（仅用户确认的两个 grant）-> 删除 Keystore credentialRef -> 撤销临时 `BLUETOOTH_*`/`POST_NOTIFICATIONS` 权限 -> force-stop 本项目包。检查 foreground service、MediaSession、active wake lock、transport 进程、测试 APK/fixture 和 pending queue 均为 0；不执行其他包的 `pm clear`、卸载或删除。

- [ ] **Step 4: 敏感信息与状态扫描**

  ```powershell
  rg -n -i '([A-Za-z]:\\|\\\\|password|passwd|username|ssid|COM[0-9]+|[0-9]{1,3}(\.[0-9]{1,3}){3}|smb://|bluetooth address)' docs/qa/t32-device-media-acceptance.md docs/qa/t32-evidence-index.md
  git diff --check
  Get-ChildItem -Recurse -File | Where-Object { $_.Name -match '\.tmp\.' }
  ```

  预期敏感扫描无命中（逻辑标识白名单除外）、无临时文件、无尾随空白；任何命中先脱敏再继续。

- [ ] **Step 5: 记录明确阻塞条件**

  `DEVICE-WS` 需要 `TEST_OSR_WIFI` 可用且唯一 profile/版本识别通过；`DEVICE-SPP` 需要唯一已配对目标；`DEVICE-BLE` 需要唯一候选和权限；`DEVICE-USB` 需要唯一 descriptor、用户授权和可见 `TEST_OSR_SERIAL`；`MEDIA-*` 需要两个 SAF grant 和可读中性来源；`SMB-REAL` 需要 `FUNSCRIPT_TEST_LIBRARY` 已配置且运行时认证成功。缺少任一条件写 `BLOCKED`、原因逻辑化、下一步和清理状态，不宣称通过。

- [ ] **Step 6: 更新主矩阵并交接**

  将本计划结果追加至 `docs/qa/t32-acceptance-matrix.md`/`t32-evidence-index.md`，只引用相对路径、命令、hash、计数和状态；复读文档后交给主计划 Task 5/6，保留未通过的 gate 和证据位置。

## Self-Review Checklist

- Spec coverage: loopback 顺序、SAF 两类真实媒体、运行时认证 SMB、`TEST_OSR_WIFI` WS、独立 SPP、BLE、USB/OTG、延迟样本、两层停止安全、权限/fixture 清理和 `BLOCKED` 条件分别由 Task 1--6 覆盖。
- Placeholder scan: 计划不使用未解决占位词或未定义函数；所有运行时值均通过逻辑环境变量/权限页注入，不写具体地址或凭据。
- Type consistency: `DeviceTransport`、`DeviceSafetyController`、`DeviceConnectionRequest`、`SmbSourceConfig`、`SmbScanner`、`SafPermissionStore` 与当前源码签名一致；录制器是测试专用输出，不改变生产接口。
- Safety: 真实动作前有 loopback、唯一 profile、空载/目视/急断电、单轴/中心/500 ms gate；任何连接/播放/应用退出路径均调用 stop、清队列和 close。

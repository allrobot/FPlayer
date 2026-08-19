# T32 多端验收与发布合规实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不泄露真实凭据或媒体的前提下，验证并补齐 FPlayer 的抖音式 Feed、Funscript/TCode 同步、WS/BLE/SPP/USB 连接、SMB/平板真实媒体与 T32 发布合规，使每个结论都有可复现证据或明确阻塞门。

**Architecture:** 以现有 `PlaybackSession`、`MediaClockScriptScheduler`、`DeviceSafetyController`、Room/SAF/SMBJ 和 `PlaybackService` 为边界，新增的延迟估计、脚本限幅、手动轴控制和验收记录都通过纯数据契约接入，不让 UI 直接写 transport。真实测试先经过 loopback/合成时钟，再通过 `TEST_TABLET` 和 `TEST_OSR_DEVICE` 逻辑 profile；发布资产从锁定依赖与构建证据机器生成。

**Tech Stack:** Kotlin/Compose、libmpv JNI、Room/SAF、SMBJ、Android Bluetooth LE/RFCOMM、Android USB Host、原生 WebSocket/TCP/UDP、Gradle 9.5/AGP 9.3/Kotlin 2.3/JDK 17、NDK r29/Meson 1.11.2、ADB。

**Spec:** `TASKS.md` T32、`SPEC.md`、`docs/ui/interaction-spec.md`、`docs/architecture/script-scheduler.md`、`docs/architecture/device-layer.md`、`docs/architecture/background-playback.md`、`docs/security/test-device-policy.md`、`docs/qa/t31-report.md`。

## Global Constraints

- 自有源码继续使用 `GPL-3.0-or-later`；第三方许可证和版权原文必须随发布资产保留。
- 仓库文档只写相对路径或逻辑标识；`TEST_TABLET`、`TEST_OSR_DEVICE`、`TEST_OSR_WIFI`、`FUNSCRIPT_TEST_LIBRARY` 是唯一允许的外部资源标识。
- 任何 SMB、Wi-Fi、Bluetooth、签名或媒体凭据只经进程环境、系统权限页或未跟踪本地配置提供，不写源码、测试、日志、计划、截图或报告。
- 真实媒体只读；日志和报告只保存数量、扩展名、状态码、脱敏 hash 和命令统计，不保存文件名、缩略图、脚本正文或 locator。
- 真实 TCode 首次动作必须唯一 profile、空载、可目视、可立即断电、单轴、中心附近、每条命令 `<=500 ms`；loopback 失败不得进入实物阶段。
- 脚本用户限幅（默认 `0..100%`）先于设备 profile 安全限幅；任何 UI 路径都不能绕过 `DeviceSafetyController`。
- 延迟正值表示脚本延后，负值表示脚本提前；自动补偿只能在有可验证响应样本时启用，并有最大绝对值和过期策略。
- 每个纵向子计划控制在 8–12 个主要源码文件；完成一个子计划后运行相关测试、`assembleDebug`/`assembleRelease`（按范围）并更新 `PROGRESS.md`。
- 新文件先写同目录 `.tmp.<task-id>`，读回校验后原子重命名；每次阶段结束检查 `git diff --check`、尾随空白、临时文件和 `git status --short`。
- 不复制 TikTok、参考 APK 或网页的品牌素材、私有实现、成人媒体内容或二进制代码；只记录可观察的交互事实和来源逻辑标识。

## 计划关系与执行顺序

1. `2026-08-19-t32-ui-feed-comparison.md`：Feed/播放叠层/热力图和参考 UI 对比。
2. `2026-08-19-t32-funscript-latency.md`：真实脚本匹配、自动/手工延迟、轴实时控制和两层限幅。
3. `2026-08-19-t32-device-media-acceptance.md`：WS/BLE/SPP/USB、安全停止、平板真实媒体和 SMB。
4. `2026-08-19-t32-release-compliance.md`：许可证、NOTICE、隐私、归属、可复现构建和签名说明。
5. 本文件 Task 5–7：跨子系统整合、最终门和报告。

### Task 0: 建立脱敏基线和验收证据目录

**Files:**
- Create: `docs/qa/t32-acceptance-matrix.md`
- Create: `docs/qa/t32-evidence-index.md`
- Modify: `PROGRESS.md`
- Test: `native-build/verify-lock.py`、现有 T31 报告和四 viewport 截图

**Interfaces:**
- Consumes: `TASKS.md` T32、T31 统计、现有架构契约、逻辑资源标识。
- Produces: 每个验收项的 `ID / 前置条件 / 输入 / 命令 / 期望 / 证据位置 / 阻塞原因 / 清理动作` 表；后续子计划只追加结果，不改变 ID。

- [ ] **Step 1: 固定矩阵 ID 和 gate 状态**

  在 `t32-acceptance-matrix.md` 写入以下固定组：`UI-FEED`、`SCRIPT-LATENCY`、`DEVICE-WS`、`DEVICE-BLE`、`DEVICE-SPP`、`DEVICE-USB`、`MEDIA-SD-MULTIAXIS`、`MEDIA-SD-SINGLEAXIS`、`SMB-REAL`、`RELEASE-LICENSE`、`RELEASE-REPRO`、`RELEASE-SIGN`。每项状态只能是 `PASS`、`FAIL`、`BLOCKED`、`NOT_RUN`。

- [ ] **Step 2: 记录当前已知事实而不写敏感值**

  记录 T31 的 36 suite/199 tests、debug APK hash、匿名 SMB 通过、认证 SMB 未完成、`TEST_OSR_DEVICE` 无唯一 profile、当前 `TEST_OSR_SERIAL` 不可见、平板真实来源需 SAF 授权等事实。来源只写仓库相对路径和逻辑标识。

- [ ] **Step 3: 运行基线命令**

  ```powershell
  if (-not $env:JDK_17_HOME) { throw 'Set JDK_17_HOME to a local JDK 17 installation before building' }
  $env:JAVA_HOME = $env:JDK_17_HOME
  .\gradlew.bat testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
  .\gradlew.bat :app:assembleDebug --no-configuration-cache
  python native-build/verify-lock.py
  git diff --check
  ```

  预期：0 failure、0 error；锁校验通过；若环境变量未提供则只记录构建前置阻塞，不把失败改写为通过。

- [ ] **Step 4: 写入证据索引并复读**

  `t32-evidence-index.md` 只列文件相对路径、hash、测试命令和脱敏摘要；复读两个文件，确认没有盘符、IP、SSID、密码、用户名、COM 端口或媒体名。

- [ ] **Step 5: 更新 checkpoint**

  在 `PROGRESS.md` 增加 T32 矩阵版本、当前 gate 和下一子计划；保持文件不超过 200 行。

### Task 1: 执行 UI/Feed 对比子计划

**Files:**
- Read/Modify: 由 `docs/superpowers/plans/2026-08-19-t32-ui-feed-comparison.md` 明确的 `app/`、`feature/feed/`、`feature/library/` 文件
- Test: Feed/overlay/progress/long-press 单测、Compose/ADB 四 viewport 截图和无障碍树

**Interfaces:**
- Consumes: `FeedGestureState`、`PlaybackOverlayState`、`ProgressInteractionState`、`PlaybackSessionSnapshot`。
- Produces: 当前项唯一 active、视频 Surface 与 overlay 的可观察验收结果；不改变设备安全契约。

- [ ] **Step 1: 先运行纯状态机回归**

  ```powershell
  .\gradlew.bat :feature:feed:testDebugUnitTest :feature:library:testDebugUnitTest --no-configuration-cache
  ```

  预期：阈值回弹、方向锁、拖动抑制翻页、长按互斥、清屏安全提示和当前项重定位全部通过。

- [ ] **Step 2: 比较当前 APK、参考 APK和三个网页**

  只记录可观察项目：来源切换、库抽屉、视频/脚本列表、热力图、轴选择、上下限滑块、全局/每轴延迟、播放/暂停/倍速/清屏、横竖屏布局。参考 APK 逻辑标识为 `REF_CYBER_ELECTRONIC_1_0_5`，网页逻辑标识为 `REF_TRYMOSA`、`REF_JOYTECH_PLAYER`、`REF_FUNWISP`。

- [ ] **Step 3: 运行真实平板四 viewport 验收**

  使用 debug screenshot activity 和 `native-build/capture-screenshot.ps1`，覆盖手机/平板横竖、系统栏、1.3x 字体、空态和至少一个合成媒体。预期无重叠、截断、半屏停留、双播放或系统手势冲突；截图只保留中性 fixture。

- [ ] **Step 4: 若发现缺陷，按 T22–T28 单任务修复**

  每个修复先写纯状态测试，再绑定 Compose；不得在该任务顺手修改延迟、设备协议或发布配置。修复后更新 `UI-FEED` 证据。

### Task 2: 执行 Funscript/延迟/限幅子计划

**Files:**
- Read/Modify: 由 `docs/superpowers/plans/2026-08-19-t32-funscript-latency.md` 明确的 `core/script/`、`core/device/`、`feature/settings/`、`feature/feed/` 文件
- Test: 确定性时钟、录制 frame sink、每轴范围和延迟估计测试

**Interfaces:**
- Consumes: `ScriptBundle`、`MediaClockScriptScheduler`、`DeviceSafetyController`、`AxisId`。
- Produces: `LatencyCompensationConfig`、`LatencyEstimate`、`ScriptAxisOutputRange`、可录制 `ScheduledDeviceFrame`；后续设备测试只消费这些接口。

- [ ] **Step 1: 用合成多轴/单轴向量验证匹配**

  覆盖 SR6 的 `L0/L1/L2/R0/R1/R2`、单轴脚本、重复时间、越界位置、异常扩展名和同名冲突。预期每个媒体产生确定的轴集合和诊断，不把未知轴静默映射。

- [ ] **Step 2: 实现并测试两层限幅**

  `ScriptAxisOutputRange` 默认每轴 `0..100`，拖动更新只影响脚本目标；`AxisSafetyConfig` 继续作为设备硬边界。测试断言输出永远满足 `scriptRange ∩ deviceRange`，并验证实时手动位置也经过同一安全控制器。

- [ ] **Step 3: 实现延迟估计策略**

  WS 使用可验证 ping/pong 或应用 echo；SPP/BLE 使用写完成/响应样本，无法获得响应时标记 `UNMEASURED`。对有效样本取稳健中位数，估计提前量为 `-round(rtt/2)`，再限制在配置上限；手工整数可正可负并覆盖自动值。不得把墙钟或 UI 帧时间当媒体时间。

- [ ] **Step 4: 接入 scheduler 并验证 seek/pause/speed/loop**

  `effectiveOffsetMs = manualOffsetMs + (autoEnabled ? autoOffsetMs : 0)`，所有 discontinuity 增加 generation 并停止旧队列。录制命令流逐帧比较 generation、mediaTime、axis、position、duration 和停止原因。

- [ ] **Step 5: 更新脚本设置 UI 和诊断**

  提供自动计算开关、正负整数输入、测量状态、每轴实时位置和上下限滑块；默认上限 100%、下限 0%。文本必须在窄屏/1.3x 字体下完整显示，诊断只显示延迟统计类别和数值，不显示地址或媒体名。

### Task 3: 执行设备连接与真实媒体验收子计划

**Files:**
- Read/Modify: 由 `docs/superpowers/plans/2026-08-19-t32-device-media-acceptance.md` 明确的 `core/device/`、`feature/device/`、`core/index/`、`app/` 测试夹具
- Test: loopback、Android instrumentation、SAF/SMB 门控、脱敏 frame recorder

**Interfaces:**
- Consumes: `DeviceConnectionRequest`、`DeviceTransport`、`DeviceSafetyController`、`SmbSourceConfig`、`SafPermissionStore`。
- Produces: `DEVICE-*`、`MEDIA-*`、`SMB-REAL` 证据；每次真实连接退出时输出清理摘要。

- [ ] **Step 1: 完成 loopback 六传输矩阵**

  TCP/UDP/WS 先用本机模拟服务；BLE/SPP 用注入 backend；USB 用注入串口 backend。覆盖连接、重连、背压、写超时、远端关闭、急停和旧 generation 不重放。

- [ ] **Step 2: 运行 `TEST_TABLET_MEDIA_ROOTS` 只读 SAF 盘点**

  用户在系统文件夹选择器中分别选择多轴和单轴逻辑来源；应用只持久化 SAF 授权，不把物理盘符写入数据库。记录视频/脚本数量、可读率、匹配率、异常扩展名计数和脱敏 checksum。

- [ ] **Step 3: 播放真实媒体但先禁用实物输出**

  多轴来源验证视频 prepare、脚本轴映射、暂停/seek/speed/EOF/后台循环；单轴来源验证单轴输出和缺失多轴回退。先使用 `RecordingDeviceFrameSink`，确认帧流后才可申请实物 gate。

- [ ] **Step 4: 验收 SMB 真实来源**

  运行时配置 `FUNSCRIPT_TEST_LIBRARY` 对应用户授权的 SMB 来源，扫描视频与脚本同名匹配、断网、恢复、缓存和旧 generation 保持；凭据只进入 Keystore/进程内。匿名临时 SMB 只能作为协议回归，不得替代认证来源。

- [ ] **Step 5: 验收 `TEST_OSR_WIFI` 上的 WS 与独立 SPP**

  先确认平板网络已获得、目标设备唯一、TCode identification 与 profile 一致；WS 连接使用网络绑定，SPP 取消 discovery 后仅连接已配对目标。首次动作单轴中心附近低幅、短时，立即执行软件停止、拔线/断连和物理断电验证。

- [ ] **Step 6: 验收 BLE 与 USB/OTG**

  BLE 记录服务/特征匹配、MTU/分包、权限关闭和 GATT 错误恢复；USB 只连接用户确认的 OSR 候选，核对设备 descriptor、权限和串口参数，断开后确认队列清空。无唯一候选时保持 `BLOCKED`。

- [ ] **Step 7: 清理和撤销权限**

  停止播放、急停、断开 transport、删除本应用产生的 fixture/cache、撤销临时蓝牙/通知权限、force-stop 本包；报告只写计数和状态。

### Task 4: 执行 T32 发布合规子计划

**Files:**
- Read/Modify: 由 `docs/superpowers/plans/2026-08-19-t32-release-compliance.md` 明确的根 `NOTICE`、`THIRD_PARTY_LICENSES`、`docs/compliance/`、`docs/privacy/`、`docs/release/`、`native-build/`、`app/build.gradle.kts`
- Test: lock/evidence/license/path/reproducibility/signature 扫描

**Interfaces:**
- Consumes: `native-build/sources.lock.json`、native evidence manifests、Gradle resolved dependency metadata、T31 report。
- Produces: 完整许可证矩阵、源码归属、隐私说明、可复现构建说明、签名说明和可验证 release artifact；生产身份/签名缺失时明确阻塞。

- [ ] **Step 1: 生成依赖许可证矩阵**

  机器读取锁文件、ELF `DT_NEEDED`、Gradle resolution result 和许可证原文，标记 `runtime`、`build-only`、`reference-only`、静态/动态链接、revision、许可证和 NOTICE。`mpv-android` reference-only 不进入二进制资产但必须在矩阵中出现。

- [ ] **Step 2: 生成 NOTICE 与 THIRD_PARTY_LICENSES**

  由脚本按稳定排序合并 native evidence 的 31 份原文和 Gradle 依赖版权；缺少许可证、重复版本或未解释的闭包项使命令失败。复读并扫描敏感字面量。

- [ ] **Step 3: 写源码归属和隐私说明**

  登记自有 GPL、复制/改写代码、仅语义参考；制定 SPDX 逐文件或集中清单策略。隐私说明必须覆盖离线默认、Keystore、无上传/画像、日志脱敏、SAF/SMB 用户选择、真实媒体不进仓库。

- [ ] **Step 4: 固定可复现构建流程**

  记录 JDK/SDK/NDK/Meson/Gradle/AGP/Kotlin/Compose 版本、锁校验、Gradle verification metadata、native 两次 clean build hash、APK zipalign/hash 和归档规则。没有 commit/tag 时先标记 `RELEASE-REPRO BLOCKED`，不得虚构标签。

- [ ] **Step 5: 分离 release variant 和签名说明**

  release keystore 只从未跟踪本地配置或 CI secret 注入；不提交密码、密钥或 debug key 作为生产签名。验证 applicationId、version、`apksigner verify --verbose`、zipalign 和 SHA-256。

### Task 5: 跨系统整合验收

**Files:**
- Modify: `docs/qa/t32-acceptance-matrix.md`、`docs/qa/t32-evidence-index.md`、`docs/qa/t32-report.md`、`PROGRESS.md`
- Test: 全工程单测、debug/release 构建、ADB/SMB/loopback 门控

**Interfaces:**
- Consumes: 四个子计划的证据和未解决 gate。
- Produces: 一份不夸大结论的 T32 report，能区分代码通过、环境未满足和用户决定缺失。

- [ ] **Step 1: 运行全工程回归**

  ```powershell
  .\gradlew.bat testDebugUnitTest --no-configuration-cache --rerun-tasks --max-workers=1
  .\gradlew.bat :app:assembleDebug --no-configuration-cache
  .\gradlew.bat :app:assembleRelease --no-configuration-cache
  python native-build/verify-lock.py
  git diff --check
  ```

  预期：相关测试 0 failure/0 error；release 若因身份/签名阻塞，记录真实错误和下一步，不替换为 debug 结果。

- [ ] **Step 2: 做全仓卫生扫描**

  扫描凭据模式、真实媒体名、盘符/绝对路径、IP/SSID/密码/COM、成人内容、临时文件、尾随空白和未登记二进制；允许的逻辑标识列入白名单并逐项审阅。

- [ ] **Step 3: 更新报告和进度**

  `t32-report.md` 按矩阵 ID 写结果、证据、环境、清理和阻塞；`PROGRESS.md` 保持 200 行以内并记录下一步。

### Task 6: 最终发布门与用户决策门

**Files:**
- Read: `docs/qa/t32-acceptance-matrix.md`、`docs/qa/t32-report.md`、`docs/release/`
- Test: clean checkout/archive rebuild、签名验证、ADB cleanup audit

- [ ] **Step 1: 验证不可替代的发布前置**

  最终应用名称、组织/作者标识、正式包名、生产签名密钥、认证 SMB 来源、唯一 `TEST_OSR_DEVICE` profile 和机械安全现场条件必须明确；缺一项就保持发布 `BLOCKED`。

- [ ] **Step 2: 从 clean tag 或明确的未发布 commit 重建**

  只接受干净 checkout、锁校验、两次 native hash 一致、Gradle 依赖校验通过、APK 签名和 hash 可复核的结果。当前无 tag 时不得声称“从标签重建”。

- [ ] **Step 3: 完成最终清理审计**

  确认服务、MediaSession、通知、wake lock、transport、测试进程、临时 SMB 规则和本应用测试缓存均为 0；APK 是否保留由用户发布流程决定，不执行宽范围清理。

### Task 7: 交付与后续会话 handoff

- [ ] **Step 1: 复读所有计划与状态文件**

  检查链接、命令、接口名称和矩阵 ID 一致；运行占位符扫描，确认没有未解决的占位符或敏感外部值。

- [ ] **Step 2: 写下一会话入口**

  在 `PROGRESS.md` 最后记录当前 gate、最后成功命令、未完成子计划和安全退出状态；若上下文或证据过长，建议从该文件开启新会话。

---

## 自检结果

- 规格覆盖：T32 的许可证、NOTICE、归属、隐私、可复现构建、签名和敏感信息门分别由 Task 4–6 覆盖；用户新增的 UI、延迟、限幅、真实媒体、SMB、WS/BLE/SPP/USB 由 Task 1–3 和对应子计划覆盖。
- 占位符扫描：JDK 17 通过本地 `JDK_17_HOME` 环境变量注入；所有环境缺失都定义为 `BLOCKED`，没有 `TBD`、`TODO` 或“适当处理”类步骤。
- 接口一致性：`ScriptSchedulerConfig.offsetMs`、`DeviceSafetyController`、`DeviceConnectionRequest`、`SmbSourceConfig`、逻辑 profile 与子计划保持同名；新增契约在子计划 Task 2 中先定义再被后续任务消费。
- 安全复核：计划不保存用户提供的 SMB/Wi-Fi/设备凭据，不承诺不存在的 COM/OSR 目标，不把匿名 SMB 或 debug APK 当作发布通过。

# T31 性能、稳定性与真实设备验收

日期：2026-08-11

状态：自动化、运行时接线、最新 `TEST_TABLET`、本机及 Android 跨主机真实 SMB2 协议验收通过；已认证外部 SMB 来源与 `TEST_OSR_DEVICE` 目标项未完成。

## 自动化结果

- 全工程 `testDebugUnitTest --rerun-tasks --max-workers=1 --no-configuration-cache`：36 个 suite、199 项测试、0 failure、0 error、1 个预期 skip；skip 是默认关闭的真实 SMB 运行时门控。
- 2,500 条 Room 提交和分页：0.223 s。
- 2,500 条 SAF fixture 扫描：0.188 s。
- 2,001 条推荐排序：3.058 s，低于 5 s 门槛；隔离强制重跑另三次为 3.073 s、3.221 s、3.254 s。一次全模块并行重编译因资源争用为 5.676 s，隔离复测未复现算法回退。
- 25%/50%/95% 扫描中断均产生 incomplete generation，旧 current generation 和旧媒体快照保持可见。
- SMB 在目录发现中断网产生 `SMB_IO_FAILED`，工作 generation incomplete，旧快照保持不变。
- TCP/UDP/WebSocket loopback 覆盖远端关闭、显式重连、背压、写超时、握手取消和写入/断连竞态；重连不重放旧帧。
- `DeviceSession` 远端断连会取消安全测试、停止控制器并阻止剩余目标写入。
- 10,000 次重复后台 PLAY 只获取一次资源并只调用一次 resume；10,000 次自动循环只记录一次 completion。

## 验收发现与修复

- 重复 PLAY 原先会重复调用播放器 resume；现在 PLAYING 状态幂等。
- 自动循环原先每圈都记录 completion；现在同一连续选择只记录一次，切换媒体后重新允许记录。
- transport 非预期 FAILED/DISCONNECTED 原先只更新 UI；现在同步取消测试并调用连接丢失停止路径。
- 快速 Activity 重建可能在 service callback 前进入 `onStop`，旧逻辑不会解绑未完成的 bind 请求；现在分别跟踪 bind request 和 connected service，任何已发起请求都在 `onStop` 解绑。
- `PlaybackService.resumeMedia` 原先重复获取 wake lock；资源获取统一由 `BackgroundPlaybackController` 持有。
- `PlaybackService` 原先没有从 Room current generation 装载媒体队列，也没有接收 native prepared/end event；现在使用单次一致 DAO 快照异步装载，并以请求 generation 屏蔽过期查询与播放器事件。
- libmpv 现在用 `loadfile` command reply 返回的 `playlist_entry_id` 精确关联 `FILE_LOADED`/`END_FILE`，EOF 循环重新 prepare，同媒体 ID 的旧 request 不能命中新 generation。
- Debug-only `T31MediaLoopActivity` 可把中性 2 s H.264 fixture 复制到本应用 cache，运行后台循环，输出脱敏计数并由应用自身清理；不写 Room、不访问真实媒体库。
- API 33 新安装默认未授予通知权限，首次真机长跑只有系统任务管理器入口而无活动通知；现在只在用户启用后台播放时请求 `POST_NOTIFICATIONS`，拒绝时不启动 foreground service，API 32 及已授权路径不弹窗。

## SMB 协议与外部来源

- 新增环境变量门控的 `RealSmbAcceptanceTest`；普通回归默认 skip，只有显式启用并提供运行时 host/port/share/root/凭据时才连接，目标、凭据和真实路径不写仓库或测试输出。
- 隔离运行时使用官方 Impacket `0.12.0` tag（commit `db53482dc864fec69156898d52c1b595a777ca9a`）在 IPv4 loopback 随机高位端口提供真实 SMB2；该工具仅用于本轮验收，不是产品或构建依赖。
- 门控测试实际执行 1 项、0 failure、0 error、0 skip：首轮真实 SMBJ 扫描提交 2 个中性媒体和 2 个同名脚本；第二轮经进程内 TCP 代理在根目录枚举后切断连接，稳定得到 `SMB_IO_FAILED` 和 incomplete generation，current generation 保持 1，旧媒体计数保持 2。
- `core:index` 新增同契约的 self-targeting Android instrumentation 和测试 INTERNET 权限；`TEST_TABLET` 通过私网直连开发机随机高位 SMB2 服务，单独执行 1 项、0 failure、0 error。平板进程内代理在首轮目录枚举后切断真实 Android socket，generation 2 incomplete 且 generation 1 的 2 个媒体/2 个脚本快照保持。
- 跨主机入口同时支持匿名和运行时 credentialRef；匿名真实 SMB2 已通过。一次性用户名/密码模式在 Windows JVM 与 Android 都完成 TCP/NTLM/`TREE_CONNECT` 后未完成 `SmbjDocumentTree` 构造，因此不能用临时 Impacket 服务替代真实 NAS 的认证验收；没有把该失败归因于产品或标记外部认证项通过。
- 隔离服务、fixture 和日志在测试后停止并按固定路径白名单清理；原路径、运行进程、新增 Windows 共享、临时防火墙规则、平板测试包和测试进程均为 0。早期两次本机隔离目录使用系统回收站 API 清理，可恢复且未清空用户回收站；含一次性认证摘要的跨主机运行目录已永久删除。
- 对当前物理私网接口所在 `/24` 做了授权的 TCP 445 探测：3 个主机可达，其中 2 个可枚举共 11 个磁盘共享，但当前会话全部不可读/不可写且没有匹配运行时凭据；第 3 个拒绝共享枚举。未读取共享文件内容，所有主机、共享和凭据诊断只保留脱敏计数。

## TEST_TABLET

- 覆盖安装前核验 APK applicationId、唯一在线目标和现有包边界；只操作 `io.github.fplayer.android`。
- 最新主 APK 与 self-targeting `core:player-mpv` instrumentation APK 安装成功；两项中性视频 native 测试通过，覆盖 prepare/play/pause/seek/speed 及连续两次 prepare/EOF 的 generation 映射。
- 中性视频的 native 播放版本在屏幕明确保持 OFF 的 5 分钟窗口从 completion 3 增至 184，增量 181；末态 `prepared=185 completed=184 failed=0 playing=true`，进程、foreground service、MediaSession 和 wake lock 持续存在。
- 最终通知权限修复 APK 实测：未授权时系统权限页出现且 Service 未 start/foreground；临时授权后 `POST_NOTIFICATIONS granted=true`、`isForeground=true`、`foregroundId=1001` 且活动通知数为 1。屏幕关闭导致无线 ADB 暂时失联，约 4 分半后重连时同一 ServiceRecord 仍在，completion 从 16 增至 133。
- `RUNNING_LOW` trim 后 PID 稳定且 completion 增至 139；foreground 状态拒绝 `COMPLETE` 符合平台约束，cleanup 后缓存进程接受 `COMPLETE` 且 PID 稳定。
- 固定方向 Activity 分别取得 1600x2560 `ROTATION_0` 和 2560x1600 `ROTATION_90`；两张临时截图人工检查无重叠、截断或系统栏冲突，检查后已删除。
- cleanup 日志为 `removed=true`，应用 cache fixture、Service、MediaSession、活动通知和 active wake lock 全部消失；撤销临时通知权限后仅 force-stop 主包，包保持安装且主包/测试包进程均不存在。
- Debug MainActivity 竖/横夹具切换期间 PID、started foreground service、MediaSession 和 wake lock 连续，过滤日志无 `FATAL EXCEPTION`。
- 10 轮快速 Activity 切换后 stopped Activity binding 数为 0；EXIT 后服务、MediaSession、通知和 wake lock 全部释放。
- 锁屏 30 s 期间进程、服务、MediaSession、通知和 wake lock 保持；PAUSE 后 active wake lock 数为 0。
- `RUNNING_LOW` 和 `COMPLETE` trim 被系统接受；前台进程稳定，后台缓存进程被本项目定向 `am kill` 后可用新 PID 正常恢复。
- force-stop 后本项目进程、服务、MediaSession 和 wake lock 均消失；APK 保持安装。
- 本轮全部过滤日志无 `FATAL EXCEPTION`；本应用私有 Room 一致查询为 0 个来源、0 个媒体，没有读取或输出真实媒体名、locator 或缩略图。

## TEST_OSR_DEVICE

- 临时授予本应用蓝牙扫描/连接权限，通过应用过滤分别发现 SPP、BLE、USB 候选，三类结果均为 0。
- 本机环境也没有 OSR/TCode 逻辑目标；普通通信口和非 OSR 外设不作为候选。
- 附近未跟踪应用配置中的 `tcode_port` 和 `tcode_addr` 均为空，`last_device_backend=None` 且不自动重连；本机也没有匹配串口或网络 endpoint，不能据此构造唯一 `TEST_OSR_DEVICE` profile。
- 未连接 transport、未发送运动或停止帧、未写持久配置、未访问固件；测试后撤销本轮蓝牙权限并 force-stop 本项目。
- 因没有唯一可识别目标，低幅单轴、断连和实物急停验收未执行。

## 未完成项

- 本机与 `TEST_TABLET` 跨主机真实 SMB2、元数据扫描和受控连接切断已执行；已认证的外部 SMB 来源网络切换仍未执行，因为当前应用没有已配置来源，局域网可见共享也没有可用运行时凭据或读权限。平板网络设置未修改；主机只短时添加端口/进程/平板地址限定的入站规则并已删除。
- `TEST_OSR_DEVICE` 需提供唯一运行时 profile，并在机械结构空载、可目视、可立即断电时完成低幅测试。

## 构建与源码卫生

- `:app:assembleDebug --no-configuration-cache` 通过。
- `:core:player-mpv:assembleDebugAndroidTest` 通过；测试 APK 的两项 instrumentation 已在设备通过。
- `:core:index:assembleDebugAndroidTest` 通过；self-targeting 测试 APK 为 6,079,386 bytes，跨主机 SMB instrumentation 1/1 在设备通过并已卸载。
- Debug APK：56,645,232 bytes，SHA-256 `8185B51C4182465670C9FE9CD0F6FB32E4E2139DFFF45871163EF118CDD035B8`。
- `native-build/verify-lock.py`：20 个锁定源码、14 个构建配方，通过。
- 169 个受控源码/文档输入：0 尾随空白；仓库临时文件 0。
- `git diff --check` 通过。仓库尚无提交，全部项目文件仍为未跟踪状态。

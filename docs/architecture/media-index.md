# 媒体索引、扫描与物化顺序

## 目标

2,000+ 视频和脚本时，应用启动、切换排序和 Feed 翻页不能等待文件系统遍历、媒体探测或全量排序。UI 始终读取最近一次成功提交的数据库快照，后台扫描用新 generation 增量替换。

## 计划数据模型

| 表/逻辑实体 | 核心字段 | 说明 |
| --- | --- | --- |
| `Source` | id, type, displayName, rootLocator, authRef | 本地 SAF 或 SMB；`authRef` 只引用 Keystore 封装 |
| `Folder` | id, sourceId, parentId, normalizedPath, counts | 文件夹树和聚合数量 |
| `Media` | id, folderId, locator, name, stat, mediaInfo | 视频稳定身份和元数据 |
| `Script` | id, mediaId?, locator, axis, stat, parseStatus | 单/多轴脚本和匹配状态 |
| `PlaybackState` | mediaId, resumeMs, lastPlayedAt, coverage | 断点和聚合状态 |
| `InteractionEvent` | id, mediaId, type, occurredAt, payload | 可压缩的原始行为事件 |
| `RecommendationAggregate` | mediaId, counters, decayed values | 避免每次读取全事件表 |
| `Thumbnail` | mediaId/folderId, cacheKey, generation | 只记录 key/状态，不存 bitmap blob |
| `ScanGeneration` | sourceId, generation, status, timestamps | 扫描原子提交和中断恢复 |
| `MaterializedOrderHeader` | scope, sort, generation, rowCount | 某个顺序是否有效 |
| `MaterializedOrderRow` | headerId, ordinal, mediaId | 行式顺序，支持定位和分页 |

路径不直接作为唯一身份。首期稳定键可组合 source、文档/SMB file id、规范化相对路径、大小和修改时间；重命名检测能力按来源实际元数据分级。

## T14 Room 基线

- `core/index` 使用 Room 2.8.4，正式数据库为 v2；`core/index/schemas/` 保留 v1/v2 导出 schema，升级必须显式注册迁移，禁止 destructive fallback。
- `Folder`、`Media`、`Script` 和 `Thumbnail` 使用逻辑 id 与扫描 generation 复合主键。工作 generation 不覆盖 current generation 的同行，扫描期间 UI 仍可读取旧快照。
- `Source.currentScanGeneration` 是唯一的 current 指针。`beginScan` 要求 generation 单调递增；只有 `commitScan` 事务能把 `WORKING` 标为 `COMPLETE` 并切换指针，`abandonScan` 只标记 `INCOMPLETE`。
- 来源元数据更新不接收 current 指针，避免目录改名、凭据引用更新或离线状态变化意外清空已提交快照。
- 播放状态、行为事件和推荐聚合按逻辑 media id 保存，不随扫描 generation 复制；删除索引行不隐式删除用户行为。
- 物化顺序先写非 current header 和连续、无重复的 rows，再在单一事务内撤销旧 current 并激活新 header。分页和 ordinal 定位始终限定 current header。
- v1 到 v2 迁移为物化顺序增加 `randomSeed`、`algorithmVersion` 和 `generatedAtEpochMs`，旧普通排序行迁移后分别为 null、null 和 0。

DAO 当前提供同步接口；扫描器、排序器和 repository 必须在非主线程调用，Room 数据库仍按应用单例拥有。

## 扫描阶段

1. `Discover`：枚举目录项，只读取轻量 stat，批量 upsert 到工作 generation。
2. `Match`：按规范化 basename 和多轴后缀建立脚本候选，记录冲突而非静默覆盖。
3. `Probe`：限并发读取时长、分辨率、帧率、编码和字幕；当前/可见项优先。
4. `Thumbnail`：按 Feed 邻项、当前相册 viewport、剩余库顺序排队。
5. `Commit`：只有成功遍历来源后才把工作 generation 标成 current。
6. `Cleanup`：提交后延迟删除 current generation 不再引用的索引和缩略图。

取消、权限丢失、SMB 断线或进程终止时，工作 generation 标记 incomplete；旧 current generation 保持可用，禁止清缓存。

## 本地与 SMB

- 本地目录通过 SAF tree URI 获取持久授权，不访问用户未选择的目录。
- T15 的本地实现由 `SafPermissionStore`、`AndroidSafDocumentTree` 和 `LocalSafScanner` 组成：授权只申请并持久化 `READ` flag，扫描器只查询 document metadata，不打开媒体内容，也不使用 root 或全盘路径。
- SAF children 查询结果在扫描器内按 NFKC、小写相对路径和 document id 排序；folder/media/script 的逻辑 ID 由 source id 与 document id 的 SHA-256 前缀构成，重命名在 provider 保持 document id 时不会产生新媒体。
- 每次扫描使用高于来源历史最大值的新 generation；发现阶段结束后批量写入工作 generation，脚本按同目录规范化 basename 和可选 `L0` 等轴后缀匹配。新增、修改和删除统计只比较当前快照，删除不会触碰播放行为或缩略图。
- `Cancellation`、权限丢失和 provider IO 错误均将工作 generation 标为 `INCOMPLETE`；只有完整遍历才调用 DAO 的 `commitScan` 切换 current。恢复通过新的 generation 重跑，旧快照在整个过程中可读。
- SMB 使用同一索引接口，但网络 IO 有单独超时、重试和离线状态；不让 WorkManager 无限重试。
- SMB 断线时展示已索引元数据和明确离线状态；只有可用缓存才能播放。
- 来源凭据不进入 Room 明文；导出配置时只导出非秘密字段。

### T18 SMB 来源与凭据封装

- SMB 实现锁定 `com.hierynomus:smbj:0.14.0`；`SmbjDocumentTree` 只调用目录枚举和轻量文件属性接口，不打开媒体内容。
- `SmbSourceConfig` 只包含主机、端口、共享名、根路径、超时和重试上限。`Source.rootLocator` 使用不含用户信息的 `smb://host:port/share/path`；密码只能通过 `credentialRef` 间接引用。
- `AndroidKeystoreCredentialStore` 为每个引用创建 Android Keystore AES-GCM 密钥，密文 blob 放在应用私有 `SharedPreferences`；读回后返回副本，认证完成立即清零密码数组。`InMemoryCredentialStore` 仅用于单元测试和预览。
- SMB 扫描复用 `LocalSafScanner` 的 Discover/Match/Commit generation 流程，但来源类型必须为 `SMB`。离线或连接失败不开始新 generation，已有 current generation 继续作为离线缓存；扫描中断仍标记 `INCOMPLETE`，不清理旧缩略图。
- 连接、会话、共享和客户端按逆序关闭；SMBJ 的读取/事务超时由 `SmbSourceConfig` 约束在有界范围内，重试由上层任务调度器控制且不得无限重试。

## 稳定排序

排序字段与 `SortSpec` 一致。每种排序都必须有最终稳定键：

1. 主字段按用户方向；
2. 规范化标题或路径作为次级键；
3. `MediaId` 作为最终键。

空值顺序必须固定，不随 SQLite/平台默认变化。分辨率排序使用像素面积，再按宽高；状态排序使用明确枚举顺序，不能按本地化字符串。

首页本地/SMB和相册共享同一个 `SortDefinition`。改变排序后：

- 当前媒体继续播放；
- 新顺序生成后通过 `mediaId` 查找新 ordinal；
- 下一次上下翻页从新 ordinal 相邻项开始；
- 旧 generation 的预加载结果在不匹配时丢弃。

## 物化顺序

物化顺序不是把 2,000 个 id 序列化进一个 blob，而是 header + row：

- 常用 scope（来源、文件夹、历史、收藏、推荐）与 sort 组合建立 header。
- row 以 `(headerId, ordinal)` 聚簇并对 `(headerId, mediaId)` 建定位索引。
- 内容/stat/行为变更只失效受影响 header；后台重建后原子切换 generation。
- UI 使用分页读取固定 generation，避免滚动中顺序漂移。
- 推荐顺序含随机 seed、算法版本和生成时间，便于复现。

## 缩略图缓存

- cache key 包含 media identity、修改指纹、目标尺寸和提取策略版本。
- 文件写入先到同目录临时文件，解码验证后原子 rename。
- 列表项使用稳定尺寸和占位，不因缩略图到达改变网格几何。
- 完整扫描成功后，清理任务按数据库引用集删除孤儿；任何宽范围删除前校验缓存根目录。

## 性能验收

- 使用 2,500 个合成媒体、对应脚本和多层目录的固定 fixture。
- 冷启动先展示旧快照，不等待扫描完成。
- 排序重建在后台进行，UI 能继续读取旧 generation。
- 当前项和下一项的元数据/缩略图优先级高于全库任务。
- 中断 25%、50%、95% 扫描均不删除旧数据或缩略图。

## T16 元数据和缩略图流水线

- `core/index/pipeline/BoundedMediaWorkQueue` 是固定容量、确定性优先队列；`CURRENT`、`ADJACENT`、`REMAINING` 依次处理，满载时只淘汰更低优先级工作，同优先级保持先入先出。
- `MediaMetadataProbe` 为注入式 metadata-only 契约，结果写回工作 generation 的 `Media` 行并以 `READY` 标记；探测失败只计入失败数，队列继续处理后续媒体。
- `ThumbnailCache` 的 key 包含 media identity、大小/修改指纹、目标尺寸和策略版本；写入同目录临时文件，非空校验后优先使用 atomic move 发布，缓存只保存文件而非 bitmap blob。
- `MetadataThumbnailPipeline` 可取消且不创建线程；取消不会提交或清理旧快照。`cleanupCommitted` 先验证来源 current generation，再依据数据库 `READY` 引用集延迟删除孤儿缓存。

## T17 排序与物化顺序

- `StableMediaSorter` 覆盖 `SortSpec` 的全部字段和双向排序；规范化标题、路径、媒体 ID 始终作为升序稳定次键，空值固定排在末尾，分辨率按像素面积再按宽高比较。
- `MaterializedOrderBuilder` 只读取 current media 快照和播放状态，先构造完整 header/rows，再调用 DAO 事务原子激活；排序切换不修改播放状态或当前媒体实体。
- header identity 含来源、scope、字段、方向和 generation；当前媒体在新 header 中通过 `currentOrdinal` 重定位，旧预加载 generation 由上层丢弃。

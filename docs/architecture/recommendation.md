# 纯算法推荐

## 选择

采用“确定性候选评分 + 时间衰减 + MMR 多样性重排 + 固定比例探索”。不运行本地模型，不上传媒体名、路径、缩略图、脚本或行为。每条推荐可展示本地可解释原因，例如“常看此文件夹”“收藏过相似标题”“尚未播放”。

该方案适合单用户、约 2,000 项的本地库：数据稀疏且没有跨用户协同信号，复杂模型难以稳定训练，也增加隐私、体积和不可解释性成本。

## 事件语义

| 事件 | 含义 | 推荐处理 |
| --- | --- | --- |
| 前台唯一观看覆盖 | 用户实际看过的不重复时间区间 | 主要隐式正信号 |
| completion | 前台覆盖首次达到阈值 | 中等正信号，每次播放会话最多一次 |
| manual replay | 用户显式从头重播 | 强于自动循环的正信号 |
| foreground auto loop | 前台设置导致的循环 | 弱、对数饱和 |
| background auto loop | 锁屏/后台自动循环 | 极弱、快速封顶 |
| fast skip | 很短时间内上划离开 | 负信号，但防止误触累积过快 |
| like | 显式喜欢 | 强正信号 |
| favorite | 显式收藏 | 强正信号并进入集合 |
| dislike | 显式不喜欢 | 强负信号和短期候选过滤 |

循环次数不能等同完成度。完成度定义为：

```text
coverage = min(uniqueForegroundPlayedMs / durationMs, 1.0)
```

同一时间区间在循环中重复播放不增加该会话 coverage。建议 completion 阈值初始为 0.90；短视频可另设最小有效观看秒数，避免 2 秒文件制造高权重。

## 特征和评分

仅使用本地可解释特征：

- 显式反馈：like、favorite、dislike。
- 行为质量：coverage、manual replay、fast skip、最近一次播放。
- 内容偏好：规范化文件夹层级、文件名 token、脚本轴类型、时长桶、分辨率桶。
- 新颖度：未看、久未看、最近连续出现的相似项惩罚。

文本 token 只在本机做大小写/分隔符规范化和确定性词频统计，不用 embedding。初始相关性可写为版本化加权和：

```text
relevance = explicitFeedback
          + contentAffinity
          + effectiveWatch
          + replayBoost
          + novelty
          - skipPenalty
          - recentRepeatPenalty
```

历史贡献使用指数时间衰减：

```text
decay(ageDays, halfLifeDays) = exp(-ln(2) * ageDays / halfLifeDays)
```

循环增益必须独立且饱和，例如：

```text
replayBoost = min(cap,
    a * log1p(manualReplayCount) +
    b * log1p(foregroundLoopCount) +
    c * log1p(backgroundLoopCount))
where a > b >> c
```

初始建议 `a:b:c = 1.0:0.25:0.03`，并让整个 replayBoost 不超过一次显式 like 的一半。权重必须集中在一个算法版本文件中并有 golden tests，不能散落在 UI。

## MMR 多样性重排

从评分最高的一批候选中逐个选择：

```text
MMR(item) = lambda * normalizedRelevance(item)
          - (1 - lambda) * maxSimilarity(item, alreadySelected)
```

初始 `lambda = 0.72`，允许设置页在“更相关/更多样”有限范围内调整。相似度由同文件夹、标题 token Jaccard、时长桶和脚本轴集合组成。MMR 的目的是避免一整屏都是同目录/同命名簇，不是改变显式点踩过滤。

## 探索和冷启动

- 默认 8% 槽位来自未看候选，设置范围 5% 到 10%。
- 探索使用按生成 seed 的确定性 epsilon-greedy；同一 generation 重进页面不乱跳。
- 冷启动无行为时，按来源/文件夹分层抽样、最近新增和未看混排，不假装有用户画像。
- 小库不足时自动降低探索/多样性约束，不重复同一 media id 填位。
- 用户可清空行为画像；清空不删除媒体索引、收藏或播放断点，除非分别选择。

## 可测试性

- 固定媒体、事件、时间和 seed 时结果完全一致。
- 测试后台循环 1、10、1000 次都不会压过明确 like/favorite。
- 测试 dislike、fast skip 误触衰减、同名簇去重、小库和全部看过场景。
- 每个结果返回分项得分和重排原因，诊断导出时媒体名/路径脱敏。

## 资料依据

- Carbonell & Goldstein, 1998，MMR 在相关性和新颖性间做贪心重排：[DOI 10.1145/290941.291025](https://doi.org/10.1145/290941.291025)
- Hu, Koren & Volinsky, 2008，隐式反馈更接近带置信度的偏好而非直接评分：[DOI 10.1109/ICDM.2008.22](https://doi.org/10.1109/ICDM.2008.22)
- Sutton & Barto，epsilon-greedy 的探索/利用基础：[Reinforcement Learning: An Introduction](http://incompleteideas.net/book/the-book-2nd.html)

这些来源支持算法结构，不代表直接照搬其训练模型。本项目只实现可解释、单用户、离线的轻量版本。

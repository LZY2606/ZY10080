# 动作分段对齐工作台（pairwise-gsb）

面向语言研究团队的多来源动作分段对齐系统：导入视频时间码、姿态关键点和多位研究者的
动作分段，基于锚点与允许偏差生成候选对齐，研究者在网页上接受 / 拆分 / 合并 / 声明无法判断，
最终导出带证据链的共识层。

## 快速开始

```bash
./gradlew --no-daemon assemble                                   # 安装/构建
./gradlew --no-daemon test && ./gradlew --no-daemon run --args='--port 5211'
```

打开 <http://127.0.0.1:5211>，点击「加载演示数据」即可看到完整流程：

1. 左侧并排展示研究者 A / B 的来源层（边界 + 原文，允许重叠、嵌套、模糊边界）；
2. 中间是服务端生成的候选对齐（无锚点的区间显示为虚线「缺口」卡片）；
3. 右侧是共识层，可接受候选、编辑、拆分、合并、声明无法判断；
4. 「导出 JSON」下载包含层次关系、区间精度与证据引用的导出文档。

演示数据是一段 300 帧的可变帧率视频（第 137 帧丢失、帧率中途变化），
两位研究者的分段互相重叠/嵌套，其中「整理衣物」一段附近没有姿态锚点，会成为缺口。

## 数据模型

所有时间都以**原始帧标识（frameId）**表达，系统内部不做固定毫秒换算：

- `TimecodeTrack`：帧标识 → 呈现时间（pts）的轨道。丢帧表现为 frameId 跳号，
  可变帧率表现为 pts 间隔不等；帧间距离一律按「帧在轨道上的序位」计算。
- `FuzzyBound`：`{earliest, exact, latest}`。有 `exact` 为精确边界；
  否则为模糊边界（ earliest–latest 的允许范围），导出/导入全程保留，绝不压缩成精确点。
- `SourceLayer`：一位研究者的一份分段，导入后不可变；接受/拆分/合并只写共识层。
- `Keypoints`：姿态关键点，`fingerprint = sha256(algorithm:version)`。
  换用新算法即产生新指纹，旧共识片段仍绑定旧指纹（`keypointFingerprint` 字段）。
- `Alignment / Candidate`：服务端用关键点位移能量峰值检测锚点，按帧序距离在
  `toleranceFrames` 内匹配起止边界；匹配不到则生成 `gap` 候选（缺口），不拉伸原文。
- `ConsensusSegment`：共识层片段，带 `version`（乐观并发）、`parentId`（层次）、
  `evidence`（候选 / 来源片段 / 关键点指纹等证据引用）。

### 并发与冲突

编辑共识片段需携带 `baseVersion`：

- 版本一致：直接应用；
- 版本落后：按字段三方合并——双方都改了文本且结果不同 → `409 STATE_CONFLICT`
  （`details.textDiff` 给出 base/theirs/yours）；双方都改了区间且结果范围重叠 →
  `409`（`details.overlapRange` 给出重叠帧范围）；其余情况自动合入并返回 `merged: true`。

### 错误分类

| HTTP | type             | 含义                       |
|------|------------------|----------------------------|
| 400  | `INPUT_FORMAT`   | 输入格式错误（JSON、字段、区间） |
| 404  | `NOT_FOUND`      | 引用对象不存在              |
| 409  | `STATE_CONFLICT` | 状态冲突（版本过期、并发冲突、重复处理） |
| 500  | `INTERNAL`       | 内部故障                    |

## 持久化与恢复

数据目录（默认 `./data`，可用 `--args='--data 路径'` 指定）分四类物理隔离：

```
data/
  raw/        原始输入：videos/ keypoints/ layers/（写入后不可变）
  derived/    派生结果：alignments/、consensus 快照（缓存，可随时重建）
  events/     操作事件日志：<videoId>.jsonl（追加写 + fsync，权威来源）
  requests/   幂等记录：<request_id>.json（request_id → 首次响应）
```

- **原子写**：所有文件先写 `*.tmp` 再原子 rename；启动时清理残留 `.tmp`，
  崩溃在落盘中途不会暴露半成品。
- **事件溯源**：每次共识变更先追加事件日志（fsync）再更新快照；
  重启时从事件日志重建共识层，日志尾部不完整的半行会被截断。
- **幂等**：所有变更端点要求 `request_id`。相同 `request_id` 重放直接返回首次
  存储的响应（响应头 `X-Replayed: true`），不会产生第二份业务结果。

## API 摘要

```
POST /api/demo/seed                       加载演示数据
POST /api/videos                          注册视频（时间码轨）
POST /api/videos/{id}/keypoints           导入姿态关键点（算法+版本→指纹）
POST /api/videos/{id}/layers              导入研究者分段（来源层）
POST /api/videos/{id}/alignments          生成候选对齐（toleranceFrames）
GET  /api/videos/{id}                     视频全量状态（层/候选/共识）
POST /api/consensus/accept                接受候选 → 共识层
POST /api/consensus/undecidable           声明无法判断（候选或片段）
POST /api/consensus/split                 拆分（atFrame 须在区间内且非丢帧）
POST /api/consensus/merge                 合并（保留模糊边界）
POST /api/consensus/edit                  编辑（baseVersion 并发控制）
GET  /api/videos/{id}/export              导出（层次/精度/证据）
POST /api/import                          重新导入（模糊区间原样保留）
```

所有 POST 请求体必须包含 `request_id`。

## 测试

`./gradlew --no-daemon test` 覆盖：VFR/丢帧时间码、模糊边界精度、锚点对齐与缺口、
来源层不可变、拆分/合并、并发冲突与自动合入、指纹绑定、幂等重放、
崩溃恢复（临时文件/半行事件/损坏快照）、导出导入模糊区间往返、HTTP 错误分类。

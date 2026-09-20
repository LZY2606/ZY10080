# 多源动作片段对齐服务（Pair-wise Timeline Alignment）

语言研究团队导入一段视频的**时间码/帧表**、多位研究者写下的**动作片段**和**姿态关键点**，
系统把每份分段作为**独立来源**保存（允许重叠、嵌套、模糊起止），基于锚点与允许偏差生成
**候选对齐**，并在独立的**共识层**上接受、拆分、合并或声明无法判断。

关键判定（锚点解析、候选生成、冲突检测、指纹绑定）全部在服务端，浏览器只负责展示与提交。

## 安装 / 构建 / 演示

需要 JDK 17 或更高版本（`java -version`）。

```bash
# 安装构建（离线环境会使用本机 Gradle/依赖缓存）
./gradlew --no-daemon assemble

# 自动化测试 + 启动演示
./gradlew --no-daemon test
./gradlew --no-daemon run --args='--port 5211'
```

浏览器固定访问：**http://127.0.0.1:5211**

可选参数：`--data <目录>` 指定数据目录（默认 `./data`）。换一个空目录即可得到一套全新的库，
也用于导出包的重导入演示。

页面操作步骤：

1. 点「载入演示数据（VFR + 模糊区间 + 多研究者）」，自动导入非均匀时间间隔的帧表、
   两位研究者的片段（含 `fuzzyBetween` 模糊起止）与姿态关键点。
2. 选择容差帧数（默认 2），点「生成候选对齐」。页面并排显示两条来源轨道、一条共识轨道，
   以及候选列表和帧缺口。
3. 在候选卡片填写共识文本并「接受为共识」；对缺口可「声明无法判断」。
4. 在共识片段上「拆分」，或选择两个活跃片段「合并」（中间有缺口会被拒绝，文字不会被拉伸）。
5. 「导出 JSON 包」；把服务用空 `--data` 目录重启后可「在新库重放」。

## 为什么不能用固定毫秒换算

所有时间位置都以**原始帧标识**为准。帧表（`POST /api/import/frame-table`）逐行给出
`ordinal / frameId / timecode / timeNanos`，相邻帧的时间间隔可以任意不同（VFR），
丢帧仍必须以自己的原始 `frameId` 占一行，序号必须从 0 稠密连续。

片段的定位点（locator）只接受：

- `{"frameId":"cam0042"}`：精确原始帧；
- `{"ordinal":42}`：精确时间线序号（仅表示导入顺序，不做时间数学）；
- `{"timecode":"00:00:01:03"}`：必须是帧表中**精确存在**的时间码；
- `{"fuzzyBetween":{"from":{...},"to":{...}},"raw":"研究者原文描述"}`：模糊范围。

裸 `{"ms":1200}` 会被服务端拒绝（400 `INPUT_FORMAT`）：在 VFR/丢帧/帧率变化下，
毫秒无法可靠回指原始帧。解析结果始终是一个闭区间 `[ordinalLo, ordinalHi]`，
并携带两端的原始 `frameId`；精确锚点即 `[n,n]`，模糊范围永远不会被压缩成单点。

## 数据模型

```
FrameTable        视频帧表：ordinal ↔ frameId ↔ timecode ↔ timeNanos（VFR 友好，唯一时间权威）
SourceSegment     研究者来源片段：sourceId、researcher、rawText（原文，绝不改写）、Window
PoseData          某姿态算法版本的关键点 + 服务端计算的 fingerprint
Candidate(派生)   两个来源片段在容差内的候选：relation、edgeDistance、confidence、unionWindow
AlignmentResult   一次派生结果：候选列表 + 未覆盖帧缺口 gaps
ConsensusSegment  共识层：version、status(active/superseded/unjudgable)、window、
                  证据(evidenceSegmentIds/evidenceCandidateIds)、supersedes/children 层次、
                  decisionId、actor、poseFingerprint
Event(权威日志)   frame_table_imported / segments_imported / pose_imported /
                  alignment_generated / consensus_decision
```

`Window` 由两个 `Bound` 组成，`Bound` 记录 `ordinalLo/ordinalHi`、`frameIdLo/frameIdHi`、
`precise` 与可选 `raw`。两个来源片段的候选按帧序号比较边界距离：
起始边差距与结束边差距都不超过 `toleranceFrames` 才成为候选；并集窗口取最宽模糊范围，
派生过程不会收窄不确定性。关系标注为 `equal / contains / inside / partial`。
未被任何来源覆盖的帧区间作为 `gaps` 返回，不会被静默填充。

### 层次与共识

- 接受（accept）只**新增**一个共识片段（v1），来源层完全不动。
- 拆分（split）把一个活跃片段按连续、无重叠的子区间替换；父片段变 `superseded` 并记录
  `children`，子片段记录 `supersedes`。
- 合并（merge）仅允许首尾相接的活跃片段；中间存在帧缺口时返回 400，避免把文字拉伸到无证据区间。
  若被合并片段绑定了不同姿态指纹，结果会标注 `MERGED_ACROSS_MIXED_POSE_FINGERPRINTS`。
- 无法判断（unjudgable）记录一个 `status=unjudgable` 的区间与理由。

### 并发冲突

决定请求带 `baseVersion`（拆分）或每段 `{consensusId, baseVersion}`（合并）。
两位研究者同时编辑同一共识片段时：

- **重叠帧范围且文本不同** → 409 `STATE_CONFLICT`，响应 `details.conflicts` 列出对方操作者、
  对方文本、重叠帧范围（含 frameId）；
- 范围不重叠（或文本相同）→ 自动并存/合入，不产生冲突。

### 姿态指纹

`PoseData` 的指纹由算法名、版本和提交的关键点内容计算。共识在**决定时**绑定当时的指纹；
将来导入新算法的姿态数据，旧共识仍指向旧指纹（导出与详情中可见）。

## 持久化布局与恢复

数据目录下三类内容物理分离：

```
<data>/
  raw/        原始输入（帧表/来源/姿态的不可变 JSON，按内容哈希命名）
  events/
    journal.log   只增事件日志（每行一个完整 JSON，权威事实来源）
  derived/
    state.snapshot.json   可重建的派生快照
  quarantine/ 崩溃时撕下的半截日志行隔离于此
```

- 写入顺序：原始文件先 `write → fsync → 原子 rename`，然后事件 `append → fsync(force true)`；
  内存状态由事件回放重建，派生快照最后原子替换。
- 进程在落盘中途退出：未写完的最后一日志行会在启动时被识别、隔离并截断到最后一条完整记录，
  不会暴露半个业务结果；`*.tmp-*` 临时文件不会被读取。
- 重启自动重放 `journal.log`，恢复全部来源、派生结果、共识层次、姿态指纹与幂等索引。

### 幂等

每个写请求必须带 `requestId`。相同 `requestId` + 相同请求体重放：返回首次结果，不产生第二份
业务结果、不新增事件；相同 `requestId` 但请求体不同：409 `STATE_CONFLICT`。
事件中保存了首次响应体，因此**重启后**重放仍返回完整业务结果。

## 错误响应

统一信封 `{"error":..., "message":..., "details":...}`，三类故障可区分：

| HTTP | error            | 场景 |
| --- | --- | --- |
| 400 | `INPUT_FORMAT`   | JSON 格式错误、定位点不合法、裸毫秒、缺口合并等 |
| 404 | `NOT_FOUND`      | 未知路由、候选或共识片段不存在 |
| 409 | `STATE_CONFLICT` | 顺序错误（先导帧表）、requestId 复用但请求不同、版本过期、重叠编辑冲突、重导入到非空库 |
| 500 | `INTERNAL`       | 服务端内部故障 |

## HTTP API

| 方法 路径 | 说明 |
| --- | --- |
| `POST /api/import/frame-table` | 导入帧表（VFR 时间码） |
| `POST /api/import/segments` | 导入一位研究者的独立来源片段 |
| `POST /api/import/pose` | 导入某算法版本关键点，返回指纹 |
| `POST /api/alignment/generate` | `{toleranceFrames, poseId?}` 生成候选与缺口（派生、可重算、幂等） |
| `GET  /api/state` | 帧表/来源/姿态/派生/共识全量视图 |
| `GET  /api/sources` `/api/consensus` | 分层查询 |
| `POST /api/consensus/decide` | `action = accept / split / merge / unjudgable` |
| `GET  /api/export` | 导出：层次关系、区间精度、每个决定的证据引用、完整事件日志 |
| `POST /api/reimport` | 仅允许空库；按事件日志原样回放，保留模糊区间与指纹 |

### 请求示例

```json
// 导入模糊片段
{"requestId":"s1","sourceId":"li-notes","researcher":"Li","segments":[
  {"rawText":"turns around ~17-23",
   "start":{"fuzzyBetween":{"from":{"frameId":"cam0017"},"to":{"frameId":"cam0019"}}},
   "end":  {"fuzzyBetween":{"from":{"frameId":"cam0021"},"to":{"frameId":"cam0023"}}}}]}

// 接受候选
{"requestId":"d1","action":"accept","candidateId":"cand-xxxx","text":"共识文本","actor":"A"}

// 拆分（连续、覆盖父区间）
{"requestId":"d2","action":"split","consensusId":"cs-xxxx","baseVersion":1,
 "parts":[{"text":"前","start":{"frameId":"cam0004"},"end":{"frameId":"cam0006"}},
          {"text":"后","start":{"frameId":"cam0007"},"end":{"frameId":"cam0009"}}]}
```

### 导出与重导入

`GET /api/export` 包含帧表、来源（含模糊窗口）、姿态、派生对齐、决定事件和共识明细；
每个共识片段都带区间精度（`precise` 与两端 frameId）、证据片段/候选 id、父子层次与姿态指纹。
`POST /api/reimport` 重放导出包中的**权威事件日志**，因此模糊区间不会被压成精确时间点，
候选 id、决定 id、指纹和层次关系原样保留。

## 技术栈与结构

- 纯 Java 17，零第三方运行时依赖（JDK 内置 `HttpServer`；JSON 为内置小解析器）。
- 测试：JUnit Jupiter 5.10.2，共 23 个用例，覆盖时间锚点、VFR、候选/缺口、
  共识接受/拆分/合并、并发冲突、幂等、崩溃日志隔离、重启恢复、导出往返与 HTTP 错误分类。

```
src/main/java/org/research/timeline/
  model/    Temporal/FrameTable/SourceSegment/PoseData/Candidate/ConsensusSegment/AlignmentResult
  store/    Journal(只增+fsync+隔离) Store(事件溯源/原子派生快照/幂等) 编解码类
  service/  ApiService ImportService AlignmentService ConsensusService ApiError
  http/     HttpServer（路由与错误信封）
  util/     Json Maps Hash
src/main/resources/web/  index.html app.js styles.css（无关键判定）
src/test/java/           23 个 JUnit 用例
```

## 故障恢复操作手册

1. 服务启动时自动重放 `<data>/events/journal.log`；末尾损坏行会移到 `quarantine/`。
2. `derived/` 全部可删后重启重建；**不要**手工编辑 `events/journal.log`。
3. 想复制一套环境：复制 `raw/` 与 `events/`（或使用 `/api/export` + `/api/reimport`），
   派生内容无需复制。

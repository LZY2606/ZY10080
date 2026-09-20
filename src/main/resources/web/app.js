// All business judgment happens server-side; this file only renders and posts.
const $ = (id) => document.getElementById(id);
let state = null;

function uid(prefix) {
  return prefix + "-" + Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
}

function toast(message, ok = true) {
  const el = $("toast");
  el.textContent = message;
  el.className = ok ? "ok" : "err";
  el.style.display = "block";
  clearTimeout(toast._t);
  toast._t = setTimeout(() => (el.style.display = "none"), 6000);
}

async function api(path, method, body) {
  const res = await fetch(path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!res.ok) {
    const msg = data && data.error ? `${data.error}: ${data.message}` : `HTTP ${res.status}`;
    const err = new Error(msg);
    err.payload = data;
    throw err;
  }
  return data;
}

function demoFrameTable() {
  // VFR-friendly table: non-uniform time gaps, one dropped-frame slot kept with its original id.
  const frames = [];
  let nanos = 0;
  const gaps = [33_300_000, 33_300_000, 47_900_000, 33_300_000, 61_200_000,
                33_300_000, 41_000_000, 33_300_000, 33_300_000, 58_000_000];
  for (let i = 0; i < 60; i++) {
    const sec = Math.floor(nanos / 1_000_000_000);
    const ms = Math.floor((nanos % 1_000_000_000) / 1_000_000);
    const tc = `00:00:${String(sec).padStart(2, "0")}.${String(ms).padStart(3, "0")}`;
    frames.push({ ordinal: i, frameId: `camA_f${String(i).padStart(4, "0")}`, timecode: tc, timeNanos: nanos });
    nanos += gaps[i % gaps.length];
  }
  return { requestId: uid("req"), videoId: "lecture-2026-09-20", frames };
}

function demoSegments() {
  const f = (n) => `camA_f${String(n).padStart(4, "0")}`;
  const payloads = [
    {
      requestId: uid("req"), sourceId: "researcher-li-notes", researcher: "李",
      segments: [
        { rawText: "抬手指向黑板（约在第5到9帧）", start: { frameId: f(5) }, end: { frameId: f(9) } },
        { rawText: "转身，大概在 18 到 24 帧之间发生",
          start: { fuzzyBetween: { from: { frameId: f(18) }, to: { frameId: f(20) } }, raw: "约18-20帧" },
          end:   { fuzzyBetween: { from: { frameId: f(22) }, to: { frameId: f(24) } }, raw: "约22-24帧" } },
        { rawText: "结束鞠躬 52-57", start: { frameId: f(52) }, end: { frameId: f(57) } },
      ],
    },
    {
      requestId: uid("req"), sourceId: "researcher-watanabe-notes", researcher: "渡边",
      segments: [
        { rawText: "pointing gesture f6-f10", start: { frameId: f(6) }, end: { frameId: f(10) } },
        { rawText: "body turn 19-23 (uncertain)",
          start: { fuzzyBetween: { from: { frameId: f(19) }, to: { frameId: f(21) } } },
          end:   { fuzzyBetween: { from: { frameId: f(21) }, to: { frameId: f(23) } } },
        },
        { rawText: "walks off screen after 40", start: { frameId: f(40) }, end: { frameId: f(48) } },
      ],
    },
  ];
  return payloads;
}

function demoPose() {
  const f = (n) => `camA_f${String(n).padStart(4, "0")}`;
  const frames = [];
  for (let i = 0; i < 60; i++) {
    frames.push({ frameId: f(i), ordinal: i,
      keypoints: [{ name: "right_wrist", x: 0.3 + 0.01 * i, y: 0.4, confidence: 0.9 }] });
  }
  return { requestId: uid("req"), algorithm: "openpose-lite", algorithmVersion: "v3.1", frames };
}

async function loadDemo() {
  try {
    const ft = await api("/api/import/frame-table", "POST", demoFrameTable());
    toast("帧表已导入：" + ft.frameCount + " 帧（VFR）");
    for (const payload of demoSegments()) {
      await api("/api/import/segments", "POST", payload);
    }
    await api("/api/import/pose", "POST", demoPose());
    toast("帧表、两位研究者片段与姿态关键点已导入");
    await refresh();
  } catch (e) {
    toast(e.message, false);
  }
}

async function refresh() {
  try {
    state = await api("/api/state", "GET");
    renderPoseSelect();
    renderTimeline();
    renderCandidates();
    renderConsensus();
  } catch (e) {
    toast(e.message, false);
  }
}

function renderPoseSelect() {
  const sel = $("pose-select");
  const current = sel.value;
  sel.innerHTML = '<option value="">（不绑定姿态）</option>';
  for (const pose of state.poses || []) {
    const opt = document.createElement("option");
    opt.value = pose.poseId;
    opt.textContent = `${pose.algorithm} ${pose.algorithmVersion} (${pose.fingerprint.slice(0, 8)})`;
    sel.appendChild(opt);
  }
  sel.value = current;
}

function frameCount() {
  return state.frameTable ? state.frameTable.frameCount : 1;
}

function pct(ordinal) {
  return (ordinal / Math.max(1, frameCount() - 1)) * 100;
}

function labelWindow(w) {
  const s = w.start, e = w.end;
  const left = s.precise ? s.frameIdLo : `${s.frameIdLo}…${s.frameIdHi}`;
  const right = e.precise ? e.frameIdLo : `${e.frameIdLo}…${e.frameIdHi}`;
  return `${left} → ${right}`;
}

function renderTimeline() {
  const ruler = $("ruler");
  const tracks = $("tracks");
  ruler.innerHTML = "";
  tracks.innerHTML = "";
  if (!state.frameTable) {
    tracks.innerHTML = '<div style="padding:12px;color:#829ab1">尚未导入帧表</div>';
    return;
  }
  const n = frameCount();
  const step = Math.max(1, Math.round(n / 12));
  for (let i = 0; i < n; i += step) {
    const tick = document.createElement("div");
    tick.className = "tick";
    tick.style.left = pct(i) + "%";
    tick.textContent = state.frameTable.frames[i].frameId;
    ruler.appendChild(tick);
  }

  // Group source segments by independent source.
  const groups = new Map();
  for (const seg of state.sources || []) {
    if (!groups.has(seg.sourceId)) groups.set(seg.sourceId, []);
    groups.get(seg.sourceId).push(seg);
  }
  for (const [sourceId, segments] of groups) {
    const track = document.createElement("div");
    track.className = "track";
    const label = document.createElement("div");
    label.className = "track-label";
    label.textContent = `来源：${segments[0].researcher} (${sourceId})`;
    track.appendChild(label);
    for (const seg of segments) {
      track.appendChild(makeBlock(seg.window, seg.rawText,
        ["src", seg.window.precise ? "" : "fuzzy"].join(" "), labelWindow(seg.window)));
    }
    tracks.appendChild(track);
  }

  const consTrack = document.createElement("div");
  consTrack.className = "track";
  const clabel = document.createElement("div");
  clabel.className = "track-label";
  clabel.textContent = "共识层（不回写来源）";
  consTrack.appendChild(clabel);
  for (const cs of state.consensus || []) {
    const cls = cs.status === "unjudgable" ? "gap"
      : cs.status === "superseded" ? "cons superseded" : "cons";
    consTrack.appendChild(makeBlock(cs.window,
      cs.status === "unjudgable" ? `无法判断：${cs.note}` : cs.text,
      cls, `${labelWindow(cs.window)} v${cs.version} ${cs.status}`));
  }
  tracks.appendChild(consTrack);
}

function makeBlock(window, text, cls, meta) {
  const block = document.createElement("div");
  block.className = "block " + cls;
  block.style.left = pct(window.ordinalLo) + "%";
  block.style.width = Math.max(1.2, pct(window.ordinalHi) - pct(window.ordinalLo)) + "%";
  block.title = `${text}\n${meta}`;
  block.textContent = text;
  const m = document.createElement("span");
  m.className = "meta";
  m.textContent = meta;
  block.appendChild(m);
  return block;
}

function renderCandidates() {
  const host = $("candidates");
  host.innerHTML = "";
  const alignments = state.alignments || [];
  if (alignments.length === 0) {
    host.innerHTML = '<div style="color:#829ab1;font-size:13px">尚未生成对齐。选择容差后点击“生成候选对齐”。</div>';
    return;
  }
  for (const align of alignments) {
    const box = document.createElement("div");
    box.className = "card";
    const segById = new Map((state.sources || []).map((s) => [s.segmentId, s]));
    let html = `<div class="title">对齐结果 ${align.resultId}
      <span class="tag">容差 ±${align.toleranceFrames} 帧</span>
      ${align.poseFingerprint ? `<span class="tag partial">姿态指纹 ${align.poseFingerprint.slice(0, 10)}…</span>` : ""}
    </div>`;
    if (align.gaps && align.gaps.length) {
      html += `<div class="meta">检测到 ${align.gaps.length} 个缺口（不拉伸文字）：</div>`;
      for (const gap of align.gaps) {
        html += `<div class="card" style="background:#fff8f7">
          <span class="tag gap">缺口 ${gap.gapFrames} 帧</span>
          ${gap.frameIdLo} → ${gap.frameIdHi}
          <div class="meta">${gap.reason}</div>
          <button class="danger" onclick="markGap('${align.resultId}','${gap.frameIdLo}','${gap.frameIdHi}')">声明无法判断</button>
        </div>`;
      }
    }
    for (const cand of align.candidates) {
      const parts = cand.segmentIds.map((id) => segById.get(id)).filter(Boolean);
      html += `<div class="card">
        <span class="tag ${cand.relation}">${cand.relation}</span>
        <span class="meta">边界距离 ${cand.edgeDistanceFrames} 帧 · 置信度 ${cand.confidence}</span>
        <div>${parts.map((p) => `<div>【${p.researcher}】${escapeHtml(p.rawText)}</div>`).join("")}</div>
        <div class="meta">${labelWindow(cand.unionWindow)}</div>
        <div class="row">
          <input id="txt-${cand.candidateId}" style="flex:1" placeholder="共识文本（不会改动研究者原文）">
          <button onclick="acceptCandidate('${cand.candidateId}','${align.poseFingerprint || ""}')">接受为共识</button>
        </div>
      </div>`;
    }
    box.innerHTML = html;
    host.appendChild(box);
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}

async function acceptCandidate(candidateId) {
  const input = $("txt-" + candidateId);
  const text = input.value.trim();
  if (!text) { toast("请填写共识文本", false); return; }
  try {
    await api("/api/consensus/decide", "POST", {
      requestId: uid("req"), action: "accept", candidateId, text,
      actor: currentActor(),
    });
    toast("已产生新的共识层，来源层未改动");
    await refresh();
  } catch (e) { showConflict(e); }
}

async function markGap(resultId, loFrame, hiFrame) {
  try {
    await api("/api/consensus/decide", "POST", {
      requestId: uid("req"), action: "unjudgable",
      reason: `对齐 ${resultId} 的未覆盖区间`,
      start: { frameId: loFrame }, end: { frameId: hiFrame },
      actor: currentActor(),
    });
    toast("已记录为无法判断的缺口");
    await refresh();
  } catch (e) { showConflict(e); }
}

function currentActor() {
  return localStorage.getItem("actor") || "研究员";
}

function showConflict(e) {
  let detail = e.message;
  if (e.payload && e.payload.details && e.payload.details.conflicts) {
    detail += "\n\n冲突明细：\n" + e.payload.details.conflicts.map((c) =>
      `- ${c.actor}: "${c.text}" 重叠帧 ${c.overlapWindow.frameIdLo}…${c.overlapWindow.frameIdHi}`).join("\n");
  }
  toast(detail, false);
}

function renderConsensus() {
  const host = $("consensus-list");
  host.innerHTML = "";
  const list = state.consensus || [];
  if (!list.length) {
    host.innerHTML = '<div style="color:#829ab1;font-size:13px">还没有共识片段。</div>';
    return;
  }
  for (const cs of list) {
    const card = document.createElement("div");
    card.className = "card";
    const fp = cs.poseFingerprint ? `姿态指纹 ${cs.poseFingerprint.slice(0, 10)}…（换算法后旧共识仍绑定它）` : "无姿态指纹";
    card.innerHTML = `
      <div class="title">
        <span class="tag ${cs.status}">${cs.status}</span>
        ${cs.status === "unjudgable" ? "无法判断" : escapeHtml(cs.text)}
      </div>
      <div class="meta">${cs.consensusId} · v${cs.version} · ${labelWindow(cs.window)} · 操作者 ${escapeHtml(cs.actor || "")}</div>
      <div class="meta">${cs.note ? escapeHtml(cs.note) : ""}</div>
      <div class="evidence">证据来源: ${(cs.evidenceSegmentIds || []).join(", ") || "—"}
        | 候选: ${(cs.evidenceCandidateIds || []).join(", ") || "—"}
        | 替代: ${(cs.supersedes || []).join(", ") || "—"}
        | 子片段: ${(cs.children || []).join(", ") || "—"}</div>
      <div class="evidence">${fp}</div>
      <div class="row" id="ops-${cs.consensusId}"></div>`;
    const ops = card.querySelector("#ops-" + cssEscape(cs.consensusId));
    if (cs.status === "active") {
      const splitBtn = document.createElement("button");
      splitBtn.className = "secondary";
      splitBtn.textContent = "拆分";
      splitBtn.onclick = () => splitSegment(cs);
      ops.appendChild(splitBtn);
    }
    host.appendChild(card);
  }
  renderMergeControls(host, list);
}

function cssEscape(s) { return window.CSS && CSS.escape ? CSS.escape(s) : s.replace(/[^a-zA-Z0-9_-]/g, "\\$&"); }

async function splitSegment(cs) {
  const lo = cs.window.ordinalLo, hi = cs.window.ordinalHi;
  if (hi - lo < 2) { toast("该片段至少要有 3 帧才能拆成两段", false); return; }
  const mid = lo + Math.round((hi - lo) / 2);
  const midFrame = state.frameTable.frames[mid].frameId;
  const t1 = prompt(`第一段文本（结束于 ${midFrame} 之前一帧）`, cs.text + " / 前半");
  if (t1 === null) return;
  const t2 = prompt(`第二段文本（从 ${midFrame} 开始）`, cs.text + " / 后半");
  if (t2 === null) return;
  const before = state.frameTable.frames[mid - 1].frameId;
  try {
    await api("/api/consensus/decide", "POST", {
      requestId: uid("req"), action: "split",
      consensusId: cs.consensusId, baseVersion: cs.version,
      parts: [
        { text: t1, start: { frameId: cs.window.start.frameIdLo }, end: { frameId: before } },
        { text: t2, start: { frameId: midFrame }, end: { frameId: cs.window.end.frameIdHi || cs.window.end.frameIdLo } },
      ],
      actor: currentActor(),
    });
    toast("已拆分，旧版本保留为 superseded");
    await refresh();
  } catch (e) { showConflict(e); }
}

function renderMergeControls(host, list) {
  const active = list.filter((c) => c.status === "active");
  if (active.length < 2) return;
  const card = document.createElement("div");
  card.className = "card";
  card.innerHTML = '<div class="title">合并两个活跃共识片段</div>';
  const s1 = document.createElement("select"), s2 = document.createElement("select");
  for (const cs of active) {
    s1.add(new Option(`${cs.consensusId} ${cs.text.slice(0, 18)}`, cs.consensusId));
    s2.add(new Option(`${cs.consensusId} ${cs.text.slice(0, 18)}`, cs.consensusId));
  }
  s2.selectedIndex = Math.min(1, active.length - 1);
  const txt = document.createElement("input");
  txt.placeholder = "合并后的共识文本";
  txt.style.flex = "1";
  const btn = document.createElement("button");
  btn.textContent = "合并（有缺口会被拒绝）";
  btn.onclick = async () => {
    if (s1.value === s2.value) { toast("请选择两个不同片段", false); return; }
    if (!txt.value.trim()) { toast("请填写合并文本", false); return; }
    const byId = new Map(active.map((c) => [c.consensusId, c]));
    try {
      await api("/api/consensus/decide", "POST", {
        requestId: uid("req"), action: "merge",
        segments: [
          { consensusId: s1.value, baseVersion: byId.get(s1.value).version },
          { consensusId: s2.value, baseVersion: byId.get(s2.value).version },
        ],
        text: txt.value.trim(),
        actor: currentActor(),
      });
      toast("已合并");
      await refresh();
    } catch (e) { showConflict(e); }
  };
  card.append(s1, s2, txt, btn);
  host.appendChild(card);
}

async function generateAlignment() {
  try {
    const body = {
      requestId: uid("req"),
      toleranceFrames: Number($("tolerance").value || 0),
    };
    if ($("pose-select").value) body.poseId = $("pose-select").value;
    const result = await api("/api/alignment/generate", "POST", body);
    toast(`生成 ${result.candidates.length} 个候选，${result.gaps.length} 个缺口`);
    await refresh();
  } catch (e) { toast(e.message, false); }
}

async function manualImport() {
  try {
    const body = JSON.parse($("import-json").value);
    const kind = $("import-kind").value;
    const path = { "frame-table": "/api/import/frame-table", segments: "/api/import/segments", pose: "/api/import/pose" }[kind];
    if (!body.requestId) body.requestId = uid("req");
    const result = await api(path, "POST", body);
    toast("导入成功");
    $("import-json").value = JSON.stringify(result, null, 2);
    await refresh();
  } catch (e) { toast(e.message, false); }
}

async function exportBundle() {
  try {
    const bundle = await api("/api/export", "GET");
    $("export-area").value = JSON.stringify(bundle, null, 2);
    toast(`导出完成：${(bundle.events || []).length} 个事件，模糊区间以 fuzzyBetween 保留`);
  } catch (e) { toast(e.message, false); }
}

async function reimportBundle() {
  try {
    const bundle = JSON.parse($("export-area").value);
    const result = await api("/api/reimport", "POST", { requestId: uid("req"), bundle });
    toast("重导入完成：" + JSON.stringify(result));
  } catch (e) {
    toast("重导入仅允许在空数据目录（重启到新的 --data 目录）。\n" + e.message, false);
  }
}

$("btn-demo").onclick = loadDemo;
$("btn-refresh").onclick = refresh;
$("btn-align").onclick = generateAlignment;
$("btn-import").onclick = manualImport;
$("btn-export").onclick = exportBundle;
$("btn-reimport").onclick = reimportBundle;
refresh();

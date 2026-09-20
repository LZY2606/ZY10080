/* 动作分段对齐工作台前端：只负责展示与发起请求，所有判定在服务端。 */
const VIDEO_ID_KEY = 'gsb.videoId';
let state = null;
const mergeSelection = new Set();

function rid() { return crypto.randomUUID(); }

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    const err = data && data.error ? data.error : { type: 'INTERNAL', message: 'HTTP ' + res.status };
    const e = new Error(err.message);
    e.type = err.type;
    e.details = err.details;
    throw e;
  }
  return data;
}

function showAlert(kind, msg) {
  const el = document.getElementById('alert');
  el.className = kind;
  el.textContent = msg;
}
function clearAlert() { document.getElementById('alert').className = 'hidden'; }

function fmtBound(b) {
  if (b.exact != null) return `帧 ${b.exact}`;
  return `帧 ${b.earliest}–${b.latest} ≈`;
}
function fmtInterval(iv) {
  return `${fmtBound(iv.start)} → ${fmtBound(iv.end)}`;
}
function precisionBadge(b) {
  return b.exact != null
    ? '<span class="badge exact">精确</span>'
    : '<span class="badge fuzzy">模糊</span>';
}

async function refresh() {
  const vid = localStorage.getItem(VIDEO_ID_KEY);
  if (!vid) { document.getElementById('board').className = 'hidden'; return; }
  try {
    state = await api('GET', `/api/videos/${vid}`);
    render();
  } catch (e) {
    showAlert('error', `${e.type}: ${e.message}`);
  }
}

function render() {
  document.getElementById('board').className = '';
  const v = state.video;
  const fp = state.consensus.keypointFingerprint || '(无)';
  document.getElementById('video-info').textContent =
    `视频 ${v.id} · ${v.track.frames.length} 帧 · 共识指纹 ${fp}`;

  const cols = document.getElementById('columns');
  cols.innerHTML = '';

  // 来源层列（只读展示，绝不修改）
  for (const layer of state.layers) {
    const col = document.createElement('div');
    col.className = 'column';
    col.innerHTML = `<h2>来源层 · ${layer.researcher}</h2>`;
    const body = document.createElement('div');
    body.className = 'body';
    for (const seg of layer.segments) {
      const card = document.createElement('div');
      card.className = 'card';
      card.innerHTML = `
        <div class="bounds">${fmtInterval(seg.interval)}
          ${precisionBadge(seg.interval.start)}${precisionBadge(seg.interval.end)}</div>
        <div class="text"></div>
        <div class="meta">${seg.id}</div>`;
      card.querySelector('.text').textContent = seg.text;
      body.appendChild(card);
    }
    col.appendChild(body);
    cols.appendChild(col);
  }

  // 候选列
  for (const al of state.alignments) {
    const col = document.createElement('div');
    col.className = 'column';
    col.innerHTML = `<h2>候选对齐 · ${al.sourceLayerId} (±${al.toleranceFrames} 帧)</h2>`;
    const body = document.createElement('div');
    body.className = 'body';
    for (const c of al.candidates) {
      const card = document.createElement('div');
      card.className = 'card' + (c.gap ? ' gap' : '');
      const target = c.gap ? '<span class="badge gap">缺口</span>' : fmtInterval(c.target);
      card.innerHTML = `
        <div class="bounds">${target}</div>
        <div class="text"></div>
        <div class="meta">${c.id} · 状态 ${c.status}</div>`;
      card.querySelector('.text').textContent = c.text;
      if (c.status === 'PENDING') {
        const actions = document.createElement('div');
        actions.className = 'actions';
        if (!c.gap) {
          const ok = document.createElement('button');
          ok.textContent = '接受';
          ok.onclick = () => act(() => api('POST', '/api/consensus/accept',
            { request_id: rid(), alignmentId: al.id, candidateId: c.id }), '已接受，写入共识层');
          actions.appendChild(ok);
        }
        const und = document.createElement('button');
        und.textContent = '无法判断';
        und.className = 'secondary';
        und.onclick = () => act(() => api('POST', '/api/consensus/undecidable',
          { request_id: rid(), alignmentId: al.id, candidateId: c.id }), '已标记无法判断');
        actions.appendChild(und);
        card.appendChild(actions);
      }
      body.appendChild(card);
    }
    col.appendChild(body);
    cols.appendChild(col);
  }

  // 共识层列
  const col = document.createElement('div');
  col.className = 'column';
  col.innerHTML = `<h2>共识层</h2>`;
  const body = document.createElement('div');
  body.className = 'body';
  const segs = [...state.consensus.segments].sort((a, b) =>
    a.interval.start.earliest - b.interval.start.earliest);
  for (const s of segs) {
    const active = s.status === 'ACTIVE';
    const card = document.createElement('div');
    card.className = 'card' + (s.status === 'UNDECIDABLE' ? ' undecidable' : '') + (active ? '' : ' inactive');
    card.innerHTML = `
      <div class="bounds">${fmtInterval(s.interval)}
        ${precisionBadge(s.interval.start)}${precisionBadge(s.interval.end)}
        <span class="badge status">${s.status}</span></div>
      <div class="text"></div>
      <div class="meta">${s.id} · v${s.version} · 指纹 ${s.keypointFingerprint || '-'}</div>`;
    card.querySelector('.text').textContent = s.text;
    if (active) {
      const actions = document.createElement('div');
      actions.className = 'actions';

      const edit = document.createElement('button');
      edit.textContent = '编辑';
      edit.onclick = () => editSegment(s);
      actions.appendChild(edit);

      const split = document.createElement('button');
      split.textContent = '拆分';
      split.className = 'secondary';
      split.onclick = () => splitSegment(s);
      actions.appendChild(split);

      const und = document.createElement('button');
      und.textContent = '无法判断';
      und.className = 'secondary';
      und.onclick = () => act(() => api('POST', '/api/consensus/undecidable',
        { request_id: rid(), segmentId: s.id, baseVersion: s.version }), '已标记无法判断');
      actions.appendChild(und);

      const sel = document.createElement('label');
      sel.style.fontSize = '12px';
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.checked = mergeSelection.has(s.id);
      cb.onchange = () => { cb.checked ? mergeSelection.add(s.id) : mergeSelection.delete(s.id); };
      sel.appendChild(cb);
      sel.appendChild(document.createTextNode(' 待合并'));
      actions.appendChild(sel);

      card.appendChild(actions);
    }
    body.appendChild(card);
  }
  col.appendChild(body);
  const bar = document.createElement('div');
  bar.className = 'merge-bar';
  const mergeBtn = document.createElement('button');
  mergeBtn.textContent = '合并选中片段';
  mergeBtn.onclick = mergeSelected;
  bar.appendChild(mergeBtn);
  col.appendChild(bar);
  cols.appendChild(col);
}

async function act(fn, okMsg) {
  clearAlert();
  try {
    await fn();
    if (okMsg) showAlert('info', okMsg);
    await refresh();
  } catch (e) {
    let msg = `${e.type}: ${e.message}`;
    if (e.details) msg += '\n' + JSON.stringify(e.details, null, 2);
    showAlert('error', msg);
    await refresh();
  }
}

function editSegment(s) {
  const text = prompt('新文本（留空保持不变）', s.text);
  const start = prompt('新起始帧（留空保持不变）', s.interval.start.exact ?? '');
  const end = prompt('新结束帧（留空保持不变）', s.interval.end.exact ?? '');
  const body = { request_id: rid(), segmentId: s.id, baseVersion: s.version };
  if (text && text !== s.text) body.text = text;
  if (start && end) {
    body.interval = {
      start: { exact: Number(start) },
      end: { exact: Number(end) },
    };
  }
  act(() => api('POST', '/api/consensus/edit', body), '已编辑（如与他人并行修改，非重叠变更会自动合入）');
}

function splitSegment(s) {
  const at = prompt(`在区间 ${fmtInterval(s.interval)} 内选择切分帧（须避开模糊边界与丢帧）`);
  if (!at) return;
  act(() => api('POST', '/api/consensus/split',
    { request_id: rid(), segmentId: s.id, atFrame: Number(at), baseVersion: s.version }), '已拆分');
}

function mergeSelected() {
  const ids = [...mergeSelection];
  if (ids.length < 2) { showAlert('error', '请至少勾选两个待合并片段'); return; }
  const versions = ids.map(id => {
    const s = state.consensus.segments.find(x => x.id === id);
    return s.version;
  });
  act(async () => {
    await api('POST', '/api/consensus/merge',
      { request_id: rid(), segmentIds: ids, baseVersions: versions });
    mergeSelection.clear();
  }, '已合并');
}

document.getElementById('btn-seed').onclick = () => act(async () => {
  const r = await api('POST', '/api/demo/seed', { request_id: rid() });
  localStorage.setItem(VIDEO_ID_KEY, r.videoId);
}, '演示数据已加载');

document.getElementById('btn-align-b').onclick = () => act(async () => {
  const vid = localStorage.getItem(VIDEO_ID_KEY);
  if (!vid) { showAlert('error', '请先加载演示数据'); return; }
  await api('POST', `/api/videos/${vid}/alignments`, {
    request_id: rid(), sourceLayerId: 'layer-b', keypointsId: 'kp-v1', toleranceFrames: 6,
  });
}, '已为研究者B生成候选');

document.getElementById('btn-export').onclick = () => act(async () => {
  const vid = localStorage.getItem(VIDEO_ID_KEY);
  if (!vid) { showAlert('error', '请先加载演示数据'); return; }
  const doc = await api('GET', `/api/videos/${vid}/export`);
  const blob = new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `export-${vid}.json`;
  a.click();
}, '已导出');

document.getElementById('btn-refresh').onclick = refresh;
refresh();

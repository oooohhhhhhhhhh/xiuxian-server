import * as api from '../api.js';
import * as utils from '../utils.js';

/**
 * 地图管理：地点增删改 + 连接/事件偏置编辑 + 野外分片总览。
 *
 * 通用表管理只能看到 map_locations 的裸行 —— 境界是个数字、邻接关系在 map_connections 里、
 * 灵气/地界倍率是两列 DOUBLE、事件偏置又在第三张表。本页把它们拼成"一张看得懂的地图"，
 * 并允许直接增删地点、接线、调偏置（保存后服务端会热重载地图缓存，不必重启进程）。
 *
 * 页上只有<b>已知世界</b>：野外不进数据库，地形由世界种子现算，
 * 只有"谁探索过、资源剩余"存成 data/wilderness 下的分片文件，因此野外单独一个只读视图。
 */

let locations = [];
let regions = [];
let filterRegion = '';
let summary = {};

const DANGER_NAMES = ['安全', '低危', '中危', '高危', '极危'];

export async function load() {
  utils.showLoading('mapTable', '加载中...');
  try {
    const resp = await api.get('/map/locations');
    if (!resp || resp.code !== 200) {
      utils.showEmpty('mapTable', '⚠️', (resp && resp.message) || '加载失败');
      return;
    }
    const data = resp.data || {};
    locations = data.locations || [];
    regions = data.regions || [];
    summary = data;

    renderSummary(data);
    renderRegionFilter();
    renderTable();
    renderTree();
    // 野外是另一套存储，单独拉一次；失败不影响本页主流程
    loadWilderness();
  } catch (e) {
    utils.showEmpty('mapTable', '⚠️', '加载失败: ' + e.message);
  }
}

function dangerBadge(loc) {
  const level = Number(loc.dangerLevel || 0);
  if (level <= 0) return '<span style="color:var(--t4)">安全</span>';
  // 只用 styles.css 里真实存在的 badge 类型：1 低危=info、2 中危=warning、
  // 3 高危=accent、4 极危=danger（本页新增的深红），四档必须一眼可分
  const type = level >= 4 ? 'danger' : (level === 3 ? 'accent' : (level === 2 ? 'warning' : 'info'));
  return utils.createBadge(`☠ ${loc.dangerName || DANGER_NAMES[level] || level}`, type);
}

function renderSummary(data) {
  const el = document.getElementById('mapSummary');
  if (!el) return;
  const known = locations;
  const safeCount = known.filter(l => l.safeZone).length;
  const pkZones = known.filter(l => Number(l.dangerLevel || 0) >= 3).length;

  let html =
    utils.createStatCard('已知世界地点', data.locationCount || known.length, '固定 15 个，map_locations') +
    utils.createStatCard('已知世界连接', data.connectionCount || 0, '无向边') +
    utils.createStatCard('区域数', (data.regions || []).length, '按 region 分组') +
    utils.createStatCard('安全区', safeCount, '区内禁止私斗');

  // 野外卡片跟着分片走：野外是无限的、懒生成的，节点数没有意义，
  // 有意义的只有"已经生成了多少分片"
  const wildDesc = data.wildernessEnabled === false
    ? '已在 wilderness_map.json 关闭'
    : '分片文件，懒生成';
  html += utils.createStatCard('野外分片', data.wildernessCount || 0, wildDesc);
  html += utils.createStatCard('险地(危险度≥3)', pkZones, 'PK 可掠夺灵石');

  el.innerHTML = html;
}

function renderRegionFilter() {
  const el = document.getElementById('mapRegionFilter');
  if (!el) return;
  let html = `<option value="">全部区域 (${locations.length})</option>`;
  regions.forEach(r => {
    html += `<option value="${utils.escapeHtml(r.name)}"${filterRegion === r.name ? ' selected' : ''}>`
      + `${utils.escapeHtml(r.name)} (${r.count})</option>`;
  });
  el.innerHTML = html;

  const rootSel = document.getElementById('mapTreeRoot');
  if (rootSel) {
    const prev = rootSel.value;
    rootSel.innerHTML = locations
      .map(l => `<option value="${l.id}">(${l.id}) ${utils.escapeHtml(l.name)}${l.wilderness ? ' 🌿' : ''}</option>`)
      .join('');
    rootSel.value = prev && locations.some(l => String(l.id) === prev)
      ? prev
      : String(locations.length ? locations[0].id : '');
  }
}

export function setRegion(value) {
  filterRegion = value || '';
  renderTable();
}

function visibleLocations() {
  const keyword = (document.getElementById('mapSearch')?.value || '').trim().toLowerCase();
  return locations.filter(l => {
    if (filterRegion && l.region !== filterRegion) return false;
    if (!keyword) return true;
    return String(l.name).toLowerCase().includes(keyword)
      || String(l.id) === keyword
      || String(l.region).toLowerCase().includes(keyword);
  });
}

function bonusCell(value) {
  const v = Number(value);
  if (v === 1) return '<span style="color:var(--t4)">×1.00</span>';
  const color = v > 1.2 ? 'var(--gn)' : 'var(--gold)';
  return `<span style="color:${color};font-weight:600">×${v.toFixed(2)}</span>`;
}

function renderTable() {
  const rows = visibleLocations();
  const el = document.getElementById('mapTable');
  if (!el) return;

  if (!rows.length) {
    utils.showEmpty('mapTable', '🗺️', '没有符合条件的地点');
    return;
  }

  let html = '<table><tr>'
    + '<th>ID</th><th>名称</th><th>区域</th><th>境界门槛</th><th>灵气</th><th>地界</th>'
    + '<th>危险度</th><th>安全区</th><th>邻居</th><th>事件偏置</th><th>操作</th></tr>';

  rows.forEach(l => {
    const neighbors = (l.neighbors || [])
      .map(n => `(${n.id})${utils.escapeHtml(n.name)}${n.wilderness ? '🌿' : ''}`)
      .join('、') || '<span style="color:var(--t4)">孤立</span>';

    const biasKeys = Object.keys(l.eventBias || {});
    const bias = biasKeys.length
      ? biasKeys.map(k => utils.createBadge(`${k} ×${Number(l.eventBias[k]).toFixed(1)}`, 'info')).join(' ')
      : '<span style="color:var(--t4)">—</span>';

    // 野外节点：标出来源入口与到期时间，否则管理员无从判断它会不会自己消失
    const nameCell = `<b>${utils.escapeHtml(l.name)}</b>`
      + (l.wilderness ? ' ' + utils.createBadge('🌿 野外', 'info') : '');

    html += '<tr>'
      + `<td>${l.id}</td>`
      + `<td>${nameCell}</td>`
      + `<td>${utils.escapeHtml(l.region)}</td>`
      + `<td>${utils.escapeHtml(l.minRealmName)} <span style="color:var(--t4)">(${l.minRealm})</span></td>`
      + `<td>${bonusCell(l.cultivationBonus)}</td>`
      + `<td>${bonusCell(l.explorationBonus)}</td>`
      + `<td>${dangerBadge(l)}</td>`
      + `<td>${l.safeZone ? utils.createBadge('🛡 安全', 'success') : utils.createBadge('可私斗', 'muted')}</td>`
      + `<td style="max-width:220px">${neighbors}</td>`
      + `<td>${bias}</td>`
      + '<td style="white-space:nowrap">'
      + `<button class="btn btn-action btn-sm" onclick="window.app.modules.map.openEdit(${l.id})">编辑</button> `
      + `<button class="btn btn-action btn-sm" onclick="window.app.modules.map.removeLocation(${l.id})">删除</button>`
      + '</td>'
      + '</tr>';
  });
  html += '</table>';
  el.innerHTML = html;
}

/** 由真实邻接关系生成脉络树，与 /地图 list 的渲染口径一致。 */
export function renderTree() {
  const el = document.getElementById('mapTree');
  if (!el) return;

  const rootId = Number(document.getElementById('mapTreeRoot')?.value || 0);
  const byId = new Map(locations.map(l => [l.id, l]));
  const root = byId.get(rootId) || locations[0];
  if (!root) {
    el.textContent = '（地图数据为空）';
    return;
  }

  const visited = new Set([root.id]);
  const lines = [`(${root.id})${root.name}`];
  walk(root, '', visited, lines, byId);

  const orphans = locations.filter(l => !visited.has(l.id));
  if (orphans.length) {
    lines.push('（未与主干连通） ' + orphans.map(l => `(${l.id})${l.name}`).join(' '));
  }
  el.textContent = lines.join('\n');
}

function walk(node, prefix, visited, lines, byId) {
  const children = (node.neighbors || []).slice().sort((a, b) => a.id - b.id);
  for (let i = 0; i < children.length; i++) {
    const child = byId.get(children[i].id);
    // 图里有环：兄弟子树可能已经展开过它，跳过而不是重复画一遍
    if (!child || visited.has(child.id)) continue;
    // 末尾判断要看"后面还有没有没画过的兄弟"，不能只看下标
    let last = true;
    for (let j = i + 1; j < children.length; j++) {
      if (!visited.has(children[j].id)) { last = false; break; }
    }
    visited.add(child.id);
    lines.push(prefix + (last ? '└─ ' : '├─ ') + `(${child.id})${child.name}`);
    walk(child, prefix + (last ? '   ' : '│  '), visited, lines, byId);
  }
}

// ==================== 新建 / 删除地点 ====================

/** 新建地点。id 留空则由服务端在已知世界范围内分配下一个空位。 */
export function openCreate() {
  const esc = utils.escapeHtml;
  const content = `
    <div class="form-grid">
      <label class="form-row"><span>地点 ID</span>
        <input type="number" id="mapNewId" min="1" placeholder="留空自动分配"></label>
      <label class="form-row"><span>名称</span><input type="text" id="mapNewName" placeholder="例如：落霞谷"></label>
      <label class="form-row"><span>区域</span><input type="text" id="mapNewRegion" placeholder="例如：南荒"></label>
      <label class="form-row"><span>境界门槛</span><input type="number" id="mapNewRealm" min="0" value="0"></label>
      <label class="form-row"><span>危险度</span>
        <select id="mapNewDanger">
          ${DANGER_NAMES.map((n, i) => `<option value="${i}">${i} - ${n}</option>`).join('')}
        </select></label>
      <label class="form-row"><span>安全区（禁私斗）</span>
        <input type="checkbox" id="mapNewSafe"></label>
      <label class="form-row"><span>灵气倍率</span>
        <input type="number" id="mapNewCult" step="0.05" min="0.1" max="100" value="1.0"></label>
      <label class="form-row"><span>地界倍率</span>
        <input type="number" id="mapNewExpl" step="0.05" min="0.1" max="100" value="1.0"></label>
      <label class="form-row" style="align-items:flex-start"><span>描述</span>
        <textarea id="mapNewDesc" rows="3" style="flex:1;min-width:0"></textarea></label>
    </div>
    <div class="hint">新建的地点默认<b>不与任何地点相连</b>，玩家走不到 —— 建完后请用「编辑 → 连接」接线。</div>
    <div class="hint">安全区与危险度不能同时成立：安全区禁止 PK，而危险度是 PK 掠夺的依据。</div>
    <div id="mapNewMsg" style="margin-top:8px"></div>
  `;

  utils.showModal(utils.createModal('新建地点', content, [
    { text: '创建', primary: true, onclick: 'window.app.modules.map.saveNew()' },
    { text: '取消', onclick: 'window.app.utils.closeModal()' }
  ]));
}

export async function saveNew() {
  const idRaw = (document.getElementById('mapNewId').value || '').trim();
  const payload = {
    name: document.getElementById('mapNewName').value,
    region: document.getElementById('mapNewRegion').value,
    description: document.getElementById('mapNewDesc').value,
    minRealm: Number(document.getElementById('mapNewRealm').value),
    dangerLevel: Number(document.getElementById('mapNewDanger').value),
    safeZone: document.getElementById('mapNewSafe').checked,
    cultivationBonus: Number(document.getElementById('mapNewCult').value),
    explorationBonus: Number(document.getElementById('mapNewExpl').value)
  };
  if (idRaw) payload.id = Number(idRaw);

  try {
    const resp = await api.post('/map/locations', payload);
    if (!resp || resp.code !== 200) {
      utils.showMessage('mapNewMsg', 'error', (resp && resp.message) || '创建失败');
      return;
    }
    utils.closeModal();
    await load();
    utils.showMessage('mapMsg', 'success', resp.message || '已创建');
  } catch (e) {
    utils.showMessage('mapNewMsg', 'error', '创建失败: ' + e.message);
  }
}

export async function removeLocation(id) {
  const loc = locations.find(l => l.id === id);
  const label = loc ? `${loc.name}(id=${id})` : `id=${id}`;
  const extra = loc && loc.wilderness
    ? '\n\n注意：这是野外节点，下次野外刷新时本来就会被自动删除。'
    : '\n\n其连接、事件偏置与探索记录会一并删除；站在这里的玩家会被送回出生点。';
  if (!confirm(`确定删除地点 ${label}？此操作不可撤销！${extra}`)) return;

  try {
    const resp = await api.del('/map/locations/' + id);
    if (!resp || resp.code !== 200) {
      utils.showMessage('mapMsg', 'error', (resp && resp.message) || '删除失败');
      return;
    }
    await load();
    utils.showMessage('mapMsg', 'success', resp.message || '已删除');
  } catch (e) {
    utils.showMessage('mapMsg', 'error', '删除失败: ' + e.message);
  }
}

// ==================== 编辑地点 ====================

export function openEdit(id) {
  const loc = locations.find(l => l.id === id);
  if (!loc) return;
  const esc = utils.escapeHtml;
  const danger = Number(loc.dangerLevel || 0);

  const content = `
    <div class="form-grid">
      <label class="form-row"><span>地点 ID</span><span class="muted">${loc.id}（不可改，存档按 id 引用）</span></label>
      <label class="form-row"><span>名称</span><input type="text" id="mapEditName" value="${esc(loc.name)}"></label>
      <label class="form-row"><span>区域</span><input type="text" id="mapEditRegion" value="${esc(loc.region)}"></label>
      <label class="form-row"><span>境界门槛</span><input type="number" id="mapEditRealm" min="0" value="${loc.minRealm}"></label>
      <label class="form-row"><span>危险度</span>
        <select id="mapEditDanger">
          ${DANGER_NAMES.map((n, i) => `<option value="${i}"${i === danger ? ' selected' : ''}>${i} - ${n}</option>`).join('')}
        </select></label>
      <label class="form-row"><span>安全区（禁私斗）</span>
        <input type="checkbox" id="mapEditSafe" ${loc.safeZone ? 'checked' : ''}></label>
      <label class="form-row"><span>灵气倍率</span>
        <input type="number" id="mapEditCult" step="0.05" min="0.1" max="100" value="${loc.cultivationBonus}"></label>
      <label class="form-row"><span>地界倍率</span>
        <input type="number" id="mapEditExpl" step="0.05" min="0.1" max="100" value="${loc.explorationBonus}"></label>
      <label class="form-row" style="align-items:flex-start"><span>描述</span>
        <textarea id="mapEditDesc" rows="3" style="flex:1;min-width:0">${esc(loc.description)}</textarea></label>
    </div>
    <div class="hint">灵气倍率影响闭关修炼收益；地界倍率影响游历与秘境收益。
      危险度决定 PK 掠夺（≥3 才掠夺，比例随危险度递增）与野外资源档位。保存后立即热重载。</div>
    ${loc.wilderness ? `<div class="hint">🌿 这是野外节点（入口：${esc(loc.entryLocationName || loc.entryLocationId || '—')}），
      下次野外刷新时会被自动替换，改动不会长期保留。</div>` : ''}
    <div id="mapEditMsg" style="margin-top:8px"></div>
    ${renderConnectionEditor(loc)}
    ${renderBiasEditor(loc)}
  `;

  utils.showModal(utils.createModal(`编辑地点：${loc.name}`, content, [
    { text: '保存', primary: true, onclick: `window.app.modules.map.save(${loc.id})` },
    { text: '关闭', onclick: 'window.app.utils.closeModal()' }
  ]));
}

function renderConnectionEditor(loc) {
  const esc = utils.escapeHtml;
  const rows = (loc.neighbors || []).map(n => {
    const oneWay = !(locations.find(l => l.id === n.id)?.neighbors || []).some(x => x.id === loc.id);
    return '<tr>'
      + `<td>(${n.id}) ${esc(n.name)}${n.wilderness ? ' 🌿' : ''}</td>`
      + `<td>${n.travelSeconds}s</td>`
      + `<td>${oneWay ? utils.createBadge('单向 ⚠️', 'warning') : utils.createBadge('双向', 'success')}</td>`
      + `<td><button class="btn btn-action btn-sm"`
      + ` onclick="window.app.modules.map.removeConnection(${loc.id}, ${n.id})">删除</button></td>`
      + '</tr>';
  }).join('');

  const options = locations
    .filter(l => l.id !== loc.id && !(loc.neighbors || []).some(n => n.id === l.id))
    .map(l => `<option value="${l.id}">(${l.id}) ${esc(l.name)}${l.wilderness ? ' 🌿' : ''}</option>`)
    .join('');

  return `
    <div class="card-title" style="margin-top:16px">🔗 连接（${(loc.neighbors || []).length}）</div>
    <div class="table-wrap">${rows
      ? `<table><tr><th>目标</th><th>路程</th><th>方向</th><th>操作</th></tr>${rows}</table>`
      : '<div class="hint">此地点不与任何地点相连 —— 玩家走不到这里。</div>'}</div>
    <div class="row" style="flex-wrap:wrap;gap:8px;margin-top:8px">
      <select class="grow" id="mapConnTarget">${options || '<option value="">（没有可连的地点）</option>'}</select>
      <input type="number" id="mapConnSeconds" min="1" max="86400" value="5" style="width:90px" title="路程秒数">
      <label style="display:flex;align-items:center;gap:4px;font-size:12px">
        <input type="checkbox" id="mapConnOneWay">单向</label>
      <button class="btn btn-action" onclick="window.app.modules.map.addConnection(${loc.id})">➕ 添加连接</button>
    </div>
    <div class="hint">默认建立双向连接（只加单向会让玩家"去了回不来"）。</div>
  `;
}

function renderBiasEditor(loc) {
  const esc = utils.escapeHtml;
  const keys = Object.keys(loc.eventBias || {});
  const rows = keys.map(k => '<tr>'
    + `<td>${esc(k)}</td>`
    + `<td>×${Number(loc.eventBias[k]).toFixed(2)}</td>`
    + `<td><button class="btn btn-action btn-sm"`
    + ` onclick="window.app.modules.map.removeBias(${loc.id}, '${esc(k)}')">删除</button></td>`
    + '</tr>').join('');

  return `
    <div class="card-title" style="margin-top:16px">🎲 事件权重偏置（${keys.length}）</div>
    <div class="table-wrap">${rows
      ? `<table><tr><th>事件 key</th><th>倍率</th><th>操作</th></tr>${rows}</table>`
      : '<div class="hint">无偏置，该地点的地界事件按默认权重抽取。</div>'}</div>
    <div class="row" style="flex-wrap:wrap;gap:8px;margin-top:8px">
      <input class="grow" id="mapBiasKey" placeholder="事件 key，例如 monster_encounter">
      <input type="number" id="mapBiasMultiplier" step="0.1" min="0.1" max="100" value="1.5" style="width:90px">
      <button class="btn btn-action" onclick="window.app.modules.map.addBias(${loc.id})">➕ 保存偏置</button>
    </div>
    <div class="hint">倍率 &gt;1 提高该事件在此地出现的权重，&lt;1 降低。</div>
  `;
}

export async function save(id) {
  const payload = {
    name: document.getElementById('mapEditName').value,
    region: document.getElementById('mapEditRegion').value,
    description: document.getElementById('mapEditDesc').value,
    minRealm: Number(document.getElementById('mapEditRealm').value),
    dangerLevel: Number(document.getElementById('mapEditDanger').value),
    safeZone: document.getElementById('mapEditSafe').checked,
    cultivationBonus: Number(document.getElementById('mapEditCult').value),
    explorationBonus: Number(document.getElementById('mapEditExpl').value)
  };

  try {
    const resp = await api.post('/map/locations/' + id, payload);
    if (!resp || resp.code !== 200) {
      utils.showMessage('mapEditMsg', 'error', (resp && resp.message) || '保存失败');
      return;
    }
    utils.closeModal();
    await load();
    utils.showMessage('mapMsg', 'success', resp.message || '已保存');
  } catch (e) {
    utils.showMessage('mapEditMsg', 'error', '保存失败: ' + e.message);
  }
}

// ==================== 连接 / 偏置 ====================

export async function addConnection(id) {
  const target = document.getElementById('mapConnTarget')?.value;
  if (!target) {
    utils.showMessage('mapEditMsg', 'error', '请选择要连接的目标地点');
    return;
  }
  const payload = {
    from: id,
    to: Number(target),
    travelSeconds: Number(document.getElementById('mapConnSeconds').value),
    oneWay: document.getElementById('mapConnOneWay').checked
  };
  await postAndReopen('/map/connections', payload, id);
}

export async function removeConnection(from, to) {
  if (!confirm(`确定删除连接 ${from} → ${to}？双向连接会一并删除反向边。`)) return;
  try {
    const resp = await api.del(`/map/connections?from=${from}&to=${to}&bothWays=true`);
    if (!resp || resp.code !== 200) {
      utils.showMessage('mapMsg', 'error', (resp && resp.message) || '删除连接失败');
      return;
    }
    await load();
    openEdit(from);
    utils.showMessage('mapEditMsg', 'success', resp.message || '已删除连接');
  } catch (e) {
    utils.showMessage('mapMsg', 'error', '删除连接失败: ' + e.message);
  }
}

export async function addBias(id) {
  const eventKey = (document.getElementById('mapBiasKey')?.value || '').trim();
  if (!eventKey) {
    utils.showMessage('mapEditMsg', 'error', '事件 key 不能为空');
    return;
  }
  const payload = {
    locationId: id,
    eventKey,
    multiplier: Number(document.getElementById('mapBiasMultiplier').value)
  };
  await postAndReopen('/map/event-bias', payload, id);
}

export async function removeBias(locationId, eventKey) {
  if (!confirm(`确定删除事件偏置 ${eventKey}？`)) return;
  try {
    const resp = await api.del(`/map/event-bias?locationId=${locationId}`
      + `&eventKey=${encodeURIComponent(eventKey)}`);
    if (!resp || resp.code !== 200) {
      utils.showMessage('mapMsg', 'error', (resp && resp.message) || '删除偏置失败');
      return;
    }
    await load();
    openEdit(locationId);
    utils.showMessage('mapEditMsg', 'success', resp.message || '已删除偏置');
  } catch (e) {
    utils.showMessage('mapMsg', 'error', '删除偏置失败: ' + e.message);
  }
}

/** 提交后重新打开同一个地点的编辑框，让管理员接着改下一项，而不是被弹回列表。 */
async function postAndReopen(path, payload, reopenId) {
  try {
    const resp = await api.post(path, payload);
    if (!resp || resp.code !== 200) {
      utils.showMessage('mapEditMsg', 'error', (resp && resp.message) || '保存失败');
      return;
    }
    await load();
    openEdit(reopenId);
    utils.showMessage('mapEditMsg', 'success', resp.message || '已保存');
  } catch (e) {
    utils.showMessage('mapEditMsg', 'error', '保存失败: ' + e.message);
  }
}

// ==================== 野外 ====================

/**
 * 给野外原地补货：只把采空的资源补回上限。
 *
 * 野外是无限的、已探索的内容永久保留，所以这里刻意没有"刷新/重建"入口 ——
 * 那会把玩家走过的地换掉。管理台能做的只是让矿重新长出来。
 */
export async function restockWilderness() {
  if (!confirm('确定给全部已生成的野外分片补货？只会把采空的资源补回上限，地形与已探索记录均不变。')) return;
  try {
    const resp = await api.post('/map/wilderness/restock', {});
    if (!resp || resp.code !== 200) {
      utils.showMessage('mapMsg', 'error', (resp && resp.message) || '补货失败');
      return;
    }
    await load();
    utils.showMessage('mapMsg', 'success', resp.message || '野外资源已补货');
  } catch (e) {
    utils.showMessage('mapMsg', 'error', '野外补货失败: ' + e.message);
  }
}

/** 加载野外分片总览（野外存的是分片文件，不是数据库表，因此单独一个只读视图）。 */
export async function loadWilderness() {
  try {
    const resp = await api.get('/map/wilderness');
    if (!resp || resp.code !== 200) return;
    renderWilderness(resp.data || {});
  } catch (e) {
    // 野外是可选功能，读不到就当没启用，不打扰主流程
  }
}

function renderWilderness(data) {
  const box = document.getElementById('wildernessBox');
  if (!box) return;

  const stats = [
    ['分片数', data.chunkCount],
    ['已固化', data.pinnedChunks],
    ['已探索节点', data.exploredNodes],
    ['资源条目', data.resourceEntries],
    ['分片边长', data.chunkSize],
    ['硬上限(片)', data.maxChunksFromOrigin],
  ];
  let html = '<div class="wilderness-stats">';
  for (const [label, value] of stats) {
    html += `<span class="stat"><b>${utils.escapeHtml(String(value ?? '-'))}</b>${utils.escapeHtml(label)}</span>`;
  }
  html += '</div>';

  const chunks = Array.isArray(data.chunks) ? data.chunks : [];
  if (chunks.length === 0) {
    html += '<p class="hint">还没有任何分片落盘 —— 野外是懒生成的，有玩家踏入才会产生分片文件。</p>';
  } else {
    html += '<table class="table"><thead><tr>'
      + '<th>分片</th><th>地形</th><th>危险度</th><th>已探索</th><th>探索者</th><th>资源</th><th>状态</th>'
      + '</tr></thead><tbody>';
    for (const c of chunks) {
      const status = c.gateway ? '🚪 出入口' : (c.pinned ? '📌 已固化' : '○ 未涉足');
      html += `<tr><td>(${c.chunkX},${c.chunkY})</td>`
        + `<td>${utils.escapeHtml(c.terrain || '-')}</td>`
        + `<td>${utils.escapeHtml(c.dangerName || '-')}</td>`
        + `<td>${c.exploredNodes}</td><td>${c.explorers}</td><td>${c.resourceEntries}</td>`
        + `<td>${status}</td></tr>`;
    }
    html += '</tbody></table>';
  }
  box.innerHTML = html;
}

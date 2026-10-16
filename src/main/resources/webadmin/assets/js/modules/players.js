import * as api from '../api.js';
import * as utils from '../utils.js';

// 选项列表缓存（带 60 秒 TTL，避免每次打开详情都重复请求）
const OPTION_CACHE = new Map();
const CACHE_TTL = 60 * 1000;

async function cachedOptions(key, fetcher) {
  const hit = OPTION_CACHE.get(key);
  if (hit && Date.now() - hit.time < CACHE_TTL) return hit.value;
  const value = await fetcher();
  OPTION_CACHE.set(key, { time: Date.now(), value });
  return value;
}

export async function load() {
  const query = document.getElementById('playerSearch').value.trim();
  let url = '/players?limit=50';
  if (query) {
    url += '&name=' + encodeURIComponent(query);
  }
  
  try {
    const data = await api.get(url);
    
    if (!data.players || data.players.length === 0) {
      document.getElementById('playerTable').innerHTML = utils.createEmptyState('☯', '暂无修士记录');
      return;
    }
    
    let html = '<table><tr><th>ID</th><th>道号</th><th>境界</th><th>灵根</th><th>等级</th><th>生命</th><th>攻击</th><th>防御</th><th>金币</th><th>灵石</th><th>状态</th><th>操作</th></tr>';
    
    for (const player of data.players) {
      const cultivating = player.cultivating 
        ? '<span class="badge badge-gold">闭关中</span>' 
        : '<span class="badge badge-jade">游历中</span>';
      
      html += `
        <tr>
          <td style="color:var(--text-muted);font-size:11px">${player.id}</td>
          <td style="color:var(--gold)">${utils.escapeHtml(player.name)}</td>
          <td>${utils.escapeHtml(player.realm)}</td>
          <td>${utils.escapeHtml(player.spiritualRoot || '')} <span style="color:var(--text-muted);font-size:11px">${utils.escapeHtml(player.rootTier || '')}</span></td>
          <td>${player.level}</td>
          <td>${player.hp}/${player.maxHp}</td>
          <td>${player.attack}</td>
          <td>${player.defense}</td>
          <td>${player.gold}</td>
          <td>${player.spiritStones || 0}</td>
          <td>${cultivating}</td>
          <td><button class="btn btn-action btn-sm" onclick="window.app.modules.players.openDetail(${player.id}, '${utils.escapeHtml(player.name)}')">详情</button></td>
        </tr>
      `;
    }
    
    html += `</table><p style="color:var(--text-muted);font-size:12px;margin-top:10px">共 ${data.total || 0} 位修士</p>`;
    document.getElementById('playerTable').innerHTML = html;
  } catch (error) {
    utils.showEmpty('playerTable', '☯', '加载失败');
  }
}

export async function openDetail(id, name) {
  try {
    const data = await api.get('/players/' + id);
    const player = data.player;
    
    if (!player) {
      alert('玩家不存在');
      return;
    }
    
    let itemsHtml = '<table><tr><th>物品</th><th>标识</th><th>数量</th></tr>';
    if (player.items) {
      for (const item of player.items) {
        itemsHtml += `
          <tr>
            <td>${utils.escapeHtml(item.name)}</td>
            <td style="color:var(--text-muted);font-size:11px">${item.key}</td>
            <td>${item.qty}</td>
          </tr>
        `;
      }
    }
    itemsHtml += '</table>';
    
    const html = `
      <button class="close" onclick="window.app.utils.closeModal()">&times;</button>
      <h3>☯ ${utils.escapeHtml(player.name)} <span style="font-size:12px;color:var(--text-muted);font-weight:normal">ID:${player.id} · UID:${player.userId}</span></h3>
      
      <div class="section-title">✏️ 修改名称</div>
      <div class="row">
        <input id="edName" placeholder="新名称" value="${utils.escapeHtml(player.name)}" style="flex:1;margin-right:8px">
        <button class="btn btn-action" onclick="window.app.modules.players.rename(${id})">修改名称</button>
      </div>
      
      <div class="form-grid">
        <div><label>生命值</label><input id="edHp" value="${player.hp}"></div>
        <div><label>最大生命</label><input id="edMaxHp" value="${player.maxHp}"></div>
        <div><label>法力值</label><input id="edMp" value="${player.mp}"></div>
        <div><label>最大法力</label><input id="edMaxMp" value="${player.maxMp}"></div>
        <div><label>攻击力</label><input id="edAtk" value="${player.attack}"></div>
        <div><label>防御力</label><input id="edDef" value="${player.defense}"></div>
        <div><label>速度</label><input id="edSpd" value="${player.speed}"></div>
        <div><label>神识</label><input id="edSpi" value="${player.spirit}"></div>
        <div><label>等级</label><input id="edLvl" value="${player.level}"></div>
        <div><label>金币</label><input id="edGold" value="${player.gold}"></div>
        <div><label>灵力</label><input id="edExp" value="${player.experience}"></div>
        <div><label>境界</label><select id="edRealm"></select></div>
      </div>
      
      <div style="margin-top:16px;padding:12px;background:rgba(212,168,83,0.05);border-radius:8px;font-size:13px;color:var(--text-secondary)">
        灵根: <b style="color:var(--gold)">${utils.escapeHtml(player.spiritualRoot || '—')}</b>（${utils.escapeHtml(player.rootTier || '')}）
        &nbsp;|&nbsp; 灵石: <b style="color:var(--jade)">${player.spiritStones || 0}</b>
        &nbsp;|&nbsp; ${player.cultivating ? '<span class="badge badge-gold">闭关中</span>' : '<span class="badge badge-jade">游历中</span>'}
      </div>
      
      <div class="section-title" style="margin-top:20px">🪨 修改灵根</div>
      <div class="row">
        <select id="rootSel" style="width:200px"><option value="">--选择灵根--</option></select>
        <button class="btn btn-action" onclick="window.app.modules.players.setSpiritualRoot(${id})">修改灵根</button>
        <small style="color:var(--text-muted)">修改灵根会重新计算基础属性</small>
      </div>
      
      <div class="actions">
        <button class="btn btn-primary" onclick="window.app.modules.players.save(${id})">保存修改</button>
      </div>
      
      <div class="section-title">🎁 发放资源</div>
      <div class="row">
        <input placeholder="金币" id="gvGold" style="width:80px">
        <input placeholder="灵力" id="gvExp" style="width:80px">
        <input placeholder="灵石" id="gvSS" style="width:80px">
        <select id="gvItem"><option value="">--选物品--</option></select>
        <input placeholder="数量" id="gvQty" style="width:60px">
        <button class="btn btn-action" onclick="window.app.modules.players.give(${id})">发放</button>
      </div>
      
      <div class="section-title">🎒 背包物品</div>
      ${itemsHtml}
      <div id="playerActionMsg"></div>
    `;
    
    utils.showModal(html);
    
    loadItemOptions();
    loadRootOptions();
    loadRealmOptions(player);
  } catch {
    alert('加载失败');
  }
}

async function loadItemOptions() {
  try {
    const items = await cachedOptions('items', () => api.get('/items'));
    const sel = document.getElementById('gvItem');
    sel.innerHTML = '<option value="">--选物品--</option>';
    for (const item of items.items) {
      sel.innerHTML += `<option value="${utils.escapeHtml(item.key)}">${utils.escapeHtml(item.name)}</option>`;
    }
  } catch {}
}

async function loadRootOptions() {
  try {
    const roots = await cachedOptions('roots', () => api.get('/spiritual-roots'));
    const sel = document.getElementById('rootSel');
    let lastTier = '';
    for (const root of roots.roots) {
      const tier = root.tierKey;
      if (tier !== lastTier) {
        if (lastTier !== '') sel.innerHTML += '<option disabled>──────────</option>';
        lastTier = tier;
      }
      sel.innerHTML += `<option value="${utils.escapeHtml(root.name)}">${utils.escapeHtml(root.displayName)} [${utils.escapeHtml(root.tier)}]</option>`;
    }
  } catch {}
}

async function loadRealmOptions(player) {
  try {
    const realms = await cachedOptions('realms', () => api.get('/realms'));
    const sel = document.getElementById('edRealm');
    let lastId = -1;
    for (const realm of realms.realms) {
      if (realm.id !== lastId) {
        if (lastId >= 0) sel.innerHTML += '<option disabled>──────────</option>';
        lastId = realm.id;
      }
      const selected = (player.realm === realm.id && player.subRealm === realm.subRealm) ? ' selected' : '';
      sel.innerHTML += `<option value="${realm.id}-${realm.subRealm}"${selected}>${utils.escapeHtml(realm.name)}</option>`;
    }
  } catch {}
}

export async function save(id) {
  const g = (id) => document.getElementById(id).value;
  const realmVal = g('edRealm');
  const realmParts = realmVal.split('-');
  
  const body = {
    hp: parseInt(g('edHp')),
    maxHp: parseInt(g('edMaxHp')),
    mp: parseInt(g('edMp')),
    maxMp: parseInt(g('edMaxMp')),
    attack: parseInt(g('edAtk')),
    defense: parseInt(g('edDef')),
    speed: parseInt(g('edSpd')),
    spirit: parseInt(g('edSpi')),
    level: parseInt(g('edLvl')),
    gold: parseInt(g('edGold')),
    experience: parseInt(g('edExp')),
    realm: parseInt(realmParts[0]),
    subRealm: parseInt(realmParts[1])
  };
  
  try {
    const result = await api.post('/players/' + id + '/edit', body);
    utils.showMessage('playerActionMsg', 'ok', result.message);
    load();
  } catch {
    utils.showMessage('playerActionMsg', 'err', '修改失败');
  }
}

export async function rename(id) {
  const newName = document.getElementById('edName').value.trim();
  if (!newName) {
    alert('名称不能为空');
    return;
  }
  
  try {
    const result = await api.post('/players/' + id + '/rename', { name: newName });
    utils.showMessage('playerActionMsg', 'ok', result.message);
    load();
  } catch {
    utils.showMessage('playerActionMsg', 'err', '修改失败');
  }
}

export async function give(id) {
  const body = {};
  const gold = document.getElementById('gvGold').value;
  const exp = document.getElementById('gvExp').value;
  const ss = document.getElementById('gvSS').value;
  const itemKey = document.getElementById('gvItem').value;
  const itemQty = document.getElementById('gvQty').value;
  
  if (gold) body.gold = parseInt(gold);
  if (exp) body.exp = parseInt(exp);
  if (ss) body.spiritStones = parseInt(ss);
  if (itemKey && itemQty) {
    body.itemKey = itemKey;
    body.itemQty = parseInt(itemQty);
  }
  
  try {
    const result = await api.post('/players/' + id + '/give', body);
    utils.showMessage('playerActionMsg', 'ok', result.message);
    load();
  } catch {
    utils.showMessage('playerActionMsg', 'err', '发放失败');
  }
}

export async function setSpiritualRoot(id) {
  const root = document.getElementById('rootSel').value;
  if (!root) {
    alert('请选择灵根');
    return;
  }
  if (!confirm('确定修改灵根？这会重新计算该玩家的基础属性。')) return;
  
  try {
    const result = await api.post('/players/' + id + '/spiritual-root', { spiritualRoot: root });
    utils.showMessage('playerActionMsg', 'ok', result.message);
    load();
    utils.closeModal();
    setTimeout(() => openDetail(id), 500);
  } catch {
    utils.showMessage('playerActionMsg', 'err', '修改灵根失败');
  }
}
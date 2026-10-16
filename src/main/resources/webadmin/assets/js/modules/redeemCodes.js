import * as api from '../api.js';
import * as utils from '../utils.js';

let _itemOptionsHtml = null;

async function loadItemOptions() {
  if (_itemOptionsHtml) return _itemOptionsHtml;
  
  try {
    const data = await api.get('/items');
    let html = '<option value="">-- 选择物品 --</option>';
    if (data.items) {
      for (const item of data.items) {
        html += `<option value="${item.key}">${utils.escapeHtml(item.name)} (${utils.escapeHtml(item.key)})</option>`;
      }
    }
    _itemOptionsHtml = html;
    return html;
  } catch {
    return '<option value="">-- 加载失败 --</option>';
  }
}

function statusBadge(status) {
  switch (status) {
    case 'active': return '<span class="badge badge-jade">生效</span>';
    case 'redeemed': return '<span class="badge badge-gold">已兑换</span>';
    case 'expired': return '<span class="badge badge-gray">已过期</span>';
    default: return '<span class="badge badge-gray">已失效</span>';
  }
}

export async function load() {
  try {
    const data = await api.get('/redeem-codes');
    
    if (!data.codes || data.codes.length === 0) {
      document.getElementById('redeemTable').innerHTML = utils.createEmptyState('🎫', '暂无兑换码');
      return;
    }
    
    let html = '<table><tr><th>ID</th><th>兑换码</th><th>名称</th><th>金币</th><th>灵石</th><th>灵力</th><th>物品</th><th>使用/上限</th><th>状态</th><th>过期</th><th>创建者</th><th>操作</th></tr>';
    
    for (const code of data.codes) {
      let itemsStr = '';
      if (code.items) {
        itemsStr = Object.entries(code.items).map(([k, v]) => utils.escapeHtml(k) + 'x' + v).join(', ');
      }
      
      const canEdit = code.status !== 'redeemed' && code.status !== 'expired';
      
      html += `
        <tr>
          <td style="color:var(--text-muted);font-size:11px">${code.id}</td>
          <td style="color:var(--gold);font-family:monospace">${utils.escapeHtml(code.code)}</td>
          <td>${utils.escapeHtml(code.name || '')}</td>
          <td>${code.gold || 0}</td>
          <td>${code.spiritStones || 0}</td>
          <td>${code.exp || 0}</td>
          <td style="max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${utils.escapeHtml(itemsStr)}">${itemsStr || '—'}</td>
          <td>${code.currentUses || 0}/${code.maxUses < 0 ? '∞' : code.maxUses}</td>
          <td>${statusBadge(code.status)}</td>
          <td style="font-size:11px">${code.expiresAt ? utils.escapeHtml(code.expiresAt.substring(0, 10)) : '永不过期'}</td>
          <td>${utils.escapeHtml(code.createdBy || '')}</td>
          <td>
            ${canEdit ? `<button class="btn btn-action btn-sm" onclick="window.app.modules.redeemCodes.openEdit(${code.id})">编辑</button> ` : ''}
            <button class="btn btn-danger btn-sm" onclick="window.app.modules.redeemCodes.deleteCode(${code.id}, '${utils.escapeHtml(code.code)}')">删除</button>
          </td>
        </tr>
      `;
    }
    
    html += '</table>';
    document.getElementById('redeemTable').innerHTML = html;
  } catch (error) {
    utils.showEmpty('redeemTable', '🎫', '加载失败: ' + (error.message || ''));
  }
}

export function openCreate() {
  const html = `
    <button class="close" onclick="window.app.utils.closeModal()">&times;</button>
    <h3>🎫 创建兑换码</h3>
    <div style="display:grid;grid-template-columns:120px 1fr;gap:10px;align-items:center">
      <label>兑换码 *</label><input id="rcCode" placeholder="如: XINCHUN2025" style="text-transform:uppercase">
      <label>名称</label><input id="rcName" placeholder="如: 新春礼包">
      <label>金币</label><input id="rcGold" type="number" value="0">
      <label>灵石</label><input id="rcSS" type="number" value="0">
      <label>灵力</label><input id="rcExp" type="number" value="0">
      <label>最大使用次数</label><input id="rcMaxUses" type="number" value="1">
      <label>过期时间</label><input id="rcExpires" type="datetime-local">
      <label>物品奖励</label>
      <div id="rcItems">
        <div class="row" style="margin-bottom:4px">
          <select id="rcItemKey0" style="flex:1"><option value="">加载中...</option></select>
          <input id="rcItemQty0" type="number" placeholder="数量" style="width:80px" value="1">
        </div>
      </div>
      <label></label><button class="btn-sm" onclick="window.app.modules.redeemCodes.addItem()">+ 添加物品</button>
    </div>
    <div class="actions">
      <button class="btn btn-primary" onclick="window.app.modules.redeemCodes.create()">创建</button>
    </div>
  `;
  
  utils.showModal(html);
  window._rcItemIdx = 1;
  window._fillMax = 5;
  fillItemSelects('rcItemKey');
}

function fillItemSelects(prefix) {
  loadItemOptions().then(options => {
    const max = window._fillMax || 10;
    for (let i = 0; i < max; i++) {
      const sel = document.getElementById(prefix + i);
      if (sel) {
        const saved = sel.getAttribute('data-sel');
        sel.innerHTML = options;
        if (saved) sel.value = saved;
      }
    }
  });
}

export function addItem() {
  const idx = window._rcItemIdx || 1;
  window._fillMax = Math.max(window._fillMax || 5, idx + 1);
  
  const div = document.createElement('div');
  div.className = 'row';
  div.style.marginBottom = '4px';
  div.innerHTML = `
    <select id="rcItemKey${idx}" style="flex:1"><option value="">加载中...</option></select>
    <input id="rcItemQty${idx}" type="number" placeholder="数量" style="width:80px" value="1">
  `;
  
  document.getElementById('rcItems').appendChild(div);
  window._rcItemIdx = idx + 1;
  fillItemSelects('rcItemKey');
}

function collectItems(prefix) {
  const items = {};
  const max = window._rcItemIdx || 5;
  for (let i = 0; i < max; i++) {
    const keyEl = document.getElementById(prefix + 'Key' + i);
    const qtyEl = document.getElementById(prefix + 'Qty' + i);
    if (keyEl && keyEl.value.trim() && qtyEl && parseInt(qtyEl.value) > 0) {
      items[keyEl.value.trim()] = parseInt(qtyEl.value);
    }
  }
  return items;
}

export async function create() {
  const code = document.getElementById('rcCode').value.trim().toUpperCase();
  if (!code) {
    alert('请输入兑换码');
    return;
  }
  
  const name = document.getElementById('rcName').value.trim();
  const gold = parseInt(document.getElementById('rcGold').value) || 0;
  const ss = parseInt(document.getElementById('rcSS').value) || 0;
  const exp = parseInt(document.getElementById('rcExp').value) || 0;
  const maxUses = parseInt(document.getElementById('rcMaxUses').value) || 1;
  const expiresEl = document.getElementById('rcExpires');
  const expiresAt = expiresEl && expiresEl.value ? expiresEl.value.replace('T', ' ') + ':00' : null;
  const items = collectItems('rcItem');
  
  try {
    const result = await api.post('/redeem-codes', {
      code, name, gold, spiritStones: ss, exp, maxUses, expiresAt, items, createdBy: 'admin'
    });
    
    if (result.code === 200) {
      utils.showMessage('redeemMsg', 'ok', '兑换码 <b>' + utils.escapeHtml(code) + '</b> 创建成功！');
      utils.closeModal();
      load();
    } else {
      alert('创建失败: ' + (result.message || ''));
    }
  } catch (error) {
    alert('创建失败: ' + (error.message || ''));
  }
}

export async function openEdit(id) {
  try {
    const data = await api.get('/redeem-codes');
    const code = (data.codes || []).find(c => c.id === id);
    
    if (!code) {
      alert('兑换码不存在');
      return;
    }
    
    let itemsHtml = '';
    let itemIdx = 0;
    
    if (code.items) {
      for (const [key, qty] of Object.entries(code.items)) {
        itemsHtml += `
          <div class="row" style="margin-bottom:4px">
            <select id="editItemKey${itemIdx}" style="flex:1" data-sel="${utils.escapeHtml(key)}"><option value="">加载中...</option></select>
            <input id="editItemQty${itemIdx}" type="number" value="${qty}" style="width:80px">
          </div>
        `;
        itemIdx++;
      }
    }
    
    itemsHtml += `
      <div class="row" style="margin-bottom:4px">
        <select id="editItemKey${itemIdx}" style="flex:1"><option value="">加载中...</option></select>
        <input id="editItemQty${itemIdx}" type="number" placeholder="数量" style="width:80px">
      </div>
    `;
    window._editItemIdx = itemIdx + 1;
    
    const expiresVal = code.expiresAt ? code.expiresAt.substring(0, 16) : '';
    
    const html = `
      <button class="close" onclick="window.app.utils.closeModal()">&times;</button>
      <h3>✏️ 编辑兑换码 · ${utils.escapeHtml(code.code)} <span style="font-size:12px;color:var(--text-muted);font-weight:normal">ID:${id}</span></h3>
      <div style="display:grid;grid-template-columns:120px 1fr;gap:10px;align-items:center">
        <label>名称</label><input id="editRcName" value="${utils.escapeHtml(code.name || '')}">
        <label>金币</label><input id="editRcGold" type="number" value="${code.gold || 0}">
        <label>灵石</label><input id="editRcSS" type="number" value="${code.spiritStones || 0}">
        <label>灵力</label><input id="editRcExp" type="number" value="${code.exp || 0}">
        <label>最大使用次数</label><input id="editRcMaxUses" type="number" value="${code.maxUses || 1}">
        <label>过期时间</label><input id="editRcExpires" type="datetime-local" value="${expiresVal}">
        <label>状态</label><span style="padding:8px 0;color:var(--text-primary)">${statusBadge(code.status)}</span>
        <label>物品奖励</label>
        <div id="editRcItems">${itemsHtml}</div>
        <label></label><button class="btn-sm" onclick="window.app.modules.redeemCodes.addEditItem()">+ 添加物品</button>
      </div>
      <div class="actions">
        <button class="btn btn-primary" onclick="window.app.modules.redeemCodes.saveEdit(${id})">保存修改</button>
      </div>
    `;
    
    utils.showModal(html);
    window._fillMax = window._editItemIdx || 5;
    fillItemSelects('editItemKey');
  } catch (error) {
    alert('加载失败: ' + (error.message || ''));
  }
}

export function addEditItem() {
  const idx = window._editItemIdx || 1;
  window._fillMax = Math.max(window._fillMax || 5, idx + 1);
  
  const div = document.createElement('div');
  div.className = 'row';
  div.style.marginBottom = '4px';
  div.innerHTML = `
    <select id="editItemKey${idx}" style="flex:1"><option value="">加载中...</option></select>
    <input id="editItemQty${idx}" type="number" placeholder="数量" style="width:80px">
  `;
  
  document.getElementById('editRcItems').appendChild(div);
  window._editItemIdx = idx + 1;
  fillItemSelects('editItemKey');
}

export async function saveEdit(id) {
  const name = document.getElementById('editRcName').value.trim();
  const gold = parseInt(document.getElementById('editRcGold').value) || 0;
  const ss = parseInt(document.getElementById('editRcSS').value) || 0;
  const exp = parseInt(document.getElementById('editRcExp').value) || 0;
  const maxUses = parseInt(document.getElementById('editRcMaxUses').value) || 1;
  const expiresEl = document.getElementById('editRcExpires');
  const expiresAt = expiresEl && expiresEl.value ? expiresEl.value.replace('T', ' ') + ':00' : null;
  const items = collectItems('editItem');
  
  try {
    const result = await api.post('/redeem-codes/' + id, {
      name, gold, spiritStones: ss, exp, maxUses, expiresAt, items
    });
    
    if (result.code === 200) {
      utils.showMessage('redeemMsg', 'ok', '更新成功');
      utils.closeModal();
      load();
    } else {
      alert('更新失败: ' + (result.message || ''));
    }
  } catch (error) {
    alert('更新失败: ' + (error.message || ''));
  }
}

export async function deleteCode(id, code) {
  if (!confirm('确定删除兑换码【' + code + '】吗？此操作不可撤销！')) return;
  
  try {
    const result = await api.del('/redeem-codes/' + id);
    
    if (result.code === 200) {
      utils.showMessage('redeemMsg', 'ok', '兑换码已删除');
      load();
    } else {
      alert('删除失败: ' + (result.message || ''));
    }
  } catch (error) {
    alert('删除失败: ' + (error.message || ''));
  }
}
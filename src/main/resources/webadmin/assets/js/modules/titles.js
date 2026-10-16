import * as api from '../api.js';
import * as utils from '../utils.js';

const RARITY_COLORS = {
  COMMON: 'color:var(--text-muted)',
  UNCOMMON: 'color:var(--jade)',
  RARE: 'color:var(--info)',
  EPIC: 'color:var(--gold)',
  LEGENDARY: 'color:#f59e0b',
  MYTHIC: 'color:#ef4444'
};

const RARITY_NAMES = {
  COMMON: '凡品',
  UNCOMMON: '良品',
  RARE: '珍品',
  EPIC: '仙品',
  LEGENDARY: '神品',
  MYTHIC: '圣品'
};

export async function load() {
  const query = document.getElementById('titleSearch').value.trim();
  
  try {
    const data = await api.get('/titles');
    let titles = data.titles || [];
    
    if (query) {
      titles = titles.filter(t => t.name && t.name.includes(query));
    }
    
    if (titles.length === 0) {
      document.getElementById('titleTable').innerHTML = utils.createEmptyState('🏆', '暂无称号');
      return;
    }
    
    let html = '<table><tr><th>Key</th><th>名称</th><th>稀有度</th><th>所需境界</th><th>属性加成</th><th>描述</th></tr>';
    
    for (const title of titles) {
      const bonuses = [];
      if (title.attackBonus > 0) bonuses.push('攻击+' + title.attackBonus);
      if (title.defenseBonus > 0) bonuses.push('防御+' + title.defenseBonus);
      if (title.speedBonus > 0) bonuses.push('速度+' + title.speedBonus);
      if (title.spiritBonus > 0) bonuses.push('神识+' + title.spiritBonus);
      if (title.hpBonus > 0) bonuses.push('生命+' + title.hpBonus);
      if (title.mpBonus > 0) bonuses.push('法力+' + title.mpBonus);
      if (title.experienceBonus > 0) bonuses.push('经验+' + title.experienceBonus + '%');
      if (title.dropRateBonus > 0) bonuses.push('掉落+' + title.dropRateBonus + '%');
      if (title.cultivationSpeedBonus > 0) bonuses.push('修炼+' + title.cultivationSpeedBonus + '%');
      
      html += `
        <tr>
          <td style="color:var(--text-muted);font-size:11px;font-family:monospace">${utils.escapeHtml(title.key)}</td>
          <td style="color:var(--gold)">${utils.escapeHtml(title.name)}</td>
          <td style="${RARITY_COLORS[title.rarity] || 'color:var(--text-muted)'}">${RARITY_NAMES[title.rarity] || title.rarity}</td>
          <td>${title.requiredRealm}</td>
          <td style="font-size:11px;color:var(--text-secondary)">${bonuses.join(', ') || '无'}</td>
          <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${utils.escapeHtml(title.description || '')}">${utils.escapeHtml(title.description || '-')}</td>
        </tr>
      `;
    }
    
    html += '</table>';
    document.getElementById('titleTable').innerHTML = html;
    
    const sel = document.getElementById('titleGiveSel');
    sel.innerHTML = '<option value="">--选择称号--</option>';
    for (const title of titles) {
      sel.innerHTML += `<option value="${utils.escapeHtml(title.key)}">${utils.escapeHtml(title.name)}</option>`;
    }
  } catch {
    utils.showEmpty('titleTable', '🏆', '加载失败');
  }
}

export async function loadPlayerList() {
  const query = document.getElementById('titlePlayerSearch').value.trim();
  let url = '/players?limit=20';
  if (query) url += '&name=' + encodeURIComponent(query);
  
  try {
    const data = await api.get(url);
    
    if (!data.players || data.players.length === 0) {
      document.getElementById('titlePlayerList').innerHTML = utils.createEmptyState('👥', '暂无玩家');
      return;
    }
    
    let html = '<table><tr><th><input type="checkbox" onchange="window.app.modules.titles.toggleSelectAll(this)">全选</th><th>ID</th><th>道号</th><th>境界</th></tr>';
    
    for (const player of data.players) {
      html += `
        <tr>
          <td><input type="checkbox" class="title-player-chk" data-id="${player.id}" data-name="${utils.escapeHtml(player.name)}"></td>
          <td style="color:var(--text-muted);font-size:11px">${player.id}</td>
          <td style="color:var(--gold)">${utils.escapeHtml(player.name)}</td>
          <td>${utils.escapeHtml(player.realm)}</td>
        </tr>
      `;
    }
    
    html += '</table>';
    document.getElementById('titlePlayerList').innerHTML = html;
  } catch {
    document.getElementById('titlePlayerList').innerHTML = utils.createEmptyState('👥', '加载失败');
  }
}

export function toggleSelectAll(chk) {
  const checkboxes = document.querySelectorAll('.title-player-chk');
  const checked = chk ? chk.checked : !Array.from(checkboxes).some(c => c.checked);
  checkboxes.forEach(c => c.checked = checked);
}

export async function giveTitle() {
  const titleKey = document.getElementById('titleGiveSel').value;
  if (!titleKey) {
    utils.showMessage('titleGiveMsg', 'err', '请选择称号');
    return;
  }
  
  const checkboxes = document.querySelectorAll('.title-player-chk:checked');
  if (checkboxes.length === 0) {
    utils.showMessage('titleGiveMsg', 'err', '请至少选择一个玩家');
    return;
  }
  
  const selectedPlayers = Array.from(checkboxes).map(c => ({
    id: parseInt(c.dataset.id),
    name: c.dataset.name
  }));
  
  utils.showMessage('titleGiveMsg', 'ok', '正在授予称号，请稍候...');
  
  let successCount = 0;
  let failCount = 0;
  
  for (const player of selectedPlayers) {
    try {
      await api.post('/players/' + player.id + '/title/add', { titleKey });
      successCount++;
    } catch {
      failCount++;
    }
  }
  
  utils.showMessage('titleGiveMsg', 'ok', `授予完成！成功 ${successCount} 人，失败 ${failCount} 人`);
}
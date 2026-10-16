import * as api from '../api.js';

export async function load() {
  const container = document.getElementById('tab-myProfile');
  if (!container) return;
  
  try {
    const response = await api.get('/me');
    
    if (response.code === 200 && response.player) {
      const player = response.player;
      renderPlayerInfo(container, player);
    } else {
      container.innerHTML = `<div class="empty-state">${response.message || '获取玩家信息失败'}</div>`;
    }
  } catch (error) {
    container.innerHTML = '<div class="empty-state">网络错误，请稍后重试</div>';
  }
}

function renderPlayerInfo(container, player) {
  let itemsHtml = '';
  if (player.items && player.items.length > 0) {
    itemsHtml = `
      <div class="section">
        <h3>📦 背包物品</h3>
        <div class="items-grid">
          ${player.items.map(item => `
            <div class="item-card">
              <span class="item-name">${item.name}</span>
              <span class="item-qty">x${item.qty}</span>
            </div>
          `).join('')}
        </div>
      </div>
    `;
  }
  
  container.innerHTML = `
    <div class="player-profile">
      <div class="profile-header">
        <div class="avatar">🧙</div>
        <div class="basic-info">
          <h2>${player.name}</h2>
          <div class="realm-badge">${player.realm}</div>
        </div>
      </div>
      
      <div class="stats-grid">
        <div class="stat-card">
          <span class="stat-value">${player.level}</span>
          <span class="stat-label">等级</span>
        </div>
        <div class="stat-card">
          <span class="stat-value">${player.experience}</span>
          <span class="stat-label">经验</span>
        </div>
        <div class="stat-card">
          <span class="stat-value">${player.gold}</span>
          <span class="stat-label">金币</span>
        </div>
        <div class="stat-card">
          <span class="stat-value">${player.spiritStones || 0}</span>
          <span class="stat-label">灵石</span>
        </div>
      </div>
      
      <div class="section">
        <h3>❤️ 生命值</h3>
        <div class="progress-bar">
          <div class="progress-fill hp" style="width: ${(player.hp / player.maxHp) * 100}%"></div>
        </div>
        <span class="progress-text">${player.hp} / ${player.maxHp}</span>
      </div>
      
      <div class="section">
        <h3>💧 法力值</h3>
        <div class="progress-bar">
          <div class="progress-fill mp" style="width: ${(player.mp / player.maxMp) * 100}%"></div>
        </div>
        <span class="progress-text">${player.mp} / ${player.maxMp}</span>
      </div>
      
      <div class="section">
        <h3>⚔️ 属性</h3>
        <div class="attr-grid">
          <div class="attr-item">攻击: ${player.attack}</div>
          <div class="attr-item">防御: ${player.defense}</div>
          <div class="attr-item">速度: ${player.speed}</div>
          <div class="attr-item">灵力: ${player.spirit}</div>
        </div>
      </div>
      
      <div class="section">
        <h3>🌿 灵根</h3>
        <div class="spirit-root">
          <span>${player.spiritualRoot || '未觉醒'}</span>
          ${player.rootTier ? `<span class="root-tier">${player.rootTier}</span>` : ''}
        </div>
      </div>
      
      <div class="section">
        <h3>🧘 修炼状态</h3>
        <div class="cultivation-status">
          ${player.cultivating 
            ? `<span class="cultivating">修炼中</span>
               <div class="progress-bar">
                 <div class="progress-fill cultivation" style="width: ${player.cultivationProgress}%"></div>
               </div>
               <span class="progress-text">进度: ${player.cultivationProgress}%</span>`
            : '<span class="idle">空闲中</span>'
          }
        </div>
      </div>
      
      ${itemsHtml}
    </div>
  `;
}
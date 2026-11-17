import * as api from '../api.js';

export async function load() {
  const container = document.getElementById('tab-myProfile');
  if (!container) return;
  
  try {
    const response = await api.get('/me');
    
    if (response.code === 200 && response.player) {
      const player = response.player;
      renderPlayerInfo(container, player);
      loadAccountSecurity(container);
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

      <div class="section account-security">
        <h3>🔐 账号安全</h3>
        <div class="acct-row">
          <span class="acct-label">QQ 绑定</span>
          <span id="acctQqStatus" class="acct-value">加载中…</span>
          <button class="btn btn-sm" id="acctUnbindBtn" disabled>解绑</button>
        </div>
        <div class="acct-row">
          <span class="acct-label">修改密码</span>
          <input type="password" id="acctOldPwd" placeholder="原密码" autocomplete="off">
          <input type="password" id="acctNewPwd" placeholder="新密码（至少8位）" autocomplete="off">
          <button class="btn btn-sm" id="acctChpwdBtn">修改</button>
        </div>
        <div class="acct-row">
          <span class="acct-label">注销账号</span>
          <input type="password" id="acctDelPwd" placeholder="密码" autocomplete="off">
          <button class="btn btn-danger btn-sm" id="acctDelBtn">注销</button>
        </div>
        <div class="acct-hint">注销会删除角色、物品与绑定，且不可恢复。解绑后可在 QQ 里用 /绑定 重新绑定。</div>
        <div id="acctMsg" class="acct-msg"></div>
      </div>
    </div>
  `;
}
// ==================== 账号安全（自助解绑 / 改密 / 注销） ====================

/** 读取绑定状态并挂载三个操作按钮（只操作当前登录账号）。 */
async function loadAccountSecurity(container) {
  const statusEl = container.querySelector('#acctQqStatus');
  const unbindBtn = container.querySelector('#acctUnbindBtn');
  if (!statusEl || !unbindBtn) return;

  try {
    const resp = await api.get('/account/qq-binding');
    if (resp.code === 200 && resp.data && resp.data.bound) {
      statusEl.textContent = `已绑定（${resp.data.openidTail || ''}）`;
      unbindBtn.disabled = false;
    } else {
      statusEl.textContent = '未绑定';
      unbindBtn.disabled = true;
    }
  } catch (e) {
    statusEl.textContent = '获取失败';
  }

  unbindBtn.addEventListener('click', async () => {
    if (!confirm('确定解绑当前 QQ？解绑后可在 QQ 里用 /绑定 重新绑定。')) return;
    unbindBtn.disabled = true;
    try {
      const resp = await api.del('/account/qq-binding');
      showAcctMsg(container, resp.code === 200, resp.message || (resp.code === 200 ? '已解绑' : '解绑失败'));
      if (resp.code === 200) {
        statusEl.textContent = '未绑定';
      } else {
        unbindBtn.disabled = false;
      }
    } catch (e) {
      showAcctMsg(container, false, '网络错误，请稍后重试');
      unbindBtn.disabled = false;
    }
  });

  const chpwdBtn = container.querySelector('#acctChpwdBtn');
  chpwdBtn.addEventListener('click', async () => {
    const oldPassword = container.querySelector('#acctOldPwd').value;
    const newPassword = container.querySelector('#acctNewPwd').value;
    if (!oldPassword || !newPassword) {
      showAcctMsg(container, false, '请填写原密码和新密码');
      return;
    }
    chpwdBtn.disabled = true;
    try {
      const resp = await api.post('/account/password', { oldPassword, newPassword });
      // UserService.changePassword 成功回 code=0，失败回 400/401/404
      const ok = resp.code === 0 || resp.code === 200;
      showAcctMsg(container, ok, resp.message || (ok ? '密码已修改' : '修改失败'));
      if (ok) {
        container.querySelector('#acctOldPwd').value = '';
        container.querySelector('#acctNewPwd').value = '';
      }
    } catch (e) {
      showAcctMsg(container, false, '网络错误，请稍后重试');
    } finally {
      chpwdBtn.disabled = false;
    }
  });

  const delBtn = container.querySelector('#acctDelBtn');
  delBtn.addEventListener('click', async () => {
    const password = container.querySelector('#acctDelPwd').value;
    if (!password) {
      showAcctMsg(container, false, '请输入密码确认');
      return;
    }
    // 双重确认：删号不可逆，且会清空角色数据
    if (!confirm('注销会删除你的角色、物品、灵石与绑定，且不可恢复。确定继续？')) return;
    if (!confirm('最后确认：真的要注销账号吗？')) return;
    delBtn.disabled = true;
    try {
      const resp = await api.post('/account/delete', { password });
      showAcctMsg(container, resp.code === 200, resp.message || (resp.code === 200 ? '账号已注销' : '注销失败'));
      if (resp.code === 200) {
        setTimeout(() => window.app.logout(), 1500);
      } else {
        delBtn.disabled = false;
      }
    } catch (e) {
      showAcctMsg(container, false, '网络错误，请稍后重试');
      delBtn.disabled = false;
    }
  });
}

function showAcctMsg(container, ok, message) {
  const el = container.querySelector('#acctMsg');
  if (!el) return;
  el.textContent = message || '';
  el.className = 'acct-msg ' + (ok ? 'ok' : 'err');
}

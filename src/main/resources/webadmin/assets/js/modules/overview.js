import * as api from '../api.js';
import * as utils from '../utils.js';

export async function load() {
  try {
    const status = await api.get('/status');
    
    const statsGrid = document.getElementById('statsGrid');
    statsGrid.innerHTML = `
      ${utils.createStatCard('运行时', status.uptimeFormatted)}
      ${utils.createStatCard('在线玩家', status.onlineCount || 0)}
      ${utils.createStatCard('已用内存', status.usedMemoryMB || 0, 'MB')}
      ${utils.createStatCard('最大内存', status.maxMemoryMB || 0, 'MB')}
    `;
    
    try {
      const players = await api.get('/players?limit=100');
      document.getElementById('quickStats').innerHTML = `
        <div class="card" style="border:1px solid var(--border-gold)">
          <p>道统传承 · 已有 <b>${players.total || 0}</b> 位修士踏入仙途</p>
        </div>
      `;
    } catch {
      document.getElementById('quickStats').innerHTML = '';
    }
  } catch {
    document.getElementById('statsGrid').innerHTML = utils.createEmptyState('📊', '加载失败');
  }
}
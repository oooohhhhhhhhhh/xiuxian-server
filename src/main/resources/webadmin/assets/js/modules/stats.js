import * as api from '../api.js';
import * as utils from '../utils.js';

export async function loadMessageStats() {
  try {
    const data = await api.get('/stats/messages');
    const stats = data.stats;
    
    if (!stats) return;
    
    document.getElementById('msgStatsCards').innerHTML = `
      ${utils.createStatCard('📨 消息总数', stats.total || 0, '本次运行累计')}
      ${utils.createStatCard('👤 活跃用户', stats.byUser ? stats.byUser.length : 0, 'Top 20 中')}
      ${utils.createStatCard('👥 活跃群', stats.byGroup ? stats.byGroup.length : 0, 'Top 20 中')}
    `;
    
    let userHtml = '<table><tr><th>QQ号</th><th>消息数</th></tr>';
    if (stats.byUser && stats.byUser.length > 0) {
      for (const user of stats.byUser) {
        userHtml += `<tr><td>${utils.escapeHtml(user.key)}</td><td style="color:var(--gold)">${user.count}</td></tr>`;
      }
    } else {
      userHtml += '<tr><td colspan="2" style="text-align:center;color:var(--text-muted)">暂无数据</td></tr>';
    }
    userHtml += '</table>';
    document.getElementById('msgByUser').innerHTML = userHtml;
    
    let groupHtml = '<table><tr><th>群号</th><th>消息数</th></tr>';
    if (stats.byGroup && stats.byGroup.length > 0) {
      for (const group of stats.byGroup) {
        groupHtml += `<tr><td>${utils.escapeHtml(group.key)}</td><td style="color:var(--gold)">${group.count}</td></tr>`;
      }
    } else {
      groupHtml += '<tr><td colspan="2" style="text-align:center;color:var(--text-muted)">暂无数据</td></tr>';
    }
    groupHtml += '</table>';
    document.getElementById('msgByGroup').innerHTML = groupHtml;
    
    let timeHtml = '<table><tr><th>小时</th><th>消息数</th></tr>';
    if (stats.byTime && stats.byTime.length > 0) {
      for (const time of stats.byTime) {
        const pct = stats.total > 0 ? ((time.count / stats.total) * 100).toFixed(1) : 0;
        const bar = `<span style="display:inline-block;height:6px;border-radius:3px;background:var(--gold);width:${Math.max(pct * 3, 1)}px;margin-left:8px;vertical-align:middle"></span>`;
        timeHtml += `<tr><td>${utils.escapeHtml(time.hour)}</td><td style="color:var(--gold)">${time.count}${bar}</td></tr>`;
      }
    } else {
      timeHtml += '<tr><td colspan="2" style="text-align:center;color:var(--text-muted)">暂无数据</td></tr>';
    }
    timeHtml += '</table>';
    document.getElementById('msgByTime').innerHTML = timeHtml;
  } catch {
    document.getElementById('msgStatsCards').innerHTML = utils.createEmptyState('💬', '加载失败');
  }
}

export async function loadCommandStats() {
  try {
    const data = await api.get('/stats/commands');
    const stats = data.stats;
    
    if (!stats) return;
    
    document.getElementById('cmdStatsCards').innerHTML = `
      ${utils.createStatCard('⚡ 指令总调用', stats.total || 0, '本次运行累计')}
      ${utils.createStatCard('👥 活跃群', stats.byGroup ? stats.byGroup.length : 0, 'Top 20 中')}
      ${utils.createStatCard('⌨️ 指令种类', stats.byCommand ? stats.byCommand.length : 0, 'Top 30 中')}
    `;
    
    let groupHtml = '<table><tr><th>群号</th><th>调用次数</th></tr>';
    if (stats.byGroup && stats.byGroup.length > 0) {
      for (const group of stats.byGroup) {
        groupHtml += `<tr><td>${utils.escapeHtml(group.key)}</td><td style="color:var(--jade)">${group.count}</td></tr>`;
      }
    } else {
      groupHtml += '<tr><td colspan="2" style="text-align:center;color:var(--text-muted)">暂无数据</td></tr>';
    }
    groupHtml += '</table>';
    document.getElementById('cmdByGroup').innerHTML = groupHtml;
    
    let cmdHtml = '<table><tr><th>指令</th><th>次数</th><th>占比</th></tr>';
    if (stats.byCommand && stats.byCommand.length > 0) {
      for (const cmd of stats.byCommand) {
        const pct = stats.total > 0 ? ((cmd.count / stats.total) * 100).toFixed(1) : 0;
        cmdHtml += `<tr><td style="color:var(--gold)">/${utils.escapeHtml(cmd.key)}</td><td style="color:var(--jade)">${cmd.count}</td><td>${pct}%</td></tr>`;
      }
    } else {
      cmdHtml += '<tr><td colspan="3" style="text-align:center;color:var(--text-muted)">暂无数据</td></tr>';
    }
    cmdHtml += '</table>';
    document.getElementById('cmdByCommand').innerHTML = cmdHtml;
    
    let timeHtml = '<table><tr><th>小时</th><th>调用次数</th></tr>';
    if (stats.byTime && stats.byTime.length > 0) {
      for (const time of stats.byTime) {
        const pct = stats.total > 0 ? ((time.count / stats.total) * 100).toFixed(1) : 0;
        const bar = `<span style="display:inline-block;height:6px;border-radius:3px;background:var(--jade);width:${Math.max(pct * 3, 1)}px;margin-left:8px;vertical-align:middle"></span>`;
        timeHtml += `<tr><td>${utils.escapeHtml(time.hour)}</td><td style="color:var(--jade)">${time.count}${bar}</td></tr>`;
      }
    } else {
      timeHtml += '<tr><td colspan="2" style="text-align:center;color:var(--text-muted)">暂无数据</td></tr>';
    }
    timeHtml += '</table>';
    document.getElementById('cmdByTime').innerHTML = timeHtml;
  } catch {
    document.getElementById('cmdStatsCards').innerHTML = utils.createEmptyState('⚡', '加载失败');
  }
}
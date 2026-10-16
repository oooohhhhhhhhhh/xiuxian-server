import * as api from '../api.js';
import * as utils from '../utils.js';

let page = 0;
const PAGE_SIZE = 30;

export async function load(targetPage) {
  if (targetPage === undefined) targetPage = 0;
  page = targetPage;
  
  const params = [];
  const uidVal = document.getElementById('traceUserId').value.trim();
  const nameVal = document.getElementById('tracePlayerName').value.trim();
  const qqVal = document.getElementById('traceQQ').value.trim();
  const actionVal = document.getElementById('traceAction').value;
  const startVal = document.getElementById('traceStart').value;
  const endVal = document.getElementById('traceEnd').value;
  
  if (uidVal) params.push('userId=' + encodeURIComponent(uidVal));
  if (nameVal) params.push('playerName=' + encodeURIComponent(nameVal));
  if (qqVal) params.push('qqNumber=' + encodeURIComponent(qqVal));
  if (actionVal) params.push('action=' + encodeURIComponent(actionVal));
  if (startVal) params.push('startTime=' + encodeURIComponent(startVal.replace('T', ' ') + ':00'));
  if (endVal) params.push('endTime=' + encodeURIComponent(endVal.replace('T', ' ') + ':00'));
  params.push('limit=' + PAGE_SIZE);
  params.push('offset=' + (page * PAGE_SIZE));
  
  try {
    const data = await api.get('/player-traces?' + params.join('&'));

    if (!data.traces || data.traces.length === 0) {
      utils.showEmpty('tracesTable', '👣', '暂无操作轨迹记录');
      document.getElementById('tracesPagination').innerHTML = '';
      return;
    }

    let html = '<table><tr><th>时间</th><th>UID</th><th>玩家名</th><th>操作</th><th>详情</th><th>QQ</th></tr>';

    for (const trace of data.traces) {
      html += `
        <tr>
          <td style="font-size:11px;color:var(--text-muted)">${utils.escapeHtml(trace.createdAt || '')}</td>
          <td style="color:var(--text-muted);font-size:11px">${trace.userId || ''}</td>
          <td style="color:var(--gold)">${utils.escapeHtml(trace.playerName || '')}</td>
          <td>${utils.escapeHtml(trace.action || '')}</td>
          <td style="color:var(--text-secondary)">${utils.escapeHtml(trace.detail || '')}</td>
          <td style="color:var(--text-muted);font-size:11px">${utils.escapeHtml(trace.qqNumber || '')}</td>
        </tr>
      `;
    }

    html += '</table>';
    document.getElementById('tracesTable').innerHTML = html;

    utils.renderPagination('tracesPagination', data.total || 0, page, PAGE_SIZE,
      p => `window.app.modules.traces.load(${p})`);
  } catch (error) {
    utils.showEmpty('tracesTable', '👣', '加载失败: ' + (error.message || ''));
  }
}

export function reset() {
  document.getElementById('tracePlayerName').value = '';
  document.getElementById('traceUserId').value = '';
  document.getElementById('traceQQ').value = '';
  document.getElementById('traceAction').value = '';
  document.getElementById('traceStart').value = '';
  document.getElementById('traceEnd').value = '';
  load();
}
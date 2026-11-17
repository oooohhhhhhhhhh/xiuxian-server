import * as api from '../api.js';
import * as utils from '../utils.js';

/**
 * 审计日志页：按主体 / 动作 / 结果 / 时间筛选，支持导出 CSV。
 *
 * 审计合并了玩家 / 管理员 / 系统 / 适配器 / 插件五类主体，
 * 取代了原来的「玩家轨迹」页（那个页面只能看玩家）。
 */

let page = 0;
const PAGE_SIZE = 50;

const ACTOR_LABELS = {
  PLAYER: '玩家',
  ADMIN: '管理员',
  SYSTEM: '系统',
  ADAPTER: '适配器',
  PLUGIN: '插件'
};

const RESULT_LABELS = {
  OK: '成功',
  DENY: '拒绝',
  FAIL: '失败'
};

function buildParams(includePaging) {
  const params = [];
  const actorType = document.getElementById('auditActorType').value;
  const action = document.getElementById('auditAction').value.trim();
  const actorId = document.getElementById('auditActorId').value.trim();
  const targetId = document.getElementById('auditTargetId').value.trim();
  const result = document.getElementById('auditResult').value;
  const keyword = document.getElementById('auditKeyword').value.trim();
  const from = document.getElementById('auditFrom').value;
  const to = document.getElementById('auditTo').value;

  if (actorType) params.push('actorType=' + encodeURIComponent(actorType));
  if (action) params.push('action=' + encodeURIComponent(action));
  if (actorId) params.push('actorId=' + encodeURIComponent(actorId));
  if (targetId) params.push('targetId=' + encodeURIComponent(targetId));
  if (result) params.push('result=' + encodeURIComponent(result));
  if (keyword) params.push('keyword=' + encodeURIComponent(keyword));
  if (from) params.push('from=' + new Date(from).getTime());
  if (to) params.push('to=' + new Date(to).getTime());
  if (includePaging) {
    params.push('limit=' + PAGE_SIZE);
    params.push('offset=' + (page * PAGE_SIZE));
  }
  return params;
}

function formatTs(ts) {
  const n = Number(ts);
  if (!n) return '';
  const d = new Date(n);
  const pad = v => String(v).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function renderResult(result) {
  const label = RESULT_LABELS[result] || result || '';
  const cls = result === 'OK' ? 'ok' : (result === 'DENY' ? 'warn' : 'err');
  return `<span class="audit-result ${cls}">${utils.escapeHtml(label)}</span>`;
}

export async function load(targetPage) {
  if (targetPage === undefined) targetPage = 0;
  page = targetPage;

  try {
    const data = await api.get('/audit-logs?' + buildParams(true).join('&'));

    if (!data.logs || data.logs.length === 0) {
      utils.showEmpty('auditTable', '📋', '暂无审计记录');
      document.getElementById('auditPagination').innerHTML = '';
      return;
    }

    let html = '<table><tr>'
      + '<th>时间</th><th>主体</th><th>行为者</th><th>动作</th>'
      + '<th>目标</th><th>结果</th><th>IP</th><th>详情</th></tr>';

    for (const log of data.logs) {
      const actorLabel = ACTOR_LABELS[log.actorType] || log.actorType || '';
      const target = log.targetType
        ? `${utils.escapeHtml(log.targetType)}:${utils.escapeHtml(log.targetId || '')}`
        : '';
      html += `
        <tr>
          <td style="font-size:11px;color:var(--text-muted);white-space:nowrap">${formatTs(log.ts)}</td>
          <td>${utils.escapeHtml(actorLabel)}</td>
          <td style="color:var(--gold)">${utils.escapeHtml(log.actorName || log.actorId || '')}</td>
          <td>${utils.escapeHtml(log.action || '')}</td>
          <td style="font-size:11px">${target}</td>
          <td>${renderResult(log.result)}</td>
          <td style="font-size:11px;color:var(--text-muted)">${utils.escapeHtml(log.ip || '')}</td>
          <td style="color:var(--text-secondary);max-width:420px;word-break:break-all">${utils.escapeHtml(log.detail || '')}</td>
        </tr>
      `;
    }
    html += '</table>';
    document.getElementById('auditTable').innerHTML = html;

    utils.renderPagination('auditPagination', data.total || 0, page, PAGE_SIZE,
      p => `window.app.modules.audit.load(${p})`);
  } catch (error) {
    utils.showEmpty('auditTable', '📋', '加载失败: ' + (error.message || ''));
  }
}

/** 导出 CSV：走 download 以便带上 Authorization 头。 */
export async function exportCsv() {
  try {
    const response = await api.download('/audit-logs/export?' + buildParams(false).join('&'));
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'audit-logs.csv';
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  } catch (error) {
    utils.showEmpty('auditTable', '📋', '导出失败: ' + (error.message || ''));
  }
}

export function reset() {
  document.getElementById('auditActorType').value = '';
  document.getElementById('auditAction').value = '';
  document.getElementById('auditActorId').value = '';
  document.getElementById('auditTargetId').value = '';
  document.getElementById('auditResult').value = '';
  document.getElementById('auditKeyword').value = '';
  document.getElementById('auditFrom').value = '';
  document.getElementById('auditTo').value = '';
  load();
}

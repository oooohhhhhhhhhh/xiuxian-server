// 通用工具函数（仅 JS 逻辑优化，不影响主题样式）

/**
 * HTML 转义。除 & < > " 外，额外转义单引号，
 * 防止拼接进 inline onclick="fn('...')" 时被单引号截断（XSS/属性截断）。
 */
export function escapeHtml(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

export function formatTime(dateStr) {
  if (!dateStr) return '--';
  try {
    const date = new Date(dateStr);
    return date.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    });
  } catch {
    return dateStr;
  }
}

export function formatNumber(num) {
  if (num === null || num === undefined) return '0';
  if (num >= 1000000) return (num / 10000).toFixed(1) + '万';
  if (num >= 10000) return (num / 10000).toFixed(2) + '万';
  return String(num);
}

export function showMessage(elementId, type, message) {
  const el = document.getElementById(elementId);
  if (!el) return;
  el.innerHTML = `<div class="msg msg-${type}">${escapeHtml(message)}</div>`;
  setTimeout(() => {
    el.innerHTML = '';
  }, 5000);
}

export function showModal(content) {
  const modalContent = document.getElementById('modalContent');
  const modalOverlay = document.getElementById('modalOverlay');
  if (!modalContent || !modalOverlay) return;
  modalContent.innerHTML = content;
  modalOverlay.classList.add('show');
}

export function closeModal() {
  const modalOverlay = document.getElementById('modalOverlay');
  if (modalOverlay) {
    modalOverlay.classList.remove('show');
  }
}

export function createModal(title, content, actions = []) {
  let actionsHtml = '';
  if (actions.length > 0) {
    actionsHtml = '<div class="actions">';
    actions.forEach(action => {
      const btnClass = action.danger ? 'btn-danger' : (action.primary ? 'btn-primary' : 'btn-action');
      actionsHtml += `<button class="btn ${btnClass}" onclick="${action.onclick}">${escapeHtml(action.text)}</button>`;
    });
    actionsHtml += '</div>';
  }
  return `
    <button class="close" onclick="window.app.utils.closeModal()">&times;</button>
    <h3>${escapeHtml(title)}</h3>
    ${content}
    ${actionsHtml}
  `;
}

export function createStatCard(label, value, desc = '') {
  return `
    <div class="stat-card">
      <div class="label">${escapeHtml(label)}</div>
      <div class="value">${escapeHtml(String(value))}</div>
      ${desc ? `<div class="desc">${escapeHtml(desc)}</div>` : ''}
    </div>
  `;
}

export function createBadge(text, type = 'gold') {
  return `<span class="badge badge-${type}">${escapeHtml(text)}</span>`;
}

export function createTag(text, removable = false, onRemove = '') {
  const closeBtn = removable ? `<span class="tag-x" onclick="${onRemove}" title="移除">&times;</span>` : '';
  return `<span class="tag">${escapeHtml(text)}${closeBtn}</span>`;
}

export function createTable(columns, rows, actions = []) {
  let html = '<table><tr>';
  columns.forEach(col => {
    html += `<th>${escapeHtml(col)}</th>`;
  });
  if (actions.length > 0) {
    html += '<th>操作</th>';
  }
  html += '</tr>';

  rows.forEach(row => {
    html += '<tr>';
    columns.forEach(col => {
      const value = row[col];
      const display = value === null ? '<span style="color:var(--text-muted)">NULL</span>' : escapeHtml(String(value));
      html += `<td>${display}</td>`;
    });
    if (actions.length > 0) {
      html += '<td>';
      actions.forEach(action => {
        const btnClass = action.danger ? 'btn-danger' : (action.primary ? 'btn-primary' : 'btn-action');
        html += `<button class="btn ${btnClass} btn-sm" onclick="${action.onclick(row)}">${escapeHtml(action.text)}</button> `;
      });
      html += '</td>';
    }
    html += '</tr>';
  });

  html += '</table>';
  return html;
}

export function createEmptyState(icon = '☯', text = '暂无数据') {
  return `
    <div class="empty">
      <div class="empty-icon">${icon}</div>
      ${escapeHtml(text)}
    </div>
  `;
}

/** 将空态写入指定容器（统一错误/空数据处理） */
export function showEmpty(elementId, icon = '☯', text = '暂无数据') {
  const el = document.getElementById(elementId);
  if (el) el.innerHTML = createEmptyState(icon, text);
}

/** 将"加载中"占位写入指定容器 */
export function showLoading(elementId, text = '加载中...') {
  const el = document.getElementById(elementId);
  if (el) el.innerHTML = `<div class="empty">${escapeHtml(text)}</div>`;
}

/** 渲染分页栏到指定容器。onGo(pageIndex) 返回要执行的 JS 调用字符串。 */
export function renderPagination(containerId, total, page, pageSize, onGo) {
  const el = document.getElementById(containerId);
  if (!el) return;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  let html = `<span style="color:var(--text-muted);font-size:12px">共 ${total} 条 · 第 ${page + 1}/${totalPages} 页</span> `;

  if (totalPages > 1) {
    html += `<button class="btn-sm" onclick="${onGo(0)}" ${page === 0 ? 'disabled' : ''}>首页</button> `;
    html += `<button class="btn-sm" onclick="${onGo(Math.max(0, page - 1))}" ${page === 0 ? 'disabled' : ''}>上一页</button> `;
    html += `<button class="btn-sm" onclick="${onGo(Math.min(totalPages - 1, page + 1))}" ${page >= totalPages - 1 ? 'disabled' : ''}>下一页</button> `;
    html += `<button class="btn-sm" onclick="${onGo(totalPages - 1)}" ${page >= totalPages - 1 ? 'disabled' : ''}>末页</button>`;
  }
  el.innerHTML = html;
}

export function createPagination(page, totalPages, totalItems, onPageChange) {
  if (totalPages <= 1) return '';

  let html = `<span style="color:var(--text-muted);font-size:12px">共 ${totalItems} 条 · 第 ${page + 1}/${totalPages} 页</span> `;
  html += `<button class="btn-sm" onclick="${onPageChange(0)}" ${page === 0 ? 'disabled' : ''}>首页</button> `;
  html += `<button class="btn-sm" onclick="${onPageChange(Math.max(0, page - 1))}" ${page === 0 ? 'disabled' : ''}>上一页</button> `;
  html += `<button class="btn-sm" onclick="${onPageChange(Math.min(totalPages - 1, page + 1))}" ${page >= totalPages - 1 ? 'disabled' : ''}>下一页</button> `;
  html += `<button class="btn-sm" onclick="${onPageChange(totalPages - 1)}" ${page >= totalPages - 1 ? 'disabled' : ''}>末页</button>`;
  return html;
}

export function createSearchRow(placeholder, onSearch, onReset, searchId = 'searchInput') {
  return `
    <div class="row">
      <input class="grow" id="${searchId}" placeholder="${placeholder}" onkeydown="if(event.key==='Enter')${onSearch}()">
      <button class="btn btn-primary" onclick="${onSearch}()">搜索</button>
      <button class="btn-sm" onclick="${onReset}()">全部</button>
    </div>
  `;
}

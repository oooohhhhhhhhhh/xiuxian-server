import * as api from '../api.js';
import * as utils from '../utils.js';

let lastSeq = 0;
let firstLoad = true;
let paused = false;
let autoScroll = true;

export function init() {
  lastSeq = 0;
  firstLoad = true;
  paused = false;
  document.getElementById('logContainer').innerHTML = '<div class="empty">加载中...</div>';
  document.getElementById('btnPause').textContent = '⏸ 暂停';
}

export function togglePause() {
  paused = !paused;
  const btn = document.getElementById('btnPause');
  btn.textContent = paused ? '▶ 继续' : '⏸ 暂停';
  btn.style.background = paused ? 'rgba(61,184,160,0.2)' : '';
  btn.style.color = paused ? 'var(--jade)' : '';
}

export function clear() {
  document.getElementById('logContainer').innerHTML = '';
  lastSeq = 0;
}

export async function fetchLogs() {
  if (paused || !api.isAuthenticated()) return;
  
  try {
    const data = await api.get('/logs?since=' + lastSeq);
    const container = document.getElementById('logContainer');
    
    if (firstLoad) {
      container.innerHTML = '';
      firstLoad = false;
    }
    
    if (data.entries && data.entries.length > 0) {
      for (const entry of data.entries) {
        const line = document.createElement('div');
        line.className = 'log-line';
        line.innerHTML = `
          <span style="color:#3a4a5a">[${utils.escapeHtml(entry.timestamp)}]</span>
          <span style="${getLevelColor(entry.level)}">[${utils.escapeHtml(entry.level)}]</span>
          <span style="color:#8e6bb8">[${utils.escapeHtml(entry.logger)}]</span>
          <span style="color:var(--text-secondary)">${utils.escapeHtml(entry.message)}</span>
        `;
        container.appendChild(line);
        lastSeq = Math.max(lastSeq, entry.seq || 0);
      }
      
      // 超过上限时一次性裁剪（避免逐条 removeChild 造成多次重排）
      if (container.children.length > 2000) {
        const keep = Array.from(container.children).slice(-2000);
        container.replaceChildren(...keep);
      }
      
      if (autoScroll) {
        container.scrollTop = container.scrollHeight;
      }
    }
    
    if (data.latestSeq > lastSeq) {
      lastSeq = data.latestSeq;
    }
  } catch {}
}

function getLevelColor(level) {
  switch (level) {
    case 'DEBUG': return 'color:#5b9bd5';
    case 'INFO': return 'color:#3db8a0';
    case 'WARN': return 'color:#e89440';
    case 'ERROR': return 'color:#e05353';
    default: return 'color:var(--text-muted)';
  }
}

export function initScrollListener() {
  const container = document.getElementById('logContainer');
  if (container) {
    container.addEventListener('scroll', () => {
      autoScroll = container.scrollHeight - container.scrollTop - container.clientHeight < 30;
    });
  }
}
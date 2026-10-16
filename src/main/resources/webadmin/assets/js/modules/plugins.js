// 插件管理模块
import * as api from '../api.js';

let plugins = [];
let watcherRunning = false;
let pluginConfigFile = '';

export function init() {
  load();
}

export async function load() {
  try {
    const resp = await api.get('/plugins/list');
    if (resp.code === 200) {
      plugins = resp.plugins || [];
      watcherRunning = resp.watcherRunning || false;
      pluginConfigFile = resp.pluginConfigFile || '';
      render();
      updateConfigHint();
    }
  } catch (err) {
    console.error('加载插件列表失败:', err);
    showMessage('加载失败: ' + err.message, 'error');
  }
}

// 内置插件分组标签：玩法模块 / 实验性插件 / 消息通道适配器
function builtInGroupLabel(plugin) {
  if (plugin.category === 'experimental') return '内置·实验性';
  if (plugin.category === 'adapter') return '内置·适配器';
  return '内置';
}

// 开关取值来源提示（旧配置兜底时提醒用户迁移到 plugins.yml）
function configSourceHint(plugin) {
  if (plugin.configSource === 'legacy') {
    return `<div class="text-muted" style="font-size:12px;margin-top:2px">⚠ 开关来自旧配置（已迁移到 plugins.yml，重启后以新文件为准）</div>`;
  }
  return '';
}

function updateConfigHint() {
  const el = document.getElementById('pluginConfigHint');
  if (!el) return;
  const file = pluginConfigFile || 'config/plugins.yml';
  el.innerHTML = `内置插件（含实验性插件与消息通道）的开关保存在 <code>${escapeHtml(file)}</code>`
    + `（键 <code>plugins.&lt;插件名&gt;</code>），在此页启停会立即写回该文件并重启后生效。`;
}

function render() {
  const container = document.getElementById('pluginList');
  if (!container) return;

  if (plugins.length === 0) {
    container.innerHTML = '<div class="empty">暂无插件</div>';
    updateWatcherButton();
    return;
  }

  let html = '<table class="table"><thead><tr>';
  html += '<th>插件名称</th>';
  html += '<th>版本</th>';
  html += '<th>类型</th>';
  html += '<th>作者</th>';
  html += '<th>状态</th>';
  html += '<th>文件 / 配置键</th>';
  html += '<th>操作</th>';
  html += '</tr></thead><tbody>';

  for (const plugin of plugins) {
    const isBuiltIn = plugin.type === 'builtin';
    const status = plugin.enabled ? '已启用' : '已停用';
    const statusClass = plugin.enabled ? 'badge-success' : 'badge-muted';
    const typeLabel = isBuiltIn ? builtInGroupLabel(plugin) : '外部';
    const typeBadge = isBuiltIn
      ? (plugin.category === 'experimental' ? 'badge-warning' : 'badge-info')
      : 'badge-secondary';
    const location = isBuiltIn
      ? (plugin.configKey || '-')
      : (plugin.jarFile || plugin.jarFileName || '-');
    
    html += '<tr>';
    html += `<td><b>${escapeHtml(plugin.name)}</b>`;
    if (plugin.description) {
      html += `<div class="text-muted" style="font-size:12px;margin-top:2px">${escapeHtml(plugin.description)}</div>`;
    }
    html += configSourceHint(plugin);
    html += '</td>';
    html += `<td>${escapeHtml(plugin.version || '-')}</td>`;
    html += `<td><span class="badge ${typeBadge}">${typeLabel}</span></td>`;
    html += `<td>${escapeHtml(plugin.author || '-')}</td>`;
    html += `<td><span class="badge ${statusClass}">${status}</span></td>`;
    html += `<td style="font-family:monospace;font-size:12px">${escapeHtml(location)}</td>`;
    html += '<td>';
    
    if (plugin.enabled) {
      html += `<button class="btn-sm btn-warning" onclick="window.app.modules.plugins.disablePlugin('${escapeHtml(plugin.name)}', '${plugin.type}')">停用</button> `;
    } else {
      html += `<button class="btn-sm btn-success" onclick="window.app.modules.plugins.enablePlugin('${escapeHtml(plugin.name)}', '${plugin.type}')">启用</button> `;
    }
    
    // 内置插件不显示重载和卸载按钮
    if (!isBuiltIn) {
      html += `<button class="btn-sm" onclick="window.app.modules.plugins.reloadPlugin('${escapeHtml(plugin.name)}')">重载</button> `;
      html += `<button class="btn-sm btn-danger" onclick="window.app.modules.plugins.unloadPlugin('${escapeHtml(plugin.name)}')">卸载</button>`;
    }
    html += '</td>';
    html += '</tr>';
  }

  html += '</tbody></table>';
  container.innerHTML = html;
  
  updateWatcherButton();
}

function updateWatcherButton() {
  const btn = document.getElementById('btnToggleWatcher');
  if (!btn) return;
  
  if (watcherRunning) {
    btn.textContent = '⏸ 停止监听';
    btn.className = 'btn-sm btn-warning';
    btn.onclick = () => stopWatcher();
  } else {
    btn.textContent = '▶️ 启动监听';
    btn.className = 'btn-sm btn-success';
    btn.onclick = () => startWatcher();
  }
}

export async function enablePlugin(name, type) {
  try {
    const qs = type ? `?type=${encodeURIComponent(type)}` : '';
    const resp = await api.post(`/plugins/enable/${encodeURIComponent(name)}${qs}`);
    showMessage(resp.message || '插件已启用', resp.code === 200 ? 'success' : 'error');
    if (resp.code === 200) {
      await load();
    }
  } catch (err) {
    showMessage('启用失败: ' + err.message, 'error');
  }
}

export async function disablePlugin(name, type) {
  try {
    const qs = type ? `?type=${encodeURIComponent(type)}` : '';
    const resp = await api.post(`/plugins/disable/${encodeURIComponent(name)}${qs}`);
    showMessage(resp.message || '插件已停用', resp.code === 200 ? 'success' : 'error');
    if (resp.code === 200) {
      await load();
    }
  } catch (err) {
    showMessage('停用失败: ' + err.message, 'error');
  }
}

export async function reloadPlugin(name) {
  if (!confirm(`确定要重载插件 "${name}" 吗？`)) return;
  
  try {
    const resp = await api.post(`/plugins/reload/${encodeURIComponent(name)}`);
    showMessage(resp.message || '插件已重载', resp.code === 200 ? 'success' : 'error');
    if (resp.code === 200) {
      await load();
    }
  } catch (err) {
    showMessage('重载失败: ' + err.message, 'error');
  }
}

export async function unloadPlugin(name) {
  if (!confirm(`确定要卸载插件 "${name}" 吗？卸载后需要重新加载才能使用。`)) return;
  
  try {
    const resp = await api.post(`/plugins/unload/${encodeURIComponent(name)}`);
    showMessage(resp.message || '插件已卸载', resp.code === 200 ? 'success' : 'error');
    if (resp.code === 200) {
      await load();
    }
  } catch (err) {
    showMessage('卸载失败: ' + err.message, 'error');
  }
}

export async function loadPluginFile(jarName) {
  try {
    const resp = await api.post(`/plugins/load/${encodeURIComponent(jarName)}`);
    showMessage(resp.message || '插件已加载', resp.code === 200 ? 'success' : 'error');
    if (resp.code === 200) {
      await load();
    }
  } catch (err) {
    showMessage('加载失败: ' + err.message, 'error');
  }
}

async function startWatcher() {
  try {
    const resp = await api.post('/plugins/watcher/start');
    showMessage(resp.message || '文件监听已启动', 'success');
    watcherRunning = true;
    updateWatcherButton();
  } catch (err) {
    showMessage('启动监听失败: ' + err.message, 'error');
  }
}

async function stopWatcher() {
  try {
    const resp = await api.post('/plugins/watcher/stop');
    showMessage(resp.message || '文件监听已停止', 'success');
    watcherRunning = false;
    updateWatcherButton();
  } catch (err) {
    showMessage('停止监听失败: ' + err.message, 'error');
  }
}

export function openLoadDialog() {
  const jarFiles = prompt('请输入要加载的插件jar文件名（位于plugins/目录下）：');
  if (jarFiles && jarFiles.trim()) {
    loadPluginFile(jarFiles.trim());
  }
}

function showMessage(msg, type = 'info') {
  const el = document.getElementById('pluginMsg');
  if (!el) return;
  
  el.textContent = msg;
  el.className = `msg msg-${type}`;
  el.style.display = 'block';
  
  setTimeout(() => {
    el.style.display = 'none';
  }, 5000);
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

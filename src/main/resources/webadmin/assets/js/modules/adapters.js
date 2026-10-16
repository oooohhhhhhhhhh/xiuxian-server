import * as api from '../api.js';
import * as utils from '../utils.js';

let adapterList = [];

export async function load() {
  const data = await api.get('/adapters');
  if (!data || data.code !== 200) {
    document.getElementById('adapterMsg').innerHTML = '<div class="empty">加载失败</div>';
    return;
  }
  
  adapterList = data.data || [];
  renderList();
  renderEditor();
}

function renderList() {
  const container = document.getElementById('adapterList');
  if (!adapterList.length) {
    container.innerHTML = '<div class="empty">暂无适配器</div>';
    return;
  }

  let html = '<table><thead><tr><th>ID</th><th>类型</th><th>启用</th><th>运行状态</th><th>操作</th></tr></thead><tbody>';
  for (const a of adapterList) {
    const typeLabel = a.type === 'onebot' ? '🤖 OneBot' : a.type === 'qq' ? '💬 QQ官方' : a.type === 'minecraft' ? '⛏️ Minecraft' : a.type === 'minecraft-player' ? '🎮 MC-Player' : a.type;
    // minecraft 类型适配器标记为实验性
    const statusIcon = a.running ? '<span class="tag tag-green">运行中</span>' : '<span class="tag tag-gray">已停止</span>';
    const toggled = a.enabled ? 'checked' : '';

    html += `<tr>
      <td><code>${utils.escapeHtml(a.id)}</code></td>
      <td>${typeLabel}</td>
      <td><label class="switch"><input type="checkbox" ${toggled} onchange="window.app.modules.adapters.toggleEnable('${utils.escapeHtml(a.id)}')"><span class="slider"></span></label></td>
      <td>${statusIcon}</td>
      <td>
        <button class="btn-sm btn-action" onclick="window.app.modules.adapters.editAdapter('${utils.escapeHtml(a.id)}')">✏️ 编辑</button>
        <button class="btn-sm btn-danger" onclick="window.app.modules.adapters.deleteAdapter('${utils.escapeHtml(a.id)}')">🗑 删除</button>
      </td>
    </tr>`;
  }
  html += '</tbody></table>';
  container.innerHTML = html;
}

function renderEditor() {
  const container = document.getElementById('adapterEditor');
  container.innerHTML = `
    <div class="card-title">🆕 添加适配器</div>
    <div class="row" style="flex-wrap:wrap;gap:8px">
      <input id="newAdapterId" placeholder="适配器ID (如 onebot-2)" style="flex:2;min-width:150px">
      <select id="newAdapterType" style="flex:1;min-width:120px">
        <option value="onebot">🤖 OneBot</option>
        <option value="qq">💬 QQ官方</option>
      </select>
      <button class="btn btn-primary" onclick="window.app.modules.adapters.addAdapter()">+ 添加</button>
    </div>
    <div style="margin-top:16px;display:flex;gap:8px">
      <button class="btn btn-action" onclick="window.app.modules.adapters.reloadAll()">🔄 重载所有适配器</button>
      <button class="btn btn-action" onclick="window.app.modules.adapters.load()">📋 刷新列表</button>
    </div>
    <div id="adapterMsg" style="margin-top:12px"></div>
  `;
}

// 添加新适配器
export async function addAdapter() {
  const id = document.getElementById('newAdapterId').value.trim();
  const type = document.getElementById('newAdapterType').value;
  
  if (!id) {
    showMsg('请输入适配器ID', 'error');
    return;
  }
  
  // 检查重复
  if (adapterList.find(a => a.id === id)) {
    showMsg('适配器ID已存在', 'error');
    return;
  }

  let config = {};
  if (type === 'onebot') {
    config = { port: 6700, mode: 'ws_server', send_mode: 'text', screenshot_window_title: '', screenshot_poll_interval: 2000 };
  } else if (type === 'qq') {
    config = { app_id: '', client_secret: '', intents: 'GROUP_AT_MESSAGE_CREATE,C2C_MESSAGE_CREATE', gateway_url: 'wss://api.sgroup.qq.com/websocket', sandbox: false, remove_at: true, command_prefix: '', send_mode: 'text', reconnect_interval_ms: 5000, mode: 'websocket', webhook_host: '0.0.0.0', webhook_port: 8080, webhook_path: '/qq/webhook', webhook_max_skew_seconds: 300 };
  } else if (type === 'minecraft') {
    config = { jar_path: '', server_dir: 'mc_server', java_path: 'java', min_memory_mb: 512, max_memory_mb: 1024, auto_start: false, ready_timeout_seconds: 120, extra_jvm_args: '', online_mode: false, command_prefix: 'xiuxian' };
  } else if (type === 'minecraft-player') {
    config = { host: '127.0.0.1', port: 25565, username: 'XiuXianBot', password: '', protocol_version: 0, command_prefix: '', login_delay_ms: 3000 };
  }

  adapterList.push({ id, type, enabled: false, running: false, config });
  await saveConfig();
  load();
}

// 删除适配器
export async function deleteAdapter(id) {
  if (!confirm('确定删除适配器 ' + id + '？')) return;
  adapterList = adapterList.filter(a => a.id !== id);
  await saveConfig();
  load();
}

// 启用/禁用切换
export async function toggleEnable(id) {
  const adapter = adapterList.find(a => a.id === id);
  if (!adapter) return;
  adapter.enabled = !adapter.enabled;
  await saveConfig();
  load();
}

// 编辑适配器
export function editAdapter(id) {
  const adapter = adapterList.find(a => a.id === id);
  if (!adapter) return;

  let configHtml = '';
  for (const [key, val] of Object.entries(adapter.config)) {
    const type = typeof val === 'boolean' ? 'checkbox' : typeof val === 'number' ? 'number' : 'text';
    if (type === 'checkbox') {
      configHtml += `<div class="row"><label style="width:160px">${key}</label>
        <input type="checkbox" ${val ? 'checked' : ''} data-key="${key}" data-val="${val}">
      </div>`;
    } else {
      configHtml += `<div class="row"><label style="width:160px">${key}</label>
        <input type="${type}" value="${utils.escapeHtml(String(val))}" data-key="${key}" style="flex:1">
      </div>`;
    }
  }

  const html = `
    <button class="close" onclick="window.app.utils.closeModal()">&times;</button>
    <h3>✏️ 编辑适配器: ${utils.escapeHtml(id)}</h3>
    <div class="form-group">
      <label>类型: <b>${utils.escapeHtml(adapter.type)}</b></label>
    </div>
    <div class="form-group">
      <label><input type="checkbox" id="editEnabled" ${adapter.enabled ? 'checked' : ''}> 启用</label>
    </div>
    <h4>配置参数</h4>
    ${configHtml}
    <div class="row" style="margin-top:16px;gap:8px">
      <button class="btn btn-primary" onclick="window.app.modules.adapters.saveEdit('${utils.escapeHtml(id)}')">💾 保存</button>
      <button class="btn-sm" onclick="window.app.utils.closeModal()">取消</button>
    </div>
  `;
  utils.showModal(html);
}

// 保存编辑
export async function saveEdit(id) {
  const adapter = adapterList.find(a => a.id === id);
  if (!adapter) return;

  adapter.enabled = document.getElementById('editEnabled').checked;

  const inputs = document.querySelectorAll('#modalContent input[data-key]');
  inputs.forEach(input => {
    const key = input.dataset.key;
    if (input.type === 'checkbox') {
      adapter.config[key] = input.checked;
    } else if (input.type === 'number') {
      adapter.config[key] = Number(input.value);
    } else {
      adapter.config[key] = input.value;
    }
  });

  utils.closeModal();
  await saveConfig();
  load();
  showMsg('适配器配置已保存', 'success');
}

// 重载所有适配器
export async function reloadAll() {
  try {
    const data = await api.post('/adapters/reload');
    if (data && data.code === 200) {
      showMsg('适配器已重载', 'success');
    } else {
      showMsg(data?.message || '重载失败', 'error');
    }
  } catch (e) {
    showMsg('重载失败: ' + e.message, 'error');
  }
  load();
}

// 保存配置到服务端
async function saveConfig() {
  const payload = { adapters: adapterList.map(a => ({
    id: a.id,
    type: a.type,
    enabled: a.enabled,
    config: a.config
  })) };
  
  try {
    const data = await api.put('/adapters', payload);
    if (!data || data.code !== 200) {
      showMsg(data?.message || '保存失败', 'error');
    }
  } catch (e) {
    showMsg('保存失败: ' + e.message, 'error');
  }
}

function showMsg(msg, type) {
  const el = document.getElementById('adapterMsg');
  if (el) {
    el.innerHTML = `<div style="color:${type === 'error' ? 'var(--red)' : 'var(--green)'}">${utils.escapeHtml(msg)}</div>`;
    setTimeout(() => { if (el) el.innerHTML = ''; }, 3000);
  }
}

import * as api from '../api.js';

const API_ROOT = '/api';
let issuedToken = '';

export async function load() {
  try {
    const resp = await api.get('/oauth/config');
    if (resp.code !== 0 && resp.code !== 200) {
      document.getElementById('oauthMsg').innerHTML = `<span class="err">${resp.message || '加载失败'}</span>`;
      return;
    }
    const cfg = resp.data || resp;
    renderConfigForm(cfg);
    document.getElementById('oauthMsg').innerHTML = '';
  } catch (e) {
    document.getElementById('oauthMsg').innerHTML = `<span class="err">加载 OAuth2 配置失败: ${e.message}</span>`;
  }
}

function renderConfigForm(cfg) {
  document.getElementById('oauthEnabled').checked = !!cfg.enabled;
  document.getElementById('oauthClientId').value = cfg.client_id || '';
  document.getElementById('oauthClientSecret').value = cfg.client_secret || '';
  document.getElementById('oauthScopes').value = cfg.scopes || '';
  document.getElementById('oauthTtl').value = cfg.token_ttl_seconds || 3600;
  document.getElementById('oauthPersistPath').textContent = cfg.persistPath || '';
}

export function toggleSecret() {
  const input = document.getElementById('oauthClientSecret');
  input.type = input.type === 'password' ? 'text' : 'password';
}

export async function saveConfig() {
  const body = {
    enabled: document.getElementById('oauthEnabled').checked,
    client_id: document.getElementById('oauthClientId').value.trim(),
    client_secret: document.getElementById('oauthClientSecret').value.trim(),
    scopes: document.getElementById('oauthScopes').value.trim(),
    token_ttl_seconds: parseInt(document.getElementById('oauthTtl').value, 10) || 3600
  };
  // 未修改密码时前端无法区分"打码值"与真实值，这里用占位标记让后端跳过
  if (body.client_secret.startsWith('***') || body.client_secret.startsWith('******') || body.client_secret.includes('******')) {
    delete body.client_secret;
  }
  try {
    const resp = await api.post('/oauth/config', body);
    const msg = document.getElementById('oauthMsg');
    if (resp.code === 200 || resp.code === 0) {
      msg.innerHTML = `<span class="ok">✅ ${resp.message || '保存成功'}</span>`;
      load();
    } else {
      msg.innerHTML = `<span class="err">${resp.message || '保存失败'}</span>`;
    }
  } catch (e) {
    document.getElementById('oauthMsg').innerHTML = `<span class="err">保存失败: ${e.message}</span>`;
  }
}

// ==================== 使用 OAuth（playground）====================

async function rawFetch(path, options = {}) {
  const resp = await fetch(API_ROOT + path, options);
  const text = await resp.text();
  try {
    return { status: resp.status, data: JSON.parse(text) };
  } catch {
    return { status: resp.status, data: text };
  }
}

/** 1. 用 client_credentials 换 token */
export async function issueToken() {
  const clientId = document.getElementById('oauthClientId').value.trim();
  const secret = document.getElementById('oauthClientSecret').value.trim();
  const scope = document.getElementById('oauthPlayScope').value.trim();
  if (!clientId || secret.includes('******')) {
    document.getElementById('oauthPlayResult').innerHTML =
      `<span class="err">请先填写真实的 client_id / client_secret（打码值无法用于换取 token）</span>`;
    return;
  }
  const body = { grant_type: 'client_credentials', client_id: clientId, client_secret: secret };
  if (scope) body.scope = scope;
  try {
    const { status, data } = await rawFetch('/auth/oauth/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (status === 200 && data.access_token) {
      issuedToken = data.access_token;
      document.getElementById('oauthPlayToken').value = issuedToken;
      renderPlayResult(`✅ 签发成功（HTTP ${status}）`, data);
    } else {
      issuedToken = '';
      renderPlayResult(`❌ 签发失败（HTTP ${status}）`, data);
    }
  } catch (e) {
    renderPlayResult(`❌ 请求异常: ${e.message}`, null);
  }
}

function getPlayToken() {
  const t = document.getElementById('oauthPlayToken').value.trim();
  return t || issuedToken;
}

/** 2. 用 token 访问受保护资源 /me */
export async function callMe() {
  const token = getPlayToken();
  if (!token) {
    renderPlayResult(`⚠️ 请先点击「1. 签发 Token」获取 access_token`, null);
    return;
  }
  try {
    const { status, data } = await rawFetch('/auth/oauth/me', {
      headers: { 'Authorization': 'Bearer ' + token }
    });
    renderPlayResult(`GET /api/auth/oauth/me → HTTP ${status}`, data);
  } catch (e) {
    renderPlayResult(`❌ 请求异常: ${e.message}`, null);
  }
}

/** 3. 用 token 访问受保护资源 /protected/status（read scope） */
export async function callStatus() {
  const token = getPlayToken();
  if (!token) {
    renderPlayResult(`⚠️ 请先获取 access_token`, null);
    return;
  }
  try {
    const { status, data } = await rawFetch('/auth/oauth/protected/status', {
      headers: { 'Authorization': 'Bearer ' + token }
    });
    renderPlayResult(`GET /api/auth/oauth/protected/status → HTTP ${status}`, data);
  } catch (e) {
    renderPlayResult(`❌ 请求异常: ${e.message}`, null);
  }
}

/** 4. 用 token 调用 write 资源 /protected/echo */
export async function callEcho() {
  const token = getPlayToken();
  if (!token) {
    renderPlayResult(`⚠️ 请先获取 access_token`, null);
    return;
  }
  const payload = document.getElementById('oauthEchoBody').value || '{"hello":"修仙世界"}';
  try {
    const { status, data } = await rawFetch('/auth/oauth/protected/echo', {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + token,
        'Content-Type': 'application/json'
      },
      body: payload
    });
    renderPlayResult(`POST /api/auth/oauth/protected/echo → HTTP ${status}`, data);
  } catch (e) {
    renderPlayResult(`❌ 请求异常: ${e.message}`, null);
  }
}

function renderPlayResult(label, data) {
  const box = document.getElementById('oauthPlayResult');
  box.innerHTML = `<div class="play-label">${label}</div><pre>${JSON.stringify(data, null, 2)}</pre>`;
}

const API_BASE = '/api/admin';
let token = '';
let loginType = '';
let currentRole = '';
let currentUserId = null;
let currentUsername = '';
let currentPermissions = new Set();

// LocalStorage keys
const STORAGE_KEY_TOKEN = 'xiuxian_admin_token';
const STORAGE_KEY_USER = 'xiuxian_admin_user';

/** 从 localStorage 恢复 token（刷新/重新打开页面时使用） */
function restoreFromStorage() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY_USER);
    if (stored) {
      const user = JSON.parse(stored);
      token = user.token || '';
      loginType = user.loginType || '';
      currentRole = user.role || '';
      currentUserId = user.userId || null;
      currentUsername = user.username || '';
      if (user.permissions && Array.isArray(user.permissions)) {
        currentPermissions.clear();
        user.permissions.forEach(p => currentPermissions.add(p));
      }
    }
  } catch (e) {
    // 解析失败则清空
    localStorage.removeItem(STORAGE_KEY_TOKEN);
    localStorage.removeItem(STORAGE_KEY_USER);
    token = '';
  }
}

// 页面加载时尝试恢复 token
restoreFromStorage();

export function setAuth(newToken, type, role, permissions, userId, username) {
  token = newToken;
  loginType = type || '';
  currentRole = role || '';
  currentUserId = userId;
  currentUsername = username || '';
  currentPermissions.clear();
  if (permissions && Array.isArray(permissions)) {
    permissions.forEach(p => currentPermissions.add(p));
  }

  // 持久化到 localStorage
  localStorage.setItem(STORAGE_KEY_TOKEN, newToken);
  localStorage.setItem(STORAGE_KEY_USER, JSON.stringify({
    token: newToken,
    loginType: type || '',
    role: role || '',
    userId: userId || null,
    username: username || '',
    permissions: permissions || []
  }));
}

export function clearAuth() {
  token = '';
  loginType = '';
  currentRole = '';
  currentUserId = null;
  currentUsername = '';
  currentPermissions.clear();

  // 清除 localStorage 中的 token
  localStorage.removeItem(STORAGE_KEY_TOKEN);
  localStorage.removeItem(STORAGE_KEY_USER);
}

export function hasPerm(perm) {
  if (isAdmin()) return true;
  return currentPermissions.has(perm);
}

export function isAuthenticated() {
  return !!token;
}

export function isAdmin() {
  return loginType === 'admin';
}

export function isPlayer() {
  return loginType === 'player';
}

export function getRole() {
  return currentRole;
}

export function getUserId() {
  return currentUserId;
}

export function getUsername() {
  return currentUsername;
}

export function getToken() {
  return token;
}

function getHeaders() {
  const headers = {};
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  return headers;
}

export async function request(path, options = {}) {
  const opts = {
    ...options,
    headers: {
      ...getHeaders(),
      ...options.headers
    }
  };

  const response = await fetch(API_BASE + path, opts);
  
  if (response.status === 401) {
    clearAuth();
    // 不强制跳转，保持在当前页显示登录 overlay
    throw new Error('Unauthorized');
  }

  if (response.status === 204) {
    return {};
  }

  try {
    return await response.json();
  } catch {
    return { code: response.status, message: 'Invalid response' };
  }
}

export async function get(path) {
  return request(path, { method: 'GET' });
}

export async function post(path, body) {
  return request(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

export async function put(path, body) {
  return request(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

export async function del(path) {
  return request(path, { method: 'DELETE' });
}

export async function download(path) {
  const response = await fetch(API_BASE + path, { headers: getHeaders() });
  if (response.status === 401) {
    clearAuth();
    throw new Error('Unauthorized');
  }
  if (!response.ok) {
    throw new Error('Download failed');
  }
  return response;
}
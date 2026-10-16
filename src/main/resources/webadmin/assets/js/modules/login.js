import * as api from '../api.js';
import * as layout from './layout.js';

export async function login() {
  const username = document.getElementById('usr').value.trim();
  const password = document.getElementById('pwd').value;
  const loginType = document.getElementById('loginType').value;
  const errorEl = document.getElementById('loginError');

  if (!username || !password) {
    errorEl.textContent = '请输入用户名和密码';
    return;
  }

  try {
    const response = await api.post('/login', { username, password, loginType });
    
    if (response.token) {
      api.setAuth(response.token, response.loginType, response.highestRole, response.permissions, response.userId, response.username);
      document.getElementById('loginOverlay').style.display = 'none';
      errorEl.textContent = '';
      window.app.init();
    } else {
      errorEl.textContent = response.message || '登录失败';
    }
  } catch (error) {
    errorEl.textContent = '网络错误';
  }
}

export function logout() {
  layout.stopTimers();  // 登出后停止所有轮询，避免无 token 空转 /status
  api.clearAuth();
  const userLabel = document.getElementById('currentUserLabel');
  if (userLabel) userLabel.textContent = '未登录';
  document.getElementById('loginOverlay').style.display = 'flex';
  document.getElementById('usr').value = '';
  document.getElementById('pwd').value = '';
  document.getElementById('loginType').value = 'admin';
  document.getElementById('loginTypeAdmin').checked = true;
}

export function initLoginForm() {
  document.getElementById('pwd').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      login();
    }
  });
  
  document.querySelectorAll('input[name="loginType"]').forEach(el => {
    el.addEventListener('change', (e) => {
      document.getElementById('loginType').value = e.target.value;
      const hintEl = document.getElementById('loginHint');
      const subtitleEl = document.querySelector('.subtitle');
      if (e.target.value === 'admin') {
        subtitleEl.textContent = '管 理 后 台';
        hintEl.textContent = '管理员可管理整个系统';
      } else {
        subtitleEl.textContent = '玩 家 中 心';
        hintEl.textContent = '玩家仅可查看自己的角色信息';
      }
    });
  });
}
/**
 * QQ 官方机器人的「网页绑定」落地页。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>机器人在群里发一条 {@code /admin/?qq_bind=<token>}；</li>
 *   <li>本模块读 URL 上的 token，调 {@code GET /api/auth/qq-bind/info} 拿到
 *       「绑定还是注册 / 要不要邮箱」；</li>
 *   <li>玩家填密码（或邮箱 + 验证码），提交到 {@code POST /api/auth/qq-bind/confirm}；</li>
 *   <li>成功后展示「已绑定，回 QQ 继续」。</li>
 * </ol>
 *
 * <h3>为什么不复用 api.js</h3>
 * <p>{@code api.js} 的 {@code API_BASE} 是 {@code /api/admin}，且会自动带上
 * localStorage 里的管理员 token。绑定页是给<b>还没绑定账号的玩家</b>用的，
 * 他可能根本没有 token；而且绑定成功也不该让他顺带获得后台会话。
 * 所以这里单独 {@code fetch('/api/auth/qq-bind/...')}，不写 localStorage。</p>
 *
 * <h3>票据即凭据</h3>
 * <p>token 单次、10 分钟、只存哈希。页面不缓存它、不写 localStorage、
 * 不放进任何日志；提交成功后 URL 上的 token 就作废了。</p>
 */

const API = '/api/auth/qq-bind';

let token = null;
let info = null;

/** URL 上是否带有绑定票据（决定是否切到绑定页） */
export function hasBindRequest() {
  return new URLSearchParams(window.location.search).has('qq_bind');
}

/** 从当前 URL 取出票据 */
export function readToken() {
  return new URLSearchParams(window.location.search).get('qq_bind') || '';
}

/** 进入绑定页：隐藏登录框，渲染表单 */
export async function init() {
  token = readToken();
  document.getElementById('loginOverlay').style.display = 'none';
  const overlay = document.getElementById('qqBindOverlay');
  overlay.style.display = 'flex';

  bindEvents();

  try {
    const resp = await fetch(API + '/info?token=' + encodeURIComponent(token));
    info = await resp.json();
    if (info.code !== 200) {
      showError(info.message || '链接已失效，请回 QQ 重新发送 /绑定');
      disableForm();
      return;
    }
    render();
  } catch (e) {
    showError('网络错误，请稍后重试');
    disableForm();
  }
}

function bindEvents() {
  document.querySelectorAll('input[name="qqBindMode"]').forEach(el => {
    el.addEventListener('change', () => render());
  });
  document.getElementById('qqBindSendCodeBtn').addEventListener('click', sendCode);
  document.getElementById('qqBindSubmitBtn').addEventListener('click', confirm);
  document.getElementById('qqBindPwd').addEventListener('keydown', e => {
    if (e.key === 'Enter') confirm();
  });
  document.getElementById('qqBindCode').addEventListener('keydown', e => {
    if (e.key === 'Enter') confirm();
  });
}

/** 按 info 渲染表单：绑定/注册、密码/邮箱、要不要邮箱 */
function render() {
  const isRegister = info.intent === 'REGISTER';
  const emailEnabled = !!info.emailEnabled;
  const emailRequired = !!info.emailRequired;

  document.getElementById('qqBindSubtitle').textContent =
    isRegister ? '注 册 角 色' : 'Q Q 账 号 绑 定';
  document.getElementById('qqBindSubmitBtn').textContent =
    isRegister ? '注 册' : '绑 定';
  document.getElementById('qqBindHint').textContent =
    isRegister ? '注册成功后回到 QQ 机器人即可开始游戏' : '完成后回到 QQ 机器人即可继续游戏';

  // 注册：角色名固定（在聊天里已确认），不给改
  const fixedName = document.getElementById('qqBindFixedName');
  if (isRegister) {
    fixedName.style.display = 'block';
    document.getElementById('qqBindFixedNameValue').textContent = info.username || '';
  } else {
    fixedName.style.display = 'none';
  }

  // 模式切换：仅绑定时可选；注册固定密码（邮箱是否必填由 emailRequired 决定）
  const modeSwitch = document.getElementById('qqBindModeSwitch');
  modeSwitch.style.display = isRegister ? 'none' : 'flex';
  const emailRadio = document.getElementById('qqBindModeEmail');
  emailRadio.disabled = !emailEnabled;
  if (!emailEnabled && emailRadio.checked) {
    document.getElementById('qqBindModePassword').checked = true;
  }

  const mode = isRegister ? 'password' : currentMode();
  const showEmail = isRegister ? emailRequired : (mode === 'email');

  // 用户名：仅绑定 + 密码模式
  document.getElementById('qqBindUsernameGroup').style.display =
    (!isRegister && mode === 'password') ? 'block' : 'none';
  // 密码：注册始终显示；绑定仅密码模式显示（邮箱验证码模式不需要密码）
  const showPassword = isRegister || mode === 'password';
  document.getElementById('qqBindPasswordGroup').style.display = showPassword ? 'block' : 'none';
  // 邮箱 + 验证码：按上面算出的 showEmail
  document.getElementById('qqBindEmailGroup').style.display = showEmail ? 'block' : 'none';
  document.getElementById('qqBindCodeGroup').style.display = showEmail ? 'block' : 'none';
  document.getElementById('qqBindSendCodeBtn').style.display = showEmail ? 'block' : 'none';

  clearError();
}

function currentMode() {
  const checked = document.querySelector('input[name="qqBindMode"]:checked');
  return checked ? checked.value : 'password';
}

/** 发送验证码：无论邮箱有没有注册，后端都回同一句话（防枚举） */
async function sendCode() {
  const email = document.getElementById('qqBindEmail').value.trim();
  if (!email) {
    showError('请先输入邮箱');
    return;
  }
  const btn = document.getElementById('qqBindSendCodeBtn');
  btn.disabled = true;
  btn.textContent = '发 送 中…';
  try {
    const resp = await fetch(API + '/send-code', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, email })
    });
    const data = await resp.json();
    if (data.code === 200) {
      showError(''); // 清空错误区，用 hint 提示
      document.getElementById('qqBindHint').textContent = data.message || '验证码已发送，请查收邮件';
    } else {
      showError(data.message || '发送失败');
    }
  } catch (e) {
    showError('网络错误，请稍后重试');
  } finally {
    btn.disabled = false;
    btn.textContent = '发 送 验 证 码';
  }
}

/** 提交绑定 / 注册 */
async function confirm() {
  const isRegister = info && info.intent === 'REGISTER';
  const mode = isRegister ? 'password' : currentMode();
  const body = { token, mode };

  if (!isRegister && mode === 'password') {
    body.username = document.getElementById('qqBindUsr').value.trim();
    body.password = document.getElementById('qqBindPwd').value;
    if (!body.username || !body.password) {
      showError('请输入用户名和密码');
      return;
    }
  } else if (!isRegister && mode === 'email') {
    body.email = document.getElementById('qqBindEmail').value.trim();
    body.code = document.getElementById('qqBindCode').value.trim();
    if (!body.email || !body.code) {
      showError('请输入邮箱和验证码');
      return;
    }
  } else {
    // 注册：密码必填；邮箱按 emailRequired
    body.password = document.getElementById('qqBindPwd').value;
    if (!body.password) {
      showError('请输入密码');
      return;
    }
    if (info.emailRequired) {
      body.email = document.getElementById('qqBindEmail').value.trim();
      body.code = document.getElementById('qqBindCode').value.trim();
      if (!body.email || !body.code) {
        showError('请输入邮箱和验证码');
        return;
      }
    }
  }

  const btn = document.getElementById('qqBindSubmitBtn');
  btn.disabled = true;
  btn.textContent = '提 交 中…';
  try {
    const resp = await fetch(API + '/confirm', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const data = await resp.json();
    if (data.code === 200) {
      showSuccess(data.message || '绑定成功');
    } else {
      showError(data.message || '操作失败');
      btn.disabled = false;
      btn.textContent = isRegister ? '注 册' : '绑 定';
    }
  } catch (e) {
    showError('网络错误，请稍后重试');
    btn.disabled = false;
    btn.textContent = isRegister ? '注 册' : '绑 定';
  }
}

function showSuccess(message) {
  const overlay = document.getElementById('qqBindOverlay');
  overlay.innerHTML = `
    <div class="login-box">
      <div class="logo">✅</div>
      <h2>修仙世界</h2>
      <div class="subtitle">完 成</div>
      <div class="login-hint" style="font-size:14px;color:var(--t3);margin-top:8px">${escapeHtml(message)}</div>
      <div class="login-hint">现在可以关闭本页，回到 QQ 机器人继续游戏。</div>
    </div>`;
}

function showError(message) {
  document.getElementById('qqBindError').textContent = message || '';
}

function clearError() {
  showError('');
}

function disableForm() {
  ['qqBindUsr', 'qqBindPwd', 'qqBindEmail', 'qqBindCode', 'qqBindSubmitBtn', 'qqBindSendCodeBtn']
    .forEach(id => {
      const el = document.getElementById(id);
      if (el) el.disabled = true;
    });
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

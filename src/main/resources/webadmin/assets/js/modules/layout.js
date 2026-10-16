import * as api from '../api.js';

const TAB_TITLES = {
  overview: '📊 总览',
  players: '👥 玩家管理',
  map: '🗺️ 地图管理',
  database: '🗄️ 数据库',
  users: '🔑 用户/角色',
  logs: '📜 实时日志',
  traces: '👣 玩家轨迹',
  redeemCodes: '🎫 兑换码',
  titles: '🏆 称号管理',
  market: '🏪 坊市管理',
  statsMessages: '💬 消息统计',
  statsCommands: '⚡ 指令统计',
  myProfile: '👤 我的信息',
  adapters: '🔌 适配器管理',
  plugins: '🧩 插件管理',
  oauth: '🔐 OAuth2 管理'
};

const ADMIN_TABS = ['overview', 'players', 'map', 'database', 'users', 'logs', 'traces', 'redeemCodes', 'titles', 'market', 'statsMessages', 'statsCommands', 'adapters', 'plugins', 'oauth'];
const PLAYER_TABS = ['myProfile'];

/** 标签页 → 加载函数（统一分发，替代重复的 switch） */
const TAB_LOADERS = {
  overview: () => window.app.modules.overview.load(),
  players: () => window.app.modules.players.load(),
  map: () => window.app.modules.map.load(),
  users: () => window.app.modules.users.load(),
  database: () => window.app.modules.database.loadTables(),
  traces: () => window.app.modules.traces.load(),
  redeemCodes: () => window.app.modules.redeemCodes.load(),
  titles: () => window.app.modules.titles.load(),
  market: () => window.app.modules.market.load(),
  statsMessages: () => window.app.modules.stats.loadMessageStats(),
  statsCommands: () => window.app.modules.stats.loadCommandStats(),
  myProfile: () => window.app.modules.myProfile.load(),
  adapters: () => window.app.modules.adapters.load(),
  plugins: () => window.app.modules.plugins.load(),
  oauth: () => window.app.modules.oauth.load()
};

/** 需要轮询刷新的标签页 → 间隔（毫秒） */
const POLL_MS = {
  overview: 8000,
  players: 8000,
  users: 8000,
  traces: 5000,
  redeemCodes: 8000,
  database: 8000
};

let currentTab = 'overview';
let statusTimer = null;
let logsTimer = null;
let dataTimer = null;

export function getCurrentTab() {
  return currentTab;
}

export function switchTab(name) {
  currentTab = name;

  document.getElementById('pageTitle').textContent = TAB_TITLES[name] || name;

  document.querySelectorAll('.nav-item').forEach(item => {
    item.classList.remove('active');
  });

  const targetItem = document.querySelector(`[data-tab="${name}"]`);
  if (targetItem) {
    targetItem.classList.add('active');
  }

  document.querySelectorAll('.tab').forEach(tab => {
    tab.classList.remove('active');
  });

  const targetTab = document.getElementById(`tab-${name}`);
  if (targetTab) {
    targetTab.classList.add('active');
  }

  clearTimers();

  // 实时日志特殊处理（带初始化与独立高频轮询）
  if (name === 'logs') {
    window.app.modules.logs.init();
    logsTimer = setInterval(() => window.app.modules.logs.fetchLogs(), 500);
    return;
  }

  const loader = TAB_LOADERS[name];
  if (loader) loader();

  const ms = POLL_MS[name];
  if (ms) dataTimer = setInterval(loader, ms);
}

function clearTimers() {
  // 状态栏（uptime/online/mem）定时器全局保留，切标签页不清除
  if (logsTimer) {
    clearInterval(logsTimer);
    logsTimer = null;
  }
  if (dataTimer) {
    clearInterval(dataTimer);
    dataTimer = null;
  }
}

/** 停止全部轮询（登出 / 管理员切换到玩家 / 重新登录时调用，防止旧会话定时器残留）。 */
export function stopTimers() {
  if (statusTimer) {
    clearInterval(statusTimer);
    statusTimer = null;
  }
  if (logsTimer) {
    clearInterval(logsTimer);
    logsTimer = null;
  }
  if (dataTimer) {
    clearInterval(dataTimer);
    dataTimer = null;
  }
}

/** 启动全局状态栏刷新（只调用一次，切标签页不清除） */
function ensureStatusTimer() {
  // 玩家没有 admin.status 权限；未登录也没有 token——一律不轮询 /status
  if (api.isPlayer() || !api.isAuthenticated()) return;
  if (!statusTimer) {
    statusTimer = setInterval(fetchStatus, 2000);
    fetchStatus();
  }
}

export async function fetchStatus() {
  if (api.isPlayer() || !api.isAuthenticated()) return;
  try {
    const data = await api.get('/status');
    document.getElementById('uptime').textContent = data.uptimeFormatted;
    document.getElementById('online').textContent = data.onlineCount;
    document.getElementById('mem').textContent = data.usedMemoryMB + ' MB';
    document.getElementById('connDot').className = 'conn-dot on';
  } catch (e) {
    document.getElementById('connDot').className = 'conn-dot off';
    // token 失效（401）时，回到登录界面
    if (!api.isAuthenticated()) {
      document.getElementById('loginOverlay').style.display = 'flex';
    }
  }
}

export function updateSidebarForRole() {
  const allowedTabs = api.isAdmin() ? ADMIN_TABS : PLAYER_TABS;
  
  document.querySelectorAll('.nav-item').forEach(item => {
    const tabName = item.getAttribute('data-tab');
    if (!allowedTabs.includes(tabName)) {
      item.style.display = 'none';
    } else {
      item.style.display = 'block';
    }
  });

  // 左下角显示当前登录用户名（旧会话无 username 时回退角色文案）
  const userLabel = document.getElementById('currentUserLabel');
  if (userLabel) {
    userLabel.textContent = api.getUsername() || (api.isAdmin() ? '管理员' : '玩家');
  }
  
  if (api.isPlayer()) {
    const sidebarTitle = document.querySelector('.sidebar-header h2');
    if (sidebarTitle) sidebarTitle.textContent = '玩家中心';
    
    const statusBar = document.querySelector('.status-bar');
    if (statusBar) statusBar.style.display = 'none';
  }
}

export function initLayout() {
  updateSidebarForRole();
  
  // 状态栏（uptime/online/mem）仅管理员需要；玩家没有 admin.status 权限，轮询只会徒增 401/403
  if (!api.isPlayer()) {
    ensureStatusTimer();
  }
  
  document.querySelectorAll('.nav-item').forEach(item => {
    item.addEventListener('click', (e) => {
      const tab = e.currentTarget.getAttribute('data-tab');
      if (tab) {
        switchTab(tab);
      }
    });
  });
  
  document.getElementById('modalOverlay').addEventListener('click', (e) => {
    if (e.target === document.getElementById('modalOverlay')) {
      window.app.utils.closeModal();
    }
  });
}
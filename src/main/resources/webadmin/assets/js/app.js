import * as api from './api.js';
import * as utils from './utils.js';
import * as login from './modules/login.js';
import * as layout from './modules/layout.js';
import * as overview from './modules/overview.js';
import * as players from './modules/players.js';
import * as map from './modules/map.js';
import * as database from './modules/database.js';
import * as users from './modules/users.js';
import * as logs from './modules/logs.js';
import * as traces from './modules/traces.js';
import * as redeemCodes from './modules/redeemCodes.js';
import * as titles from './modules/titles.js';
import * as market from './modules/market.js';
import * as stats from './modules/stats.js';
import * as myProfile from './modules/myProfile.js';
import * as adapters from './modules/adapters.js';
import * as plugins from './modules/plugins.js';
import * as oauth from './modules/oauth.js';

window.app = {
  api,
  utils,
  modules: {
    login,
    layout,
    overview,
    players,
    map,
    database,
    users,
    logs,
    traces,
    redeemCodes,
    titles,
    market,
    stats,
    myProfile,
    adapters,
    plugins,
    oauth
  },
  
  init() {
    login.initLoginForm();
    // 清掉上一会话（例如管理员）遗留的状态轮询，避免切换角色/重登后仍打 /status
    layout.stopTimers();
    layout.initLayout();
    layout.updateSidebarForRole();
    if (api.isPlayer()) {
      layout.switchTab('myProfile');
    } else {
      // 统一走 switchTab，确保总览页自动刷新定时器生效
      logs.initScrollListener();
      layout.switchTab('overview');
    }
  },

  autoLogin() {
    // 从 localStorage 恢复的 token 存在即自动进入应用
    if (api.isAuthenticated()) {
      document.getElementById('loginOverlay').style.display = 'none';
      this.init();
      return true;
    }
    return false;
  },
  
  login() {
    login.login();
  },
  
  logout() {
    login.logout();
  },
  
  switchTab(tabName) {
    layout.switchTab(tabName);
  }
};

// 页面加载：先尝试用已保存的 token 自动登录；否则停在登录界面
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    window.app.autoLogin();
  });
} else {
  window.app.autoLogin();
}
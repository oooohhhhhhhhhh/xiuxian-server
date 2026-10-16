import * as api from '../api.js';
import * as utils from '../utils.js';

/**
 * 坊市管理：挂单总览 + 成交行情查询。
 *
 * 只读视图。走管理台权限（admin.economy）而非玩家权限，
 * 因此交易玩法插件停用后仍可查看存量挂单与成交历史。
 */

/** 剩余时间文案（服务端已给 remainingText，这里仅做兜底格式化）。 */
function remainingText(listing) {
  if (listing.remainingText) return listing.remainingText;
  const ms = listing.remainingMs;
  if (ms === undefined || ms === null || ms < 0) return '永不过期';
  if (ms <= 0) return '已到期';
  const totalMinutes = Math.floor(ms / 60000);
  const days = Math.floor(totalMinutes / (60 * 24));
  const hours = Math.floor(totalMinutes / 60) % 24;
  const minutes = totalMinutes % 60;
  if (days > 0) return days + '天' + hours + '小时';
  if (hours > 0) return hours + '小时' + minutes + '分钟';
  return Math.max(1, minutes) + '分钟';
}

/** 剩余时间越少越红，便于管理员快速发现即将到期的挂单。 */
function remainingColor(listing) {
  const ms = listing.remainingMs;
  if (ms === undefined || ms === null || ms < 0) return 'color:var(--text-muted)';
  if (ms <= 3600000) return 'color:#ef4444';        // 1 小时内
  if (ms <= 24 * 3600000) return 'color:#f59e0b';   // 1 天内
  return 'color:var(--text-secondary)';
}

export async function load() {
  await loadListings();
  await loadPriceHistory();
}

export async function loadListings() {
  const query = (document.getElementById('marketSellerSearch').value || '').trim();

  utils.showLoading('marketTable', '加载中...');
  try {
    const resp = await api.get('/market/listings');
    const data = resp.data || {};
    let listings = data.listings || [];

    if (query) {
      const q = query.toLowerCase();
      listings = listings.filter(l =>
        String(l.sellerName || '').toLowerCase().includes(q) ||
        String(l.sellerPlayerId || '').includes(q) ||
        String(l.itemName || '').toLowerCase().includes(q) ||
        String(l.itemKey || '').toLowerCase().includes(q)
      );
    }

    // 概览统计
    const totalValue = listings.reduce((sum, l) => sum + (l.priceSpiritStones || 0), 0);
    const totalQty = listings.reduce((sum, l) => sum + (l.quantity || 0), 0);
    document.getElementById('marketSummary').innerHTML =
      utils.createStatCard('在售挂单', listings.length + ' 条', query ? '筛选后' : '全部') +
      utils.createStatCard('挂单物品总数', utils.formatNumber(totalQty)) +
      utils.createStatCard('挂单总标价', utils.formatNumber(totalValue) + ' 灵石') +
      utils.createStatCard('挂单时限', (data.listingDurationHours ?? '--') + ' 小时',
        '成交历史保留 ' + (data.priceHistoryKeepDays ?? '--') + ' 天');

    if (listings.length === 0) {
      utils.showEmpty('marketTable', '🏪', query ? '没有匹配的挂单' : '坊市暂无在售挂单');
      return;
    }

    let html = '<table><tr><th>ID</th><th>卖家</th><th>物品</th><th>数量</th><th>标价</th>' +
      '<th>单价</th><th>手续费</th><th>剩余时间</th><th>上架时间</th></tr>';
    for (const l of listings) {
      html += `
        <tr>
          <td style="color:var(--text-muted);font-size:11px;font-family:monospace">${utils.escapeHtml(l.id)}</td>
          <td style="color:var(--gold)">${utils.escapeHtml(l.sellerName || ('#' + l.sellerPlayerId))}
            <div style="color:var(--text-muted);font-size:10px">UID ${utils.escapeHtml(l.sellerPlayerId)}</div>
          </td>
          <td>${utils.escapeHtml(l.itemName || l.itemKey)}
            <div style="color:var(--text-muted);font-size:10px;font-family:monospace">${utils.escapeHtml(l.itemKey)}</div>
          </td>
          <td>${utils.escapeHtml(l.quantity)}</td>
          <td style="color:var(--gold)">${utils.escapeHtml(utils.formatNumber(l.priceSpiritStones))}</td>
          <td>${utils.escapeHtml(l.unitPrice)}</td>
          <td style="color:var(--text-muted)">${utils.escapeHtml(l.fee)}</td>
          <td style="${remainingColor(l)}">${utils.escapeHtml(remainingText(l))}</td>
          <td style="color:var(--text-muted);font-size:11px">${utils.escapeHtml(utils.formatTime(l.createdAt))}</td>
        </tr>
      `;
    }
    html += '</table>';
    document.getElementById('marketTable').innerHTML = html;
  } catch (e) {
    utils.showEmpty('marketTable', '🏪', '加载失败: ' + (e.message || ''));
  }
}

export async function loadPriceHistory() {
  const itemKey = (document.getElementById('marketPriceItem').value || '').trim();
  if (!itemKey) {
    document.getElementById('marketPriceResult').innerHTML =
      utils.createEmptyState('📈', '输入物品 Key 或名称后查询成交行情');
    return;
  }

  const daysStr = (document.getElementById('marketPriceDays').value || '').trim();
  let days = 0;
  if (daysStr) {
    const parsed = parseInt(daysStr, 10);
    if (!isNaN(parsed)) days = parsed;
  }

  utils.showLoading('marketPriceResult', '查询中...');
  try {
    let url = '/market/price?itemKey=' + encodeURIComponent(itemKey);
    if (days > 0) url += '&days=' + days;
    const resp = await api.get(url);
    const d = resp.data || {};

    if (resp.code !== 200) {
      document.getElementById('marketPriceResult').innerHTML =
        utils.createEmptyState('📈', resp.message || '查询失败');
      return;
    }

    const tradeCount = Number(d.tradeCount || 0);
    let html = '<div class="stats-grid">' +
      utils.createStatCard('物品', d.itemName || d.itemKey || itemKey, d.itemKey || '') +
      utils.createStatCard('统计窗口', (d.days ?? '--') + ' 天') +
      utils.createStatCard('成交笔数', tradeCount) +
      utils.createStatCard('成交总量', utils.formatNumber(d.totalQuantity || 0));
    if (tradeCount > 0) {
      html += utils.createStatCard('成交均价', utils.formatNumber(d.avgUnitPrice) + ' /个') +
        utils.createStatCard('最低单价', utils.formatNumber(d.minUnitPrice) + ' /个') +
        utils.createStatCard('最高单价', utils.formatNumber(d.maxUnitPrice) + ' /个');
    }
    if (d.lowestActiveUnitPrice !== undefined && d.lowestActiveUnitPrice !== null) {
      html += utils.createStatCard('当前在售最低', utils.formatNumber(d.lowestActiveUnitPrice) + ' /个', '定价锚点');
    }
    html += '</div>';

    if (tradeCount === 0) {
      html += `<div style="margin-top:12px;color:var(--text-muted);font-size:12px">
        ⚠️ 该物品近期无成交记录，统计值仅供参考</div>`;
    }
    document.getElementById('marketPriceResult').innerHTML = html;
  } catch (e) {
    utils.showEmpty('marketPriceResult', '📈', '查询失败: ' + (e.message || ''));
  }
}

/** 从挂单表格点物品名快速回填行情查询框。 */
export function fillPriceQuery(itemKey) {
  document.getElementById('marketPriceItem').value = itemKey || '';
  document.getElementById('marketPriceDays').value = '7';
  loadPriceHistory();
}

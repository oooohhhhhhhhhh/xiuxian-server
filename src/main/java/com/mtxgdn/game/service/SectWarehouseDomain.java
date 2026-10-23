package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.entity.SectWarehouseItem;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.i18n.I18n;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宗门仓库域：捐献、取出、列表，以及"物品折多少贡献值"的定价。
 *
 * <p>仓库是宗门里唯一<b>双向</b>流动物品的地方（进：弟子捐献；出：弟子取用），
 * 因此它自己就带一整套反套利口径 —— 捐进来的贡献按物品价格/10 折算，取出去
 * 按价格/5 计费（{@link #getItemContributionCost}），并且管理职位捐献不再发贡献值
 * （其特权已是免费取物）。这几条互相咬合，必须待在同一个类里才看得懂，
 * 所以它们一起从 {@code SectService} 搬到了这里。</p>
 *
 * <p>方法体、SQL、异常文案、{@code Map} 里 key 的顺序都与拆分前逐字相同。
 * 特别保留了两处方言的 {@code ON CONFLICT / ON DUPLICATE KEY} 分支选择。</p>
 *
 * <p><b>事务边界</b>：捐献（扣背包 + 入仓库 + 加贡献 + 加声望）与取出
 * （扣贡献 + 减仓库 + 发背包）原本就各是一个 {@code runTransaction}，
 * 拆分时两个事务块整体搬了过来，块内只使用事务传入的 {@code conn}
 * （背包增减走 {@code itemService} 的 {@code conn} 重载），不会在事务里再开连接。
 * {@link #getItemContributionCost} 是纯函数（只读 {@code Item} 对象），
 * 因此可以安全地在事务块内被调用 —— 这是它必须保持纯函数的原因。</p>
 */
final class SectWarehouseDomain {

    private final ItemService itemService;
    private final SectQueryDomain queryDomain;

    SectWarehouseDomain(ItemService itemService, SectQueryDomain queryDomain) {
        this.itemService = itemService;
        this.queryDomain = queryDomain;
    }

    // ==================== 仓库系统 ====================

    List<SectWarehouseItem> getWarehouse(long sectId) {
        String sql = "SELECT sw.*, p.name AS donated_by_name FROM sect_warehouse sw " +
                "LEFT JOIN players p ON sw.donated_by_player_id = p.id " +
                "WHERE sw.sect_id = ? AND sw.quantity > 0 ORDER BY sw.created_at";
        List<SectWarehouseItem> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(SectSupport.mapWarehouseItem(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门仓库失败", e); }
        return result;
    }

    Map<String, Object> donateToWarehouse(long playerId, long sectId, String itemKey, int quantity) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = queryDomain.getMember(sectId, playerId);
        if (member == null) {
            result.put("success", false); result.put("message", I18n.t("sect.you_are_not_a_member")); return result;
        }

        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            result.put("success", false); result.put("message", I18n.t("sect.item_does_not_exist")); return result;
        }
        String fullKey = item.getFullKey();
        if (quantity <= 0) {
            result.put("success", false); result.put("message", I18n.t("sect.the_amount_must_be_greater")); return result;
        }
        if (!itemService.hasItem(playerId, fullKey, quantity)) {
            result.put("success", false); result.put("message", I18n.t("sect.not_enough_in_your_backpack", item.getName())); return result;
        }

        // 修复刷贡献 bug：管理职位（宗主/副宗主/长老）可以免费从仓库取出物品，
        // 若捐献时仍发放贡献值，则取出→捐献→取出无限循环即可刷取海量贡献值。
        // 因此管理职位捐献不再获得贡献值（其特权已是免费取物），仅普通弟子捐献获得贡献值。
        // 贡献值按物品价格折算（约为取出成本的 1/3~1/2），保证"捐低价物换高价物"无利可图。
        final boolean isManager = member.canManage();
        final long contributionGain = isManager ? 0L : quantity * Math.max(2L, item.getPrice() / 10L);

        DatabaseManager.runTransaction(conn -> {
            itemService.removeItem(conn, playerId, fullKey, quantity);
            String upsertSql;
            if (DatabaseManager.isSqlite()) {
                upsertSql = "INSERT INTO sect_warehouse (sect_id, item_key, quantity, donated_by_player_id) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT(sect_id, item_key) DO UPDATE SET quantity = quantity + ?, donated_by_player_id = ?";
            } else {
                upsertSql = "INSERT INTO sect_warehouse (sect_id, item_key, quantity, donated_by_player_id) VALUES (?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE quantity = quantity + ?, donated_by_player_id = ?";
            }
            try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                ps.setLong(1, sectId);
                ps.setString(2, fullKey);
                ps.setInt(3, quantity);
                ps.setLong(4, playerId);
                ps.setInt(5, quantity);
                ps.setLong(6, playerId);
                ps.executeUpdate();
            }
            // 仅普通弟子捐献发放贡献值
            if (contributionGain > 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE sect_members SET contribution = contribution + ? WHERE sect_id = ? AND player_id = ?")) {
                    ps.setLong(1, contributionGain);
                    ps.setLong(2, sectId);
                    ps.setLong(3, playerId);
                    ps.executeUpdate();
                }
            }
            // 任何成员捐献都小幅增加宗门声望（声望是宗门升级/宗门战的资源，此前无来源导致死锁）
            long prestigeGain = Math.max(1L, quantity / 10L);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE sects SET prestige = prestige + ? WHERE id = ?")) {
                ps.setLong(1, prestigeGain);
                ps.setLong(2, sectId);
                ps.executeUpdate();
            }
            return null;
        });

        result.put("success", true);
        if (isManager) {
            result.put("message", I18n.t("sect.donated_x_to_the_sect", item.getName(), quantity));
        } else {
            result.put("message", I18n.t("sect.donated_x_to_the_sect_2", item.getName(), quantity, contributionGain));
        }
        result.put("contributionGain", contributionGain);
        return result;
    }

    Map<String, Object> withdrawFromWarehouse(long playerId, long sectId, String itemKey, int quantity) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = queryDomain.getMember(sectId, playerId);
        if (member == null) {
            result.put("success", false); result.put("message", I18n.t("sect.you_are_not_a_member")); return result;
        }
        if (quantity <= 0) {
            result.put("success", false); result.put("message", I18n.t("sect.the_amount_must_be_greater")); return result;
        }

        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            result.put("success", false); result.put("message", I18n.t("sect.item_does_not_exist")); return result;
        }
        String fullKey = item.getFullKey();

        int available = getWarehouseItemCount(sectId, fullKey);
        if (available < quantity) {
            result.put("success", false);
            result.put("message", I18n.t("sect.not_enough_in_the_sect", item.getName(), available)); return result;
        }

        boolean isManager = member.canManage();
        if (!isManager) {
            long costPerItem = getItemContributionCost(item);
            long totalCost = costPerItem * quantity;
            if (member.getContribution() < totalCost) {
                result.put("success", false);
                result.put("message", I18n.t("sect.not_enough_contribution", item.getName(), quantity, totalCost, member.getContribution()));
                return result;
            }
        }

        // 单事务 + 条件扣除：贡献值、仓库数量、背包发放原子完成，防止并发超取/重复取物
        try {
            DatabaseManager.runTransaction(conn -> {
                if (!isManager) {
                    long totalCost = getItemContributionCost(item) * quantity;
                    String costSql = "UPDATE sect_members SET contribution = contribution - ? WHERE id = ? AND contribution >= ?";
                    try (PreparedStatement ps = conn.prepareStatement(costSql)) {
                        ps.setLong(1, totalCost);
                        ps.setLong(2, member.getId());
                        ps.setLong(3, totalCost);
                        if (ps.executeUpdate() == 0) {
                            throw new SQLException("贡献值不足");
                        }
                    }
                }

                String deductSql = "UPDATE sect_warehouse SET quantity = quantity - ? WHERE sect_id = ? AND item_key = ? AND quantity >= ?";
                try (PreparedStatement ps = conn.prepareStatement(deductSql)) {
                    ps.setInt(1, quantity);
                    ps.setLong(2, sectId);
                    ps.setString(3, fullKey);
                    ps.setInt(4, quantity);
                    if (ps.executeUpdate() == 0) {
                        throw new SQLException("仓库物品数量不足");
                    }
                }

                if (!itemService.addItem(conn, playerId, fullKey, quantity)) {
                    throw new SQLException("发放物品失败");
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            // "未知错误" 保留在代码里当兜底，不走词条：
            // 它只在异常消息为空时出现，属于"连系统都说不清"的例外情况
            result.put("message", I18n.t("sect.withdraw.failed",
                    e.getMessage() != null ? e.getMessage() : "未知错误"));
            return result;
        }

        String msg;
        if (isManager) {
            msg = "已从宗门仓库取出【" + item.getName() + "】×" + quantity;
        } else {
            long cost = getItemContributionCost(item) * quantity;
            msg = "已从宗门仓库取出【" + item.getName() + "】×" + quantity + "，消耗贡献值 " + cost;
        }
        result.put("success", true);
        result.put("message", msg);
        return result;
    }

    /**
     * 物品兑换贡献值（取价格/5，最低10）。
     * 高于捐献所得（价格/10），保证"捐献→取出"循环总是亏损，杜绝套利。
     */
    long getItemContributionCost(Item item) {
        int price = item.getPrice();
        return Math.max(10, price / 5);
    }

    private int getWarehouseItemCount(long sectId, String itemKey) {
        String sql = "SELECT quantity FROM sect_warehouse WHERE sect_id = ? AND item_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("quantity");
            }
        } catch (SQLException e) { throw new RuntimeException("查询仓库物品失败", e); }
        return 0;
    }
}

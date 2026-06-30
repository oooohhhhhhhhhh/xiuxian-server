package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经济总控服务：签到、回收、兑换、商店、灵石修炼加速、经济数据面板。
 */
public class EconomyService {

    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();

    // ==================== 签到系统 ====================

    /** 7 天签到奖励（按稀有度和数量递进） */
    private static final String[][] SIGN_IN_REWARDS = {
        {"灵石 x50", "mtxgdn:spirit_stone", "50"},
        {"灵石 x80 + 灵草 x2", "mtxgdn:spirit_stone", "80", "mtxgdn:spirit_grass", "2"},
        {"灵石 x120 + 铁矿石 x3", "mtxgdn:spirit_stone", "120", "mtxgdn:iron_ore", "3"},
        {"灵石 x160 + 回血丹 x2", "mtxgdn:spirit_stone", "160", "mtxgdn:healing_pill", "2"},
        {"灵石 x200 + 强化石 x1", "mtxgdn:spirit_stone", "200", "mtxgdn:enhance_stone", "1"},
        {"灵石 x260 + 回蓝丹 x2", "mtxgdn:spirit_stone", "260", "mtxgdn:mana_pill", "2"},
        {"灵石 x350 + 修炼丹 x1", "mtxgdn:spirit_stone", "350", "mtxgdn:cultivation_elixir", "1"},
    };

    public Map<String, Object> signIn(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = playerService.getPlayerById(playerId);
        if (player == null) { result.put("success", false); result.put("message", "角色不存在"); return result; }

        SignInRecord rec = getSignInRecord(playerId);
        String today = java.time.LocalDate.now().toString();

        if (rec.lastSignIn != null && rec.lastSignIn.equals(today)) {
            result.put("success", false);
            result.put("message", "今日已签到，明日再来吧");
            return result;
        }

        int newStreak;
        if (rec.lastSignIn != null) {
            java.time.LocalDate last = java.time.LocalDate.parse(rec.lastSignIn);
            if (last.plusDays(1).equals(java.time.LocalDate.now())) {
                newStreak = rec.streak % 7 + 1;
            } else {
                newStreak = 1;
            }
        } else {
            newStreak = 1;
        }

        String[] reward = SIGN_IN_REWARDS[newStreak - 1];
        // 奖励发放
        for (int i = 1; i < reward.length; i += 2) {
            String itemKey = reward[i];
            int qty = Integer.parseInt(reward[i + 1]);
            itemService.addItem(playerId, itemKey, qty);
        }

        updateSignIn(playerId, today, newStreak);

        result.put("success", true);
        result.put("day", newStreak);
        result.put("streak", newStreak);
        result.put("reward", reward[0]);
        result.put("message", "签到成功！第 " + newStreak + " 天\n获得：" + reward[0]);
        return result;
    }

    public SignInRecord getSignInRecord(long playerId) {
        return querySignIn(playerId);
    }

    // ==================== 物品回收 ====================

    /** 回收价为基础价的 30% */
    private static final double RECYCLE_RATE = 0.30;

    public Map<String, Object> recycleItem(long playerId, String itemKey, int quantity) {
        Map<String, Object> result = new LinkedHashMap<>();

        var item = com.mtxgdn.game.item.ItemRegistry.resolve(itemKey);
        if (item == null) { result.put("success", false); result.put("message", "物品不存在"); return result; }

        String fullKey = item.getFullKey();
        if (fullKey.equals("mtxgdn:spirit_stone")) {
            result.put("success", false); result.put("message", "灵石本身就是货币，无需回收"); return result;
        }
        if (!item.isTradeable()) {
            result.put("success", false); result.put("message", "该物品不可交易，无法回收"); return result;
        }

        long owned = itemService.getItemCount(playerId, fullKey);
        if (owned < quantity) {
            result.put("success", false); result.put("message", "背包中只有 " + owned + " 个【" + item.getName() + "】"); return result;
        }

        int basePrice = item.getPrice();
        long recycleValue = (long)(basePrice * quantity * RECYCLE_RATE);
        if (recycleValue < 1) recycleValue = 1;

        itemService.removeItem(playerId, fullKey, quantity);
        itemService.addSpiritStones(playerId, recycleValue);

        result.put("success", true);
        result.put("recycled", item.getName() + " x" + quantity);
        result.put("stonesGained", recycleValue);
        result.put("message", "回收了【" + item.getName() + "】x" + quantity + "，获得 " + recycleValue + " 灵石");
        return result;
    }

    // ==================== 商店 ====================

    /** 商店商品：name, itemKey, price(灵石), description */
    private static final String[][] SHOP_ITEMS = {
        {"回血丹", "mtxgdn:healing_pill", "30", "恢复 50 生命值"},
        {"回蓝丹", "mtxgdn:mana_pill", "25", "恢复 30 法力值"},
        {"回灵丹", "mtxgdn:spirit_recovery_pill", "60", "恢复 100 生命 + 80 法力"},
        {"修炼丹", "mtxgdn:cultivation_elixir", "150", "获得 500 经验"},
        {"渡劫丹", "mtxgdn:tribulation_pill", "500", "突破时 +10% 成功率"},
        {"强化石", "mtxgdn:enhance_stone", "100", "强化装备所需"},
        {"灵草", "mtxgdn:spirit_grass", "20", "炼丹基本材料"},
        {"铁矿石", "mtxgdn:iron_ore", "15", "炼器基本材料"},
    };

    public Map<String, Object> buyFromShop(long playerId, int shopIndex) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (shopIndex < 1 || shopIndex > SHOP_ITEMS.length) {
            result.put("success", false); result.put("message", "商品不存在（1~" + SHOP_ITEMS.length + "）"); return result;
        }

        String[] entry = SHOP_ITEMS[shopIndex - 1];
        String itemKey = entry[1];
        int price = Integer.parseInt(entry[2]);
        var item = com.mtxgdn.game.item.ItemRegistry.get(itemKey);

        long stones = itemService.getSpiritStoneCount(playerId);
        if (stones < price) {
            result.put("success", false);
            result.put("message", "灵石不足，需要 " + price + " 灵石（当前: " + stones + "）");
            return result;
        }

        itemService.removeSpiritStones(playerId, price);
        itemService.addItem(playerId, itemKey, 1);

        result.put("success", true);
        result.put("item", item != null ? item.getName() : itemKey);
        result.put("cost", price);
        result.put("message", "花费 " + price + " 灵石，购得【" + (item != null ? item.getName() : itemKey) + "】");
        return result;
    }

    public String[][] getShopItems() { return SHOP_ITEMS; }

    // ==================== 灵石修炼加速 ====================

    /** 每 100 灵石 = 1 小时 ×1.5 倍修炼效率 */
    private static final int STONES_PER_BOOST = 100;
    private static final double BOOST_MULTIPLIER = 1.5;

    public Map<String, Object> boostCultivation(long playerId, int stonesToBurn) {
        Map<String, Object> result = new LinkedHashMap<>();

        Player player = playerService.getPlayerById(playerId);
        if (player == null) { result.put("success", false); result.put("message", "角色不存在"); return result; }
        if (!player.isCultivating()) {
            result.put("success", false); result.put("message", "请先开始修炼（/修炼），才能使用灵石加速"); return result;
        }
        if (stonesToBurn < STONES_PER_BOOST) {
            result.put("success", false); result.put("message", "至少需要 " + STONES_PER_BOOST + " 灵石才能加速修炼"); return result;
        }

        long currentStones = itemService.getSpiritStoneCount(playerId);
        if (currentStones < stonesToBurn) {
            result.put("success", false); result.put("message", "灵石不足，需要 " + stonesToBurn + "，当前仅有 " + currentStones); return result;
        }

        int boostHours = stonesToBurn / STONES_PER_BOOST;
        stonesToBurn = boostHours * STONES_PER_BOOST;

        itemService.removeSpiritStones(playerId, stonesToBurn);

        // 直接结算加速经验：按玩家境界计算 boostHours 时间的修炼收益，叠 1.5 倍
        int realmId = player.getRealm();
        double cultivPerSecond = com.mtxgdn.game.config.GameConfigLoader.getCultivationPerSecond(realmId);
        long boostSeconds = (long) boostHours * 3600L;
        long normalExp = (long)(cultivPerSecond * boostSeconds);
        long bonusExp = (long)(normalExp * (BOOST_MULTIPLIER - 1.0));

        playerService.addExperience(playerId, bonusExp);

        result.put("success", true);
        result.put("stonesSpent", stonesToBurn);
        result.put("boostHours", boostHours);
        result.put("bonusExp", bonusExp);
        result.put("message", "你燃烧 " + stonesToBurn + " 灵石化作灵气洪流！\n修炼加速 " + boostHours + " 小时（×" + BOOST_MULTIPLIER + "）\n额外获得 " + bonusExp + " 经验");
        return result;
    }

    // ==================== 金币灵石互通 ====================

    private static final long GOLD_TO_STONE_RATE = 10;   // 10 金币 = 1 灵石
    private static final long STONE_TO_GOLD_RATE = 5;     // 1 灵石 = 5 金币

    public Map<String, Object> exchangeGoldToStones(long playerId, long goldAmount) {
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = playerService.getPlayerById(playerId);
        if (player == null) { result.put("success", false); result.put("message", "角色不存在"); return result; }
        if (goldAmount < GOLD_TO_STONE_RATE) {
            result.put("success", false); result.put("message", "至少需要 " + GOLD_TO_STONE_RATE + " 金币才能兑换灵石"); return result;
        }
        if (player.getGold() < goldAmount) {
            result.put("success", false); result.put("message", "金币不足，需要 " + goldAmount + "（当前: " + player.getGold() + "）"); return result;
        }

        long stones = goldAmount / GOLD_TO_STONE_RATE;
        playerService.addGold(playerId, -goldAmount);
        itemService.addSpiritStones(playerId, stones);

        result.put("success", true);
        result.put("goldSpent", goldAmount);
        result.put("stonesGained", stones);
        result.put("message", "兑换成功！" + goldAmount + " 金币 → " + stones + " 灵石");
        return result;
    }

    public Map<String, Object> exchangeStonesToGold(long playerId, long stoneAmount) {
        Map<String, Object> result = new LinkedHashMap<>();
        long currentStones = itemService.getSpiritStoneCount(playerId);
        if (stoneAmount < 1) {
            result.put("success", false); result.put("message", "至少需要 1 灵石才能兑换金币"); return result;
        }
        if (currentStones < stoneAmount) {
            result.put("success", false); result.put("message", "灵石不足，需要 " + stoneAmount + "（当前: " + currentStones + "）"); return result;
        }

        long gold = stoneAmount * STONE_TO_GOLD_RATE;
        itemService.removeSpiritStones(playerId, stoneAmount);
        playerService.addGold(playerId, gold);

        result.put("success", true);
        result.put("stonesSpent", stoneAmount);
        result.put("goldGained", gold);
        result.put("message", "兑换成功！" + stoneAmount + " 灵石 → " + gold + " 金币");
        return result;
    }

    // ==================== 灵庄 ====================

    /** 定期利率表: 天数 → {名称, 总利率%} */
    private static final Object[][] FIXED_DEPOSIT_PLANS = {
        {7, "七日期", 3},    // 7天 3%
        {30, "月满期", 10},   // 30天 10%
        {90, "季定期", 25},   // 90天 25%
    };

    /** 活期日利率 0.5% */
    private static final double CURRENT_DAILY_RATE = 0.005;

    /**
     * 存入灵庄。
     * @param type "current"=活期, "fixed_7"/"fixed_30"/"fixed_90"=定期
     */
    public Map<String, Object> bankDeposit(long playerId, String type, long amount) {
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = playerService.getPlayerById(playerId);
        if (player == null) { result.put("success", false); result.put("message", "角色不存在"); return result; }

        long currentStones = itemService.getSpiritStoneCount(playerId);
        if (currentStones < amount) {
            result.put("success", false); result.put("message", "灵石不足，需要 " + amount + "，当前仅有 " + currentStones); return result;
        }
        if (amount < 100) { result.put("success", false); result.put("message", "灵庄起存 100 灵石"); return result; }

        double rate;
        int days = 0;
        String typeName;
        long maturesAt;

        if ("current".equals(type)) {
            rate = CURRENT_DAILY_RATE;
            typeName = "活期";
            maturesAt = 0;
        } else {
            int planIdx = switch (type) {
                case "fixed_7" -> 0; case "fixed_30" -> 1; case "fixed_90" -> 2;
                default -> -1;
            };
            if (planIdx < 0) { result.put("success", false); result.put("message", "未知存款类型: " + type + "（可选: current/fixed_7/fixed_30/fixed_90）"); return result; }

            Object[] plan = FIXED_DEPOSIT_PLANS[planIdx];
            days = (int) plan[0];
            typeName = (String) plan[1];
            rate = ((Integer) plan[2]) / 100.0;
            maturesAt = System.currentTimeMillis() + days * 86400000L;
        }

        try {
            DatabaseManager.runTransaction(conn -> {
                itemService.removeItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY, amount);

                String sql = "INSERT INTO player_bank (player_id, deposit_type, principal, rate, deposited_at, matures_at) VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, playerId);
                    ps.setString(2, type);
                    ps.setLong(3, amount);
                    ps.setDouble(4, rate);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.setLong(6, maturesAt);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException("灵庄存入失败", e);
        }

        result.put("success", true);
        if ("current".equals(type)) {
            result.put("message", "已将 " + amount + " 灵石存入灵庄（活期，日利 " + String.format("%.1f", rate * 100) + "%）。随时可取，利息按日结算。");
        } else {
            long expectedInterest = (long)(amount * rate);
            result.put("message", "已将 " + amount + " 灵石存入灵庄【" + typeName + "】（" + days + "天，到期利息 " + expectedInterest + " 灵石）。到期前取出将损失全部利息！");
        }
        result.put("principal", amount);
        result.put("depositType", type);
        return result;
    }

    /**
     * 从灵庄取款（含利息结算）。
     */
    public Map<String, Object> bankWithdraw(long playerId, long depositId) {
        Map<String, Object> result = new LinkedHashMap<>();

        BankDeposit dep = getBankDeposit(depositId);
        if (dep == null || dep.playerId != playerId) {
            result.put("success", false); result.put("message", "该存款不存在或不属于你"); return result;
        }
        if (!"active".equals(dep.status)) {
            result.put("success", false); result.put("message", "该存款已取出"); return result;
        }

        boolean isCurrent = "current".equals(dep.depositType);
        long interest;
        String note = "";

        if (isCurrent) {
            // 活期：按天数算复利
            long now = System.currentTimeMillis();
            long elapsedMs = now - dep.depositedAt;
            int elapsedDays = (int)(elapsedMs / 86400000L);
            interest = Math.max(0, (long)(dep.principal * Math.pow(1 + dep.rate, elapsedDays) - dep.principal));
        } else {
            // 定期：到期才有利息，提前取出无利息
            long now = System.currentTimeMillis();
            if (dep.maturesAt > 0 && now >= dep.maturesAt) {
                interest = (long)(dep.principal * dep.rate);
                note = "（已到期，全额利息）";
            } else {
                interest = 0;
                note = "（提前取出，无利息）";
            }
        }

        try {
            DatabaseManager.runTransaction(conn -> {
                markBankDepositDone(conn, depositId, interest);
                itemService.addItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY, dep.principal + interest);
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException("取款失败", e);
        }

        result.put("success", true);
        result.put("principal", dep.principal);
        result.put("interest", interest);
        result.put("total", dep.principal + interest);
        result.put("message", "灵庄取款成功！本金 " + dep.principal + "+ 利息 " + interest + " = 共 " + (dep.principal + interest) + " 灵石" + note);
        return result;
    }

    /** 查看灵庄账户 */
    public Map<String, Object> getBankInfo(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> deposits = new ArrayList<>();
        long totalPrincipal = 0, totalPendingInterest = 0;
        long now = System.currentTimeMillis();

        String sql = "SELECT * FROM player_bank WHERE player_id = ? AND status = 'active' ORDER BY created_at DESC";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    long id = rs.getLong("id");
                    String type = rs.getString("deposit_type");
                    long principal = rs.getLong("principal");
                    double rate = rs.getDouble("rate");
                    long depositedAt = rs.getLong("deposited_at");
                    long maturesAt = rs.getLong("matures_at");

                    row.put("id", id);
                    row.put("principal", principal);
                    totalPrincipal += principal;

                    boolean isCurrent = "current".equals(type);
                    if (isCurrent) {
                        int elapsedDays = (int)((now - depositedAt) / 86400000L);
                        long estimatedInterest = (long)(principal * Math.pow(1 + rate, elapsedDays) - principal);
                        if (estimatedInterest < 0) estimatedInterest = 0;
                        row.put("type", "活期");
                        row.put("estimatedInterest", estimatedInterest);
                        row.put("note", "已存 " + elapsedDays + " 天，日利 " + String.format("%.1f", rate * 100) + "%");
                        totalPendingInterest += estimatedInterest;
                    } else {
                        int planDays = maturesAt > 0 ? (int)((maturesAt - depositedAt) / 86400000L) : 0;
                        long remainingMs = maturesAt - now;
                        row.put("type", planDays + "天定期");
                        row.put("estimatedInterest", maturesAt > 0 && now >= maturesAt ? (long)(principal * rate) : 0L);
                        if (remainingMs > 0) {
                            long remainingDays = remainingMs / 86400000L + 1;
                            row.put("note", "剩余 " + remainingDays + " 天到期，利率 " + String.format("%.0f", rate * 100) + "%（到期前取款无利息）");
                        } else {
                            row.put("note", "已到期！可取款，利率 " + String.format("%.0f", rate * 100) + "%");
                            totalPendingInterest += (long)(principal * rate);
                        }
                    }
                    deposits.add(row);
                }
            }
        } catch (SQLException e) { throw new RuntimeException("查询灵庄失败", e); }

        result.put("deposits", deposits);
        result.put("totalPrincipal", totalPrincipal);
        result.put("totalPendingInterest", totalPendingInterest);
        return result;
    }

    private BankDeposit getBankDeposit(long depositId) {
        String sql = "SELECT * FROM player_bank WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, depositId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BankDeposit d = new BankDeposit();
                    d.id = rs.getLong("id");
                    d.playerId = rs.getLong("player_id");
                    d.depositType = rs.getString("deposit_type");
                    d.principal = rs.getLong("principal");
                    d.rate = rs.getDouble("rate");
                    d.depositedAt = rs.getLong("deposited_at");
                    d.maturesAt = rs.getLong("matures_at");
                    d.status = rs.getString("status");
                    return d;
                }
            }
        } catch (SQLException e) { throw new RuntimeException("查询存款失败", e); }
        return null;
    }

    private void markBankDepositDone(long depositId, long interest) {
        try (Connection conn = DatabaseManager.getConnection()) {
            markBankDepositDone(conn, depositId, interest);
        } catch (SQLException e) {
            throw new RuntimeException("取款失败", e);
        }
    }

    private void markBankDepositDone(Connection conn, long depositId, long interest) throws SQLException {
        String sql = "UPDATE player_bank SET status = 'withdrawn', interest_earned = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, interest);
            ps.setLong(2, depositId);
            ps.executeUpdate();
        }
    }

    private static class BankDeposit {
        @SuppressWarnings("unused")
        long id;
        long playerId, principal, depositedAt, maturesAt;
        String depositType, status;
        double rate;
    }

    // ==================== 经济数据面板 ====================

    public Map<String, Object> getEconomyStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        String totalStonesSql = "SELECT COALESCE(SUM(quantity), 0) FROM players_items WHERE item_key = 'mtxgdn:spirit_stone'";
        String totalGoldSql = "SELECT COALESCE(SUM(gold), 0) FROM players";
        String totalPlayersSql = "SELECT COUNT(*) FROM players";
        String avgStonesSql = "SELECT COALESCE(AVG(s.quantity), 0) FROM (SELECT COALESCE(SUM(quantity), 0) AS quantity FROM players_items WHERE item_key = 'mtxgdn:spirit_stone' GROUP BY player_id) s";

        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(totalStonesSql); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) stats.put("totalStones", rs.getLong(1));
            }
            try (PreparedStatement ps = conn.prepareStatement(totalGoldSql); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) stats.put("totalGold", rs.getLong(1));
            }
            try (PreparedStatement ps = conn.prepareStatement(totalPlayersSql); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) stats.put("totalPlayers", rs.getInt(1));
            }
            try (PreparedStatement ps = conn.prepareStatement(avgStonesSql); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) stats.put("avgStonesPerPlayer", Math.round(rs.getDouble(1)));
            }

            // 24h 内灵石变化（粗略估算：从player_action_logs取）
            stats.put("last24hTradeVolume", estimate24hVolume(conn));
        } catch (Exception e) {
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    private long estimate24hVolume(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM trade_listings WHERE status = 'sold'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getLong(1);
        } catch (Exception ignored) {}
        return 0;
    }

    // ==================== DB helpers ====================

    /** 竞拍行 — 创建拍卖 */
    public Map<String, Object> createAuction(long playerId, String itemKey, int quantity, long startPrice, int hours) {
        Map<String, Object> result = new LinkedHashMap<>();
        var item = com.mtxgdn.game.item.ItemRegistry.resolve(itemKey);
        if (item == null) { result.put("success", false); result.put("message", "物品不存在"); return result; }
        if (quantity <= 0 || startPrice <= 0) { result.put("success", false); result.put("message", "数量和起拍价必须大于 0"); return result; }

        String fullKey = item.getFullKey();
        if (!itemService.hasItem(playerId, fullKey, quantity)) {
            result.put("success", false); result.put("message", "背包中没有足够的【" + item.getName() + "】"); return result;
        }

        int realHours = Math.min(Math.max(hours, 1), 72);
        itemService.removeItem(playerId, fullKey, quantity);

        long endTimeMs = System.currentTimeMillis() + realHours * 3600000L;
        String sql = "INSERT INTO auction_listings (seller_player_id, item_key, quantity, start_price, fee_rate, end_time) VALUES (?, ?, ?, ?, 0.07, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, fullKey);
            ps.setInt(3, quantity);
            ps.setLong(4, startPrice);
            ps.setTimestamp(5, new java.sql.Timestamp(endTimeMs));
            ps.executeUpdate();
        } catch (SQLException e) {
            itemService.addItem(playerId, fullKey, quantity);
            throw new RuntimeException("创建拍卖失败", e);
        }

        result.put("success", true);
        result.put("message", "已将【" + item.getName() + "】×" + quantity + " 上架竞拍行，起拍价 " + startPrice + " 灵石，" + realHours + " 小时后截止");
        return result;
    }

    /** 竞拍行 — 出价 */
    public Map<String, Object> placeBid(long bidderPlayerId, long listingId, long amount) {
        Map<String, Object> result = new LinkedHashMap<>();

        AuctionListing listing = getAuctionListing(listingId);
        if (listing == null) { result.put("success", false); result.put("message", "该拍卖不存在或已结束"); return result; }
        if (listing.sellerPlayerId == bidderPlayerId) { result.put("success", false); result.put("message", "不能竞拍自己的物品"); return result; }

        long minBid = listing.currentBid != null ? listing.currentBid + 1 : listing.startPrice;
        if (amount < minBid) {
            result.put("success", false); result.put("message", "出价必须高于当前出价，当前: " + (listing.currentBid != null ? listing.currentBid : "无人出价") + "，起拍价: " + listing.startPrice); return result;
        }

        long currentStones = itemService.getSpiritStoneCount(bidderPlayerId);
        if (currentStones < amount) { result.put("success", false); result.put("message", "灵石不足，需要 " + amount + "（当前: " + currentStones + "）"); return result; }

        try {
            DatabaseManager.runTransaction(conn -> {
                // 退还前一个出价人
                if (listing.currentBidderId != null && listing.currentBid != null) {
                    itemService.addSpiritStones(conn, listing.currentBidderId, listing.currentBid);
                }

                // 扣除当前出价人灵石
                if (!itemService.removeItem(conn, bidderPlayerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY, amount)) {
                    throw new SQLException("灵石扣除失败");
                }

                // 记录出价
                String bidSql = "INSERT INTO auction_bids (listing_id, bidder_player_id, amount) VALUES (?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(bidSql)) {
                    ps.setLong(1, listingId);
                    ps.setLong(2, bidderPlayerId);
                    ps.setLong(3, amount);
                    ps.executeUpdate();
                }
                // 更新当前出价
                String updSql = "UPDATE auction_listings SET current_bid = ?, current_bidder_id = ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(updSql)) {
                    ps.setLong(1, amount);
                    ps.setLong(2, bidderPlayerId);
                    ps.setLong(3, listingId);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "出价失败: " + e.getMessage());
            return result;
        }

        result.put("success", true);
        result.put("message", "出价成功！以 " + amount + " 灵石竞拍 #" + listingId);
        return result;
    }

    public List<Map<String, Object>> getActiveAuctionItems() {
        List<Map<String, Object>> result = new ArrayList<>();
        // 先结算过期的
        settleExpiredAuctions();
        String sql = "SELECT * FROM auction_listings WHERE status = 'active' ORDER BY created_at DESC";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                var item = com.mtxgdn.game.item.ItemRegistry.get(rs.getString("item_key"));
                row.put("itemName", item != null ? item.getName() : rs.getString("item_key"));
                row.put("quantity", rs.getInt("quantity"));
                row.put("startPrice", rs.getLong("start_price"));
                row.put("currentBid", rs.getObject("current_bid"));
                row.put("remaining", estimateRemaining(rs.getTimestamp("end_time")));
                result.add(row);
            }
        } catch (SQLException e) { throw new RuntimeException("查询竞拍列表失败", e); }
        return result;
    }

    public List<Map<String, Object>> getPlayerAuctionItems(long playerId) {
        List<Map<String, Object>> result = new ArrayList<>();
        settleExpiredAuctions();
        String sql = "SELECT * FROM auction_listings WHERE seller_player_id = ? ORDER BY created_at DESC LIMIT 20";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    var item = com.mtxgdn.game.item.ItemRegistry.get(rs.getString("item_key"));
                    row.put("itemName", item != null ? item.getName() : rs.getString("item_key"));
                    row.put("quantity", rs.getInt("quantity"));
                    row.put("currentBid", rs.getObject("current_bid"));
                    row.put("status", rs.getString("status"));
                    result.add(row);
                }
            }
        } catch (SQLException e) { throw new RuntimeException("查询个人拍卖失败", e); }
        return result;
    }

    private void settleExpiredAuctions() {
        String selectSql = "SELECT * FROM auction_listings WHERE status = 'active'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql);
             ResultSet rs = ps.executeQuery()) {
            long now = System.currentTimeMillis();
            while (rs.next()) {
                java.sql.Timestamp endTime = rs.getTimestamp("end_time");
                if (endTime != null && endTime.getTime() > now) continue; // 未过期
                long listingId = rs.getLong("id");
                long sellerId = rs.getLong("seller_player_id");
                String itemKey = rs.getString("item_key");
                int qty = rs.getInt("quantity");
                long currentBid = rs.getLong("current_bid");
                Long bidderId = rs.getObject("current_bidder_id") != null ? rs.getLong("current_bidder_id") : null;
                double feeRate = rs.getDouble("fee_rate");

                // 原子标记为已结算，防止并发重复结算
                try (PreparedStatement claimPs = conn.prepareStatement(
                        "UPDATE auction_listings SET status = 'ended' WHERE id = ? AND status = 'active'")) {
                    claimPs.setLong(1, listingId);
                    int claimed = claimPs.executeUpdate();
                    if (claimed == 0) continue; // 已被其他线程结算
                }

                if (bidderId != null && currentBid > 0) {
                    long fee = Math.max(1, (long)(currentBid * feeRate));
                    itemService.addSpiritStones(sellerId, currentBid - fee);
                    itemService.addItem(bidderId, itemKey, qty);
                } else {
                    // 无人出价，退回物品
                    itemService.addItem(sellerId, itemKey, qty);
                }
            }
        } catch (SQLException e) { /* 结算失败不影响正常查询 */ }
    }

    private String estimateRemaining(java.sql.Timestamp endTime) {
        if (endTime == null) return "--";
        long remaining = endTime.getTime() - System.currentTimeMillis();
        if (remaining <= 0) return "已结束";
        long hours = remaining / 3600000;
        if (hours > 0) return hours + "小时";
        long mins = remaining / 60000;
        if (mins > 0) return mins + "分钟";
        return "即将结束";
    }

    private AuctionListing getAuctionListing(long listingId) {
        settleExpiredAuctions();
        String sql = "SELECT * FROM auction_listings WHERE id = ? AND status = 'active'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    AuctionListing l = new AuctionListing();
                    l.id = rs.getLong("id");
                    l.sellerPlayerId = rs.getLong("seller_player_id");
                    l.itemKey = rs.getString("item_key");
                    l.quantity = rs.getInt("quantity");
                    l.startPrice = rs.getLong("start_price");
                    l.currentBid = rs.getObject("current_bid") != null ? rs.getLong("current_bid") : null;
                    l.currentBidderId = rs.getObject("current_bidder_id") != null ? rs.getLong("current_bidder_id") : null;
                    l.feeRate = rs.getDouble("fee_rate");
                    return l;
                }
            }
        } catch (SQLException e) { throw new RuntimeException("查询拍卖失败", e); }
        return null;
    }

    private static class AuctionListing {
        @SuppressWarnings("unused")
        long id;
        long sellerPlayerId, startPrice;
        @SuppressWarnings("unused")
        int quantity;
        @SuppressWarnings("unused")
        String itemKey;
        Long currentBid, currentBidderId;
        @SuppressWarnings("unused")
        double feeRate;
    }

    // ==================== DB helpers ====================

    private static class SignInRecord {
        String lastSignIn;
        int streak;
    }

    private SignInRecord querySignIn(long playerId) {
        String sql = "SELECT last_sign_in, streak FROM player_economy WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    SignInRecord r = new SignInRecord();
                    r.lastSignIn = rs.getString("last_sign_in");
                    r.streak = rs.getInt("streak");
                    return r;
                }
            }
        } catch (SQLException ignored) {}
        return new SignInRecord();
    }

    private void updateSignIn(long playerId, String today, int streak) {
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = "INSERT INTO player_economy (player_id, last_sign_in, streak) VALUES (?, ?, ?) ON CONFLICT(player_id) DO UPDATE SET last_sign_in = excluded.last_sign_in, streak = excluded.streak";
        } else {
            sql = "INSERT INTO player_economy (player_id, last_sign_in, streak) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE last_sign_in = VALUES(last_sign_in), streak = VALUES(streak)";
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, today);
            ps.setInt(3, streak);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("签到记录更新失败", e);
        }
    }
}

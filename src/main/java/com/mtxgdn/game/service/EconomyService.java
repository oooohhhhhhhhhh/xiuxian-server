package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.Cave;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EconomyService {

    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();

    // ==================== 签到系统 ====================

    private static final String[][] SIGN_IN_REWARDS = {
        {"下品灵石 x50", "mtxgdn:spirit_stone_low", "50"},
        {"下品灵石 x80 + 灵草 x2", "mtxgdn:spirit_stone_low", "80", "mtxgdn:spirit_grass", "2"},
        {"下品灵石 x120 + 铁矿石 x3", "mtxgdn:spirit_stone_low", "120", "mtxgdn:iron_ore", "3"},
        {"下品灵石 x160 + 回血丹 x2", "mtxgdn:spirit_stone_low", "160", "mtxgdn:healing_pill", "2"},
        {"下品灵石 x200 + 强化石 x1", "mtxgdn:spirit_stone_low", "200", "mtxgdn:enhance_stone", "1"},
        {"下品灵石 x260 + 回蓝丹 x2", "mtxgdn:spirit_stone_low", "260", "mtxgdn:mana_pill", "2"},
        {"下品灵石 x350 + 修炼丹 x1", "mtxgdn:spirit_stone_low", "350", "mtxgdn:cultivation_elixir", "1"},
    };

    public Map<String, Object> signIn(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
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

        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();
            try {
                // 事务内行锁复查：防止并发双签（MySQL 行锁/间隙锁，SQLite 单写者串行化）
                String checkSql = "SELECT last_sign_in FROM player_economy WHERE player_id = ?"
                        + (DatabaseManager.isSqlite() ? "" : " FOR UPDATE");
                try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                    ps.setLong(1, playerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && today.equals(rs.getString("last_sign_in"))) {
                            txResult.put("success", false);
                            txResult.put("message", "今日已签到，明日再来吧");
                            return txResult;
                        }
                    }
                }

                for (int i = 1; i < reward.length; i += 2) {
                    String itemKey = reward[i];
                    int qty = Integer.parseInt(reward[i + 1]);
                    itemService.addItem(conn, playerId, itemKey, qty);
                }

                updateSignIn(conn, playerId, today, newStreak);

                txResult.put("success", true);
                txResult.put("day", newStreak);
                txResult.put("streak", newStreak);
                txResult.put("reward", reward[0]);
                txResult.put("message", "签到成功！第 " + newStreak + " 天\n获得：" + reward[0]);
            } catch (SQLException e) {
                txResult.put("success", false);
                txResult.put("message", "签到失败: " + e.getMessage());
            }
            return txResult;
        });
    }

    public SignInRecord getSignInRecord(long playerId) {
        return querySignIn(playerId);
    }

    // ==================== 物品回收 ====================

    private static final double RECYCLE_RATE = 0.30;

    public Map<String, Object> recycleItem(long playerId, String itemKey, int quantity) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        var item = com.mtxgdn.game.item.ItemRegistry.resolve(itemKey);
        if (item == null) { result.put("success", false); result.put("message", "物品不存在"); return result; }

        String fullKey = item.getFullKey();
        if (fullKey.startsWith("mtxgdn:spirit_stone")) {
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

        final long finalRecycleValue = recycleValue;
        DatabaseManager.runTransaction(conn -> {
            itemService.removeItem(conn, playerId, fullKey, quantity);
            itemService.addSpiritStones(conn, playerId, finalRecycleValue);
            return null;
        });

        result.put("success", true);
        result.put("recycled", item.getName() + " x" + quantity);
        result.put("stonesGained", recycleValue);
        result.put("message", "回收了【" + item.getName() + "】x" + quantity + "，获得 " + recycleValue + " 下品灵石");
        return result;
    }

    // ==================== 商店 ====================

    private static final String[][] SHOP_ITEMS = {
        {"回血丹", "mtxgdn:healing_pill", "30", "恢复 50 生命值"},
        {"回蓝丹", "mtxgdn:mana_pill", "25", "恢复 30 法力值"},
        {"回灵丹", "mtxgdn:spirit_recovery_pill", "60", "恢复 100 生命 + 80 法力"},
        {"修炼丹", "mtxgdn:cultivation_elixir", "150", "获得 500 经验"},
        {"渡劫丹", "mtxgdn:tribulation_pill", "500", "突破时 +10% 成功率"},
        {"强化石", "mtxgdn:enhance_stone", "100", "强化装备所需"},
        {"灵草", "mtxgdn:spirit_grass", "20", "炼丹基本材料"},
        {"铁矿石", "mtxgdn:iron_ore", "15", "炼器基本材料"},
        {"低级肥料", "mtxgdn:low_grade_fertilizer", "20", "提高肥力25%，助力作物生长"},
        {"中级肥料", "mtxgdn:mid_grade_fertilizer", "50", "提高肥力40%，更好的收成"},
        {"高级肥料", "mtxgdn:high_grade_fertilizer", "100", "提高肥力60%，顶级收成"},
        {"杀虫剂", "mtxgdn:pesticide", "30", "清除农田病虫害"},
        {"灵草种子", "mtxgdn:spirit_grass_seed", "50", "种植灵草，60秒成熟"},
        {"千年人参种子", "mtxgdn:thousand_year_ginseng_seed", "200", "种植千年人参，180秒成熟"},
        {"暗冰草种子", "mtxgdn:dark_ice_grass_seed", "120", "种植暗冰草，90秒成熟"},
        {"火焰藤种子", "mtxgdn:fire_vine_seed", "150", "种植火焰藤，120秒成熟"},
        {"幽冥花种子", "mtxgdn:nether_flower_seed", "300", "种植幽冥花，240秒成熟（稀有）"},
        {"星草种子", "mtxgdn:star_grass_seed", "500", "种植星草，300秒成熟（史诗）"},
        {"血灵芝种子", "mtxgdn:blood_lingzhi_seed", "400", "种植血灵芝，200秒成熟（稀有）"},
        {"天山雪莲种子", "mtxgdn:tianshan_snow_lotus_seed", "1000", "种植天山雪莲，420秒成熟（传说）"},
    };

    public Map<String, Object> buyFromShop(long playerId, int shopIndex) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
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
            result.put("message", "灵石不足，需要 " + price + " 下品灵石（当前: " + stones + "）");
            return result;
        }

        DatabaseManager.runTransaction(conn -> {
            itemService.removeSpiritStones(conn, playerId, price);
            itemService.addItem(conn, playerId, itemKey, 1);
            return null;
        });

        result.put("success", true);
        result.put("item", item != null ? item.getName() : itemKey);
        result.put("cost", price);
        result.put("message", "花费 " + price + " 下品灵石，购得【" + (item != null ? item.getName() : itemKey) + "】");
        return result;
    }

    public String[][] getShopItems() { return SHOP_ITEMS; }

    // ==================== 灵石修炼加速 ====================

    private static final int STONES_PER_BOOST = 100;
    private static final double BOOST_MULTIPLIER = 1.5;

    public Map<String, Object> boostCultivation(long playerId, int stonesToBurn) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Player player = playerService.getPlayerById(playerId);
        if (player == null) { result.put("success", false); result.put("message", "角色不存在"); return result; }
        if (!player.isCultivating()) {
            result.put("success", false); result.put("message", "请先开始修炼（/修炼），才能使用灵石加速"); return result;
        }
        if (stonesToBurn < STONES_PER_BOOST) {
            result.put("success", false); result.put("message", "至少需要 " + STONES_PER_BOOST + " 下品灵石才能加速修炼"); return result;
        }

        long currentStones = itemService.getSpiritStoneCount(playerId);
        if (currentStones < stonesToBurn) {
            result.put("success", false); result.put("message", "灵石不足，需要 " + stonesToBurn + "，当前仅有 " + currentStones); return result;
        }

        int boostHours = stonesToBurn / STONES_PER_BOOST;
        stonesToBurn = boostHours * STONES_PER_BOOST;

        int realmId = player.getRealm();
        double cultivPerSecond = com.mtxgdn.game.config.GameConfigLoader.getCultivationPerSecond(realmId);
        long boostSeconds = (long) boostHours * 3600L;
        long normalExp = (long)(cultivPerSecond * boostSeconds);
        long bonusExp = (long)(normalExp * (BOOST_MULTIPLIER - 1.0));

        final long finalStonesToBurn = stonesToBurn;
        final long finalBonusExp = bonusExp;
        DatabaseManager.runTransaction(conn -> {
            itemService.removeSpiritStones(conn, playerId, finalStonesToBurn);
            playerService.addExperience(conn, playerId, finalBonusExp);
            return null;
        });

        result.put("success", true);
        result.put("stonesSpent", stonesToBurn);
        result.put("boostHours", boostHours);
        result.put("bonusExp", bonusExp);
        result.put("message", "你燃烧 " + stonesToBurn + " 下品灵石化作灵气洪流！\n修炼加速 " + boostHours + " 小时（×" + BOOST_MULTIPLIER + "）\n额外获得 " + bonusExp + " 经验");
        return result;
    }

    // ==================== 金币灵石互通 ====================

    private static final long GOLD_TO_STONE_RATE = 10;
    private static final long STONE_TO_GOLD_RATE = 5;

    public Map<String, Object> exchangeGoldToStones(long playerId, long goldAmount) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
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
        DatabaseManager.runTransaction(conn -> {
            playerService.addGold(conn, playerId, -goldAmount);
            itemService.addSpiritStones(conn, playerId, stones);
            return null;
        });

        result.put("success", true);
        result.put("goldSpent", goldAmount);
        result.put("stonesGained", stones);
        result.put("message", "兑换成功！" + goldAmount + " 金币 → " + stones + " 下品灵石");
        return result;
    }

    public Map<String, Object> exchangeStonesToGold(long playerId, long stoneAmount) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        long currentStones = itemService.getSpiritStoneCount(playerId);
        if (stoneAmount < 1) {
            result.put("success", false); result.put("message", "至少需要 1 下品灵石才能兑换金币"); return result;
        }
        if (currentStones < stoneAmount) {
            result.put("success", false); result.put("message", "灵石不足，需要 " + stoneAmount + "（当前: " + currentStones + "）"); return result;
        }

        long gold = stoneAmount * STONE_TO_GOLD_RATE;
        DatabaseManager.runTransaction(conn -> {
            itemService.removeSpiritStones(conn, playerId, stoneAmount);
            playerService.addGold(conn, playerId, gold);
            return null;
        });

        result.put("success", true);
        result.put("stonesSpent", stoneAmount);
        result.put("goldGained", gold);
        result.put("message", "兑换成功！" + stoneAmount + " 下品灵石 → " + gold + " 金币");
        return result;
    }

    // ==================== 灵庄 ====================

    private static final Object[][] FIXED_DEPOSIT_PLANS = {
        {7, "七日期", 4},
        {30, "月满期", 15},
        {90, "季定期", 40},
    };

    /** 活期日利率 0.2%（30 天复利约 6.2%，低于定期，保证定期产品有意义） */
    private static final double CURRENT_DAILY_RATE = 0.002;

    public Map<String, Object> bankDeposit(long playerId, String type, long amount) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = playerService.getPlayerById(playerId);
        if (player == null) { result.put("success", false); result.put("message", "角色不存在"); return result; }

        long currentStones = itemService.getSpiritStoneCount(playerId);
        if (currentStones < amount) {
            result.put("success", false); result.put("message", "灵石不足，需要 " + amount + "，当前仅有 " + currentStones); return result;
        }
        if (amount < 100) { result.put("success", false); result.put("message", "灵庄起存 100 下品灵石"); return result; }

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

        long depositId = 0;
        try {
            depositId = DatabaseManager.runTransaction(conn -> {
                // 灵庄存款：跨等级扣除灵石（等价折算为下品计息）
                if (!itemService.removeSpiritStones(conn, playerId, amount)) {
                    throw new SQLException("灵石扣除失败");
                }

                String sql = "INSERT INTO player_bank (player_id, deposit_type, principal, rate, deposited_at, matures_at) VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, playerId);
                    ps.setString(2, type);
                    ps.setLong(3, amount);
                    ps.setDouble(4, rate);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.setLong(6, maturesAt);
                    ps.executeUpdate();
                    try (var rs = ps.getGeneratedKeys()) {
                        if (rs.next()) return rs.getLong(1);
                    }
                }
                return 0L;
            });
        } catch (Exception e) {
            throw new RuntimeException("灵庄存入失败", e);
        }

        result.put("success", true);
        if ("current".equals(type)) {
            result.put("message", "已将 " + amount + " 下品灵石存入灵庄（活期，日利 " + String.format("%.1f", rate * 100) + "%）。随时可取，利息按日结算。\n编号: #" + depositId);
        } else {
            long expectedInterest = (long)(amount * rate);
            result.put("message", "已将 " + amount + " 下品灵石存入灵庄【" + typeName + "】（" + days + "天，到期利息 " + expectedInterest + " 下品灵石）。到期前取出将损失全部利息！\n编号: #" + depositId);
        }
        result.put("principal", amount);
        result.put("depositType", type);
        result.put("depositId", depositId);
        return result;
    }

    public Map<String, Object> bankWithdraw(long playerId, long depositId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
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
            long now = System.currentTimeMillis();
            long elapsedMs = now - dep.depositedAt;
            int elapsedDays = (int)(elapsedMs / 86400000L);
            interest = Math.max(0, (long)(dep.principal * Math.pow(1 + dep.rate, elapsedDays) - dep.principal));
        } else {
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
                // 条件认领：仅当存款仍为 active 时才能取出，防止并发双取（本金+利息重复发放）
                if (!markBankDepositDone(conn, depositId, interest)) {
                    throw new SQLException("存款已被取出");
                }
                itemService.addItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_LOW, dep.principal + interest);
                return null;
            });
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("存款已被取出")) {
                result.put("success", false);
                result.put("message", "该存款已取出，请勿重复操作");
                return result;
            }
            throw new RuntimeException("取款失败", e);
        }

        result.put("success", true);
        result.put("principal", dep.principal);
        result.put("interest", interest);
        result.put("total", dep.principal + interest);
        result.put("message", "灵庄取款成功！本金 " + dep.principal + "+ 利息 " + interest + " = 共 " + (dep.principal + interest) + " 下品灵石" + note);
        return result;
    }

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

    private boolean markBankDepositDone(Connection conn, long depositId, long interest) throws SQLException {
        String sql = "UPDATE player_bank SET status = 'withdrawn', interest_earned = ? WHERE id = ? AND status = 'active'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, interest);
            ps.setLong(2, depositId);
            return ps.executeUpdate() > 0;
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

        String totalStonesSql = "SELECT COALESCE(SUM(CASE WHEN item_key = 'mtxgdn:spirit_stone_low' THEN quantity WHEN item_key = 'mtxgdn:spirit_stone_mid' THEN quantity * 1000 WHEN item_key = 'mtxgdn:spirit_stone_high' THEN quantity * 1000000 WHEN item_key = 'mtxgdn:spirit_stone_supreme' THEN quantity * 1000000000 WHEN item_key = 'mtxgdn:spirit_stone' THEN quantity ELSE 0 END), 0) FROM players_items WHERE item_key LIKE 'mtxgdn:spirit_stone%'";
        String totalGoldSql = "SELECT COALESCE(SUM(gold), 0) FROM players";
        String totalPlayersSql = "SELECT COUNT(*) FROM players";

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

    // ==================== 竞拍行 ====================

    public Map<String, Object> createAuction(long playerId, String itemKey, int quantity, long startPrice, int hours) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        var item = com.mtxgdn.game.item.ItemRegistry.resolve(itemKey);
        if (item == null) { result.put("success", false); result.put("message", "物品不存在"); return result; }
        if (quantity <= 0 || startPrice <= 0) { result.put("success", false); result.put("message", "数量和起拍价必须大于 0"); return result; }

        String fullKey = item.getFullKey();
        if (!itemService.hasItem(playerId, fullKey, quantity)) {
            result.put("success", false); result.put("message", "背包中没有足够的【" + item.getName() + "】"); return result;
        }

        int realHours = Math.min(Math.max(hours, 1), 72);

        long endTimeMs = System.currentTimeMillis() + realHours * 3600000L;
        String sql = "INSERT INTO auction_listings (seller_player_id, item_key, quantity, start_price, fee_rate, end_time) VALUES (?, ?, ?, ?, 0.07, ?)";
        try {
            DatabaseManager.runTransaction(conn -> {
                if (!itemService.removeItem(conn, playerId, fullKey, quantity)) {
                    throw new SQLException("物品扣除失败");
                }
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, playerId);
                    ps.setString(2, fullKey);
                    ps.setInt(3, quantity);
                    ps.setLong(4, startPrice);
                    ps.setTimestamp(5, new java.sql.Timestamp(endTimeMs));
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            throw new RuntimeException("创建拍卖失败", e);
        }

        result.put("success", true);
        result.put("message", "已将【" + item.getName() + "】×" + quantity + " 上架竞拍行，起拍价 " + startPrice + " 下品灵石，" + realHours + " 小时后截止");
        return result;
    }

    public Map<String, Object> placeBid(long bidderPlayerId, long listingId, long amount) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(bidderPlayerId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (amount <= 0) {
            result.put("success", false);
            result.put("message", "出价必须大于 0");
            return result;
        }

        // 竞态修复：listing 读取与校验全部移入事务内并加锁，避免事务外快照导致
        // 前出价人被重复退款 / 新出价人灵石丢失（与坊市购买 S6 修复同一模式）
        try {
            DatabaseManager.runTransaction(conn -> {
                AuctionListing listing = getAuctionListingForUpdate(conn, listingId);
                if (listing == null) {
                    throw new SQLException("该拍卖不存在或已结束");
                }
                if (listing.sellerPlayerId == bidderPlayerId) {
                    throw new SQLException("不能竞拍自己的物品");
                }
                long minBid = listing.currentBid != null ? listing.currentBid + 1 : listing.startPrice;
                if (amount < minBid) {
                    throw new SQLException("出价必须高于当前出价");
                }
                long currentStones = itemService.getSpiritStoneCount(conn, bidderPlayerId);
                if (currentStones < amount) {
                    throw new SQLException("灵石不足，需要 " + amount + "（当前: " + currentStones + "）");
                }

                if (listing.currentBidderId != null && listing.currentBid != null) {
                    itemService.addSpiritStones(conn, listing.currentBidderId, listing.currentBid);
                }

                if (!itemService.removeSpiritStones(conn, bidderPlayerId, amount)) {
                    throw new SQLException("灵石扣除失败");
                }

                String bidSql = "INSERT INTO auction_bids (listing_id, bidder_player_id, amount) VALUES (?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(bidSql)) {
                    ps.setLong(1, listingId);
                    ps.setLong(2, bidderPlayerId);
                    ps.setLong(3, amount);
                    ps.executeUpdate();
                }
                String updSql = "UPDATE auction_listings SET current_bid = ?, current_bidder_id = ? WHERE id = ? AND status = 'active'";
                try (PreparedStatement ps = conn.prepareStatement(updSql)) {
                    ps.setLong(1, amount);
                    ps.setLong(2, bidderPlayerId);
                    ps.setLong(3, listingId);
                    if (ps.executeUpdate() == 0) {
                        throw new SQLException("拍卖已结束");
                    }
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "出价失败: " + rootMessage(e));
            return result;
        }

        result.put("success", true);
        result.put("message", "出价成功！以 " + amount + " 下品灵石竞拍 #" + listingId);
        return result;
    }

    /** 解包 runTransaction 包装的 RuntimeException，返回业务异常的真实原因消息。 */
    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause instanceof RuntimeException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    /** 事务内读取竞拍并加行锁（MySQL 使用 FOR UPDATE；SQLite 在事务内 UPDATE 即隐式写锁）。必须在事务内调用。 */
    private AuctionListing getAuctionListingForUpdate(Connection conn, long listingId) throws SQLException {
        String sql = DatabaseManager.isSqlite()
                ? "SELECT * FROM auction_listings WHERE id = ? AND status = 'active'"
                : "SELECT * FROM auction_listings WHERE id = ? AND status = 'active' FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
        }
        return null;
    }

    public List<Map<String, Object>> getActiveAuctionItems() {
        List<Map<String, Object>> result = new ArrayList<>();
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
                if (endTime != null && endTime.getTime() > now) continue;
                final long listingId = rs.getLong("id");
                final long sellerId = rs.getLong("seller_player_id");
                final String itemKey = rs.getString("item_key");
                final int qty = rs.getInt("quantity");
                final long currentBid = rs.getLong("current_bid");
                final Long bidderId = rs.getObject("current_bidder_id") != null ? rs.getLong("current_bidder_id") : null;
                final double feeRate = rs.getDouble("fee_rate");

                DatabaseManager.runTransaction(innerConn -> {
                    try (PreparedStatement claimPs = innerConn.prepareStatement(
                            "UPDATE auction_listings SET status = 'ended' WHERE id = ? AND status = 'active'")) {
                        claimPs.setLong(1, listingId);
                        int claimed = claimPs.executeUpdate();
                        if (claimed == 0) return null;
                    }

                    if (bidderId != null && currentBid > 0) {
                        long fee = Math.max(1, (long)(currentBid * feeRate));
                        // 卖家收到扣除手续费后的灵石；手续费作为系统税费直接销毁（不再记入玩家 0，避免外键失败导致结算回滚）
                        itemService.addSpiritStones(innerConn, sellerId, currentBid - fee);
                        itemService.addItem(innerConn, bidderId, itemKey, qty);
                    } else {
                        itemService.addItem(innerConn, sellerId, itemKey, qty);
                    }
                    return null;
                });
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

    // ==================== 灵石提纯 ====================

    /** 提纯境界需求（按源等级 0/1/2）：筑基 / 元婴 / 大乘 */
    private static final int[] PURIFY_REALM = {2, 4, 7};
    /** 提纯单次消耗的洞府灵气（按源等级） */
    private static final long[] PURIFY_AURA_COST = {100, 1000, 10000};
    /** 提纯基础成功率（按源等级） */
    private static final double[] PURIFY_BASE_RATE = {0.90, 0.60, 0.30};
    /** 每次提纯消耗的源等级灵石数量（= 1 块高一级灵石的等值） */
    private static final long PURIFY_STONE_COST = 1000;

    /**
     * 提纯灵石：将低级灵石升为高级灵石。
     * 需要消耗洞府灵气与大量低阶灵石，有概率失败，且有境界需求。
     *
     * @param grade 源灵石等级（0=下品→中品，1=中品→上品，2=上品→极品）
     */
    public Map<String, Object> purifySpiritStone(long playerId, int grade) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", "角色不存在");
            return result;
        }
        if (grade < 0 || grade > 2) {
            result.put("success", false);
            result.put("message", "无效的灵石等级，只能提纯 下品/中品/上品 灵石");
            return result;
        }

        int targetGrade = grade + 1;
        String sourceName = com.mtxgdn.game.item.CurrencyEffect.GRADE_NAMES[grade];
        String targetName = com.mtxgdn.game.item.CurrencyEffect.GRADE_NAMES[targetGrade];

        // 境界需求
        if (player.getRealm() < PURIFY_REALM[grade]) {
            String realmName = GameConfigLoader.getRealmConfig(player.getRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(player.getRealm(), 0).getFullName() : "凡人";
            String requiredName = GameConfigLoader.getRealmConfig(PURIFY_REALM[grade], 0) != null
                    ? GameConfigLoader.getRealmConfig(PURIFY_REALM[grade], 0).getFullName() : "未知境界";
            result.put("success", false);
            result.put("message", "境界不足，提纯" + targetName + "需要" + requiredName + "以上（当前：" + realmName + "）");
            return result;
        }

        // 灵石数量
        String sourceKey = com.mtxgdn.game.item.CurrencyEffect.getStoneKey(grade);
        if (itemService.getItemCount(playerId, sourceKey) < PURIFY_STONE_COST) {
            result.put("success", false);
            result.put("message", sourceName + "不足，提纯一次需要 " + PURIFY_STONE_COST + " 块" + sourceName
                    + "（当前：" + itemService.getItemCount(playerId, sourceKey) + "）");
            return result;
        }

        // 洞府灵气（"灵气"资源）
        long auraCost = PURIFY_AURA_COST[grade];
        CaveService caveService = new CaveService();
        Cave cave = caveService.getCaveByPlayerId(playerId);
        if (cave == null) {
            result.put("success", false);
            result.put("message", "提纯灵石需要借助洞府灵气，请先开辟洞府");
            return result;
        }
        if (cave.getSpiritEnergy() < auraCost) {
            result.put("success", false);
            result.put("message", "洞府灵气不足，提纯" + targetName + "需要 " + auraCost + " 点灵气（当前：" + cave.getSpiritEnergy() + "）");
            return result;
        }

        // 成功率：基础 + 灵力加成（上限 +15%）
        double baseRate = PURIFY_BASE_RATE[grade];
        double spiritBonus = Math.min(0.15, player.getSpirit() / 2000.0);
        double finalRate = Math.min(0.98, baseRate + spiritBonus);
        boolean success = new java.util.Random().nextDouble() < finalRate;

        final long finalAuraCost = auraCost;
        final boolean finalSuccess = success;
        final long finalStoneCost = PURIFY_STONE_COST;
        final int finalTargetGrade = targetGrade;
        DatabaseManager.runTransaction(conn -> {
            // 扣除灵石
            itemService.removeItem(conn, playerId, sourceKey, finalStoneCost);
            // 消耗洞府灵气
            String auraSql = "UPDATE caves SET spirit_energy = spirit_energy - ? WHERE id = ? AND spirit_energy >= ?";
            try (PreparedStatement ps = conn.prepareStatement(auraSql)) {
                ps.setLong(1, finalAuraCost);
                ps.setLong(2, cave.getId());
                ps.setLong(3, finalAuraCost);
                if (ps.executeUpdate() == 0) {
                    throw new SQLException("洞府灵气不足");
                }
            }
            // 成功则发放高一级灵石
            if (finalSuccess) {
                itemService.addItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.getStoneKey(finalTargetGrade), 1);
            }
            return null;
        });

        int ratePercent = (int) Math.round(finalRate * 100);
        result.put("success", true);
        result.put("purifySuccess", success);
        result.put("grade", grade);
        result.put("targetGrade", targetGrade);
        result.put("sourceName", sourceName);
        result.put("targetName", targetName);
        result.put("auraCost", auraCost);
        result.put("stoneCost", PURIFY_STONE_COST);
        result.put("successRate", finalRate);
        if (success) {
            result.put("message", "提纯成功！" + PURIFY_STONE_COST + " 块" + sourceName + " + " + auraCost + " 灵气 → 1 块" + targetName
                    + "（成功率 " + ratePercent + "%）");
        } else {
            result.put("message", "提纯失败……" + PURIFY_STONE_COST + " 块" + sourceName + "与 " + auraCost
                    + " 点灵气已消散（成功率 " + ratePercent + "%），请再试一次");
        }
        return result;
    }

    /** 提纯帮助信息（供 QQ 指令展示） */
    public String getPurifyHelp(long playerId) {
        Player player = playerService.getPlayerById(playerId);
        StringBuilder sb = new StringBuilder();
        sb.append("===== 灵石提纯 =====\n");
        sb.append("提纯可消耗灵气把低级灵石升为高级灵石（有概率失败）\n\n");
        for (int grade = 0; grade < 3; grade++) {
            int targetGrade = grade + 1;
            String sourceName = com.mtxgdn.game.item.CurrencyEffect.GRADE_NAMES[grade];
            String targetName = com.mtxgdn.game.item.CurrencyEffect.GRADE_NAMES[targetGrade];
            String realmName = GameConfigLoader.getRealmConfig(PURIFY_REALM[grade], 0) != null
                    ? GameConfigLoader.getRealmConfig(PURIFY_REALM[grade], 0).getFullName() : "?";
            sb.append("· ").append(sourceName).append(" → ").append(targetName)
                    .append("  需").append(realmName).append(" · 成功率 ").append((int) (PURIFY_BASE_RATE[grade] * 100))
                    .append("% · 消耗 ").append(PURIFY_STONE_COST).append("块 + ").append(PURIFY_AURA_COST[grade]).append("灵气\n");
        }
        sb.append("\n用法: /提纯 <下品|中品|上品>");
        if (player != null) {
            sb.append("\n\n当前灵石:\n");
            for (int i = 0; i < 4; i++) {
                sb.append("  ").append(com.mtxgdn.game.item.CurrencyEffect.GRADE_NAMES[i])
                        .append(": ").append(itemService.getSpiritStoneCount(playerId, i)).append("\n");
            }
        }
        return sb.toString();
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

    private void updateSignIn(Connection conn, long playerId, String today, int streak) throws SQLException {
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = "INSERT INTO player_economy (player_id, last_sign_in, streak) VALUES (?, ?, ?) ON CONFLICT(player_id) DO UPDATE SET last_sign_in = excluded.last_sign_in, streak = excluded.streak";
        } else {
            sql = "INSERT INTO player_economy (player_id, last_sign_in, streak) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE last_sign_in = VALUES(last_sign_in), streak = VALUES(streak)";
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, today);
            ps.setInt(3, streak);
            ps.executeUpdate();
        }
    }
}
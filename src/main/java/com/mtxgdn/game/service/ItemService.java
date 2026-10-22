package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.CurrencyEffect;
import com.mtxgdn.game.item.InventoryCapacityEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemEffect;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.util.AppConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

public class ItemService {

    private static final int BASE_CAPACITY = AppConfig.getInt("inventory.base_capacity", 30);
    private static final int CAPACITY_PER_LEVEL = AppConfig.getInt("inventory.capacity_per_level", 5);

    private final PlayerService playerService = ServiceRegistry.getPlayerService();

    // ==================== 背包容量 ====================

    public int getInventoryCapacity(long playerId) {
        Player player = playerService.getPlayerById(playerId);
        int level = player != null ? player.getLevel() : 1;
        int baseCapacity = BASE_CAPACITY + (level - 1) * CAPACITY_PER_LEVEL;
        int bonusCapacity = calculateCapacityBonus(playerId);
        return baseCapacity + bonusCapacity;
    }

    private int calculateCapacityBonus(long playerId) {
        int bonus = 0;
        Map<String, String> equipment = getEquipment(playerId);
        for (String itemKey : equipment.values()) {
            if (itemKey == null || itemKey.isEmpty()) continue;
            Item item = ItemRegistry.get(itemKey);
            if (item == null) continue;
            for (ItemEffect effect : item.getEffects()) {
                if (effect instanceof InventoryCapacityEffect ice) {
                    bonus += ice.getCapacityBonus();
                }
            }
        }
        com.mtxgdn.game.service.CaveService caveService = new com.mtxgdn.game.service.CaveService();
        bonus += caveService.getStorageBonus(playerId);
        return bonus;
    }

    public int getInventoryUsedSlots(long playerId) {
        String sql = "SELECT COUNT(*) FROM players_items WHERE player_id = ? AND quantity > 0";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询背包使用槽位数失败", e);
        }
        return 0;
    }

    public boolean isInventoryFull(long playerId) {
        return getInventoryUsedSlots(playerId) >= getInventoryCapacity(playerId);
    }

    // ==================== 添加物品 ====================

    /**
     * 添加物品到背包。使用 upsert：存在则累加数量，不存在则插入。
     *
     * @throws IllegalArgumentException 如果 quantity <= 0 或 itemKey 未注册
     * @throws RuntimeException 如果背包已满
     */
    public boolean addItem(long playerId, String itemKey, long quantity) {
        String resolvedKey = validateAddParams(itemKey, quantity);
        checkCapacity(playerId, resolvedKey);
        try (Connection conn = DatabaseManager.getConnection()) {
            boolean ok = addItem(conn, playerId, resolvedKey, quantity);
            if (ok) {
                com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
            }
            return ok;
        } catch (SQLException e) {
            throw new RuntimeException("添加物品失败", e);
        }
    }

    /**
     * 在已有事务中批量添加物品，使用外部 Connection。
     */
    public boolean addItem(Connection conn, long playerId, String itemKey, long quantity) throws SQLException {
        String resolvedKey = validateAddParams(itemKey, quantity);
        checkCapacity(conn, playerId, resolvedKey);
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = """
                INSERT INTO players_items (player_id, item_key, quantity)
                VALUES (?, ?, ?)
                ON CONFLICT(player_id, item_key) DO UPDATE SET quantity = quantity + excluded.quantity,
                    updated_at = CURRENT_TIMESTAMP
                """;
        } else {
            sql = """
                INSERT INTO players_items (player_id, item_key, quantity)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity),
                    updated_at = CURRENT_TIMESTAMP
                """;
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, resolvedKey);
            ps.setLong(3, quantity);
            return ps.executeUpdate() > 0;
        }
    }

    private String validateAddParams(String itemKey, long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于 0，当前值: " + quantity);
        }
        if (ItemRegistry.contains(itemKey)) {
            return itemKey;
        }
        if (!itemKey.contains(":")) {
            String fullKey = "mtxgdn:" + itemKey;
            if (ItemRegistry.contains(fullKey)) {
                return fullKey;
            }
        }
        throw new IllegalArgumentException("物品不存在: " + itemKey);
    }

    private void checkCapacity(long playerId, String itemKey) {
        if (getItemCount(playerId, itemKey) == 0 && isInventoryFull(playerId)) {
            int capacity = getInventoryCapacity(playerId);
            throw new RuntimeException("背包已满！当前容量: " + capacity + " 格，请先清理背包或提升等级扩容");
        }
    }

    private void checkCapacity(Connection conn, long playerId, String itemKey) throws SQLException {
        if (getItemCount(conn, playerId, itemKey) == 0 && isInventoryFull(playerId)) {
            int capacity = getInventoryCapacity(playerId);
            throw new SQLException("背包已满！当前容量: " + capacity + " 格，请先清理背包或提升等级扩容");
        }
    }

    // ==================== 移除物品（原子操作） ====================

    /**
     * 原子移除物品。使用单条 SQL 完成检查+扣除，消除 TOCTOU 竞态条件。
     *
     * @return true 表示扣除成功，false 表示数量不足或物品不存在
     * @throws IllegalArgumentException 如果 quantity <= 0
     */
    public boolean removeItem(long playerId, String itemKey, long quantity) {
        validateRemoveParams(quantity);
        try (Connection conn = DatabaseManager.getConnection()) {
            boolean ok = removeItem(conn, playerId, itemKey, quantity);
            if (ok) {
                com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
            }
            return ok;
        } catch (SQLException e) {
            throw new RuntimeException("移除物品失败", e);
        }
    }

    /**
     * 在已有事务中原子移除物品。
     */
    public boolean removeItem(Connection conn, long playerId, String itemKey, long quantity) throws SQLException {
        validateRemoveParams(quantity);
        // 原子操作：一条 SQL 同时完成检查与扣除
        String sql = "UPDATE players_items SET quantity = quantity - ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE player_id = ? AND item_key = ? AND quantity >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, quantity);
            ps.setLong(2, playerId);
            ps.setString(3, itemKey);
            ps.setLong(4, quantity);
            int affected = ps.executeUpdate();
            if (affected > 0) {
                // 扣除成功后清理零数量行
                cleanZeroQuantity(conn, playerId, itemKey);
                return true;
            }
            return false;
        }
    }

    private void validateRemoveParams(long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于 0，当前值: " + quantity);
        }
    }

    private void cleanZeroQuantity(Connection conn, long playerId, String itemKey) throws SQLException {
        String sql = "DELETE FROM players_items WHERE player_id = ? AND item_key = ? AND quantity <= 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            ps.executeUpdate();
        }
    }

    // ==================== 批量操作 ====================

    /**
     * 批量添加物品，使用单事务保证原子性。
     */
    public boolean addItems(long playerId, Map<String, Long> items) {
        return DatabaseManager.runTransaction(conn -> {
            for (Map.Entry<String, Long> entry : items.entrySet()) {
                if (entry.getValue() <= 0) continue;
                addItem(conn, playerId, entry.getKey(), entry.getValue());
            }
            return true;
        });
    }

    /**
     * 批量移除物品，使用单事务保证原子性。任一物品不足则全部回滚。
     *
     * @return true 表示全部移除成功
     */
    public boolean removeItems(long playerId, Map<String, Long> items) {
        return DatabaseManager.runTransaction(conn -> {
            // 第一阶段：检查所有物品是否足够
            for (Map.Entry<String, Long> entry : items.entrySet()) {
                if (entry.getValue() <= 0) continue;
                long count = getItemCount(conn, playerId, entry.getKey());
                if (count < entry.getValue()) {
                    throw new SQLException("物品不足: " + entry.getKey() + " 需要 " + entry.getValue() + " 仅有 " + count);
                }
            }
            // 第二阶段：逐个扣除
            for (Map.Entry<String, Long> entry : items.entrySet()) {
                if (entry.getValue() <= 0) continue;
                if (!removeItem(conn, playerId, entry.getKey(), entry.getValue())) {
                    throw new SQLException("移除物品失败: " + entry.getKey());
                }
            }
            return true;
        });
    }

    // ==================== 查询 ====================

    public List<InventoryEntry> getInventory(long playerId) {
        String sql = "SELECT item_key, quantity FROM players_items WHERE player_id = ? AND quantity > 0 ORDER BY item_key";
        List<InventoryEntry> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String itemKey = rs.getString("item_key");
                    long quantity = rs.getLong("quantity");
                    Item item = ItemRegistry.get(itemKey);
                    if (item != null) {
                        result.add(new InventoryEntry(item, quantity));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取背包失败", e);
        }
        return result;
    }

    public long getItemCount(long playerId, String itemKey) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getItemCount(conn, playerId, itemKey);
        } catch (SQLException e) {
            throw new RuntimeException("查询物品数量失败", e);
        }
    }

    public long getItemCount(Connection conn, long playerId, String itemKey) throws SQLException {
        String sql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("quantity");
                }
            }
        }
        return 0;
    }

    public boolean hasItem(long playerId, String itemKey, long quantity) {
        return getItemCount(playerId, itemKey) >= quantity;
    }

    // ==================== 灵石便捷方法 ====================

    public long getSpiritStoneCount(long playerId) {
        return getTotalSpiritStonesInLowGrade(playerId);
    }

    /**
     * 事务内查询灵石总量（与外部连接同事务，保证一致性）。
     * 必须在事务内调用。
     */
    public long getSpiritStoneCount(Connection conn, long playerId) throws SQLException {
        return getTotalSpiritStonesInLowGrade(conn, playerId);
    }

    public long getSpiritStoneCount(long playerId, int grade) {
        String key = CurrencyEffect.getStoneKey(grade);
        return getItemCount(playerId, key);
    }

    public long getTotalSpiritStonesInLowGrade(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getTotalSpiritStonesInLowGrade(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询灵石数量失败", e);
        }
    }

    public long getTotalSpiritStonesInLowGrade(Connection conn, long playerId) throws SQLException {
        long total = 0;
        for (int i = 0; i < 4; i++) {
            String key = CurrencyEffect.getStoneKey(i);
            long count = getItemCount(conn, playerId, key);
            total += count * CurrencyEffect.getExchangeRate(i);
        }
        long oldCount = getItemCount(conn, playerId, CurrencyEffect.SPIRIT_STONE_OLD_KEY);
        total += oldCount;
        return total;
    }

    public boolean removeSpiritStones(long playerId, long amount) {
        return removeSpiritStonesFromAllGrades(playerId, amount);
    }

    public boolean removeSpiritStones(long playerId, long amount, int grade) {
        String key = CurrencyEffect.getStoneKey(grade);
        return removeItem(playerId, key, amount);
    }

    private boolean removeSpiritStonesFromAllGrades(long playerId, long amount) {
        if (amount <= 0) return true;
        try (Connection conn = DatabaseManager.getConnection()) {
            return removeSpiritStonesFromAllGrades(conn, playerId, amount);
        } catch (SQLException e) {
            throw new RuntimeException("扣除灵石失败", e);
        }
    }

    public boolean removeSpiritStones(Connection conn, long playerId, long amount) throws SQLException {
        if (amount <= 0) return true;
        return removeSpiritStonesFromAllGrades(conn, playerId, amount);
    }

    private boolean removeSpiritStonesFromAllGrades(Connection conn, long playerId, long amount) throws SQLException {
        long remaining = amount;
        long oldCount = getItemCount(conn, playerId, CurrencyEffect.SPIRIT_STONE_OLD_KEY);
        if (oldCount > 0) {
            if (oldCount >= remaining) {
                removeItem(conn, playerId, CurrencyEffect.SPIRIT_STONE_OLD_KEY, remaining);
                return true;
            } else {
                removeItem(conn, playerId, CurrencyEffect.SPIRIT_STONE_OLD_KEY, oldCount);
                remaining -= oldCount;
            }
        }

        for (int i = 0; i < 4; i++) {
            String key = CurrencyEffect.getStoneKey(i);
            long rate = CurrencyEffect.getExchangeRate(i);
            long count = getItemCount(conn, playerId, key);
            long value = count * rate;

            // 该档位没有存量：跳过。这里必须跳过而不是照常 removeItem(…, 0) ——
            // validateRemoveParams 会拒绝数量 0，导致"手里只有中品灵石"的玩家
            // 在下品这一轮直接抛异常（灵石发放按面额从大到小分配，下品为 0 很常见）。
            if (count <= 0) {
                continue;
            }

            if (value >= remaining) {
                // 用本级灵石支付，超出部分按下品灵石找零
                long toRemove = (remaining + rate - 1) / rate;
                removeItem(conn, playerId, key, toRemove);
                long overpaid = toRemove * rate - remaining;
                if (overpaid > 0) {
                    addItem(conn, playerId, CurrencyEffect.SPIRIT_STONE_LOW, overpaid);
                }
                return true;
            } else {
                removeItem(conn, playerId, key, count);
                remaining -= value;
            }
        }
        return false;
    }

    /** 富甲一方称号检查：每玩家每日最多触发一次（内存态，避免灵石入账热点） */
    private static final java.util.Set<String> MILLIONAIRE_CHECKED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile String millionaireDate = java.time.LocalDate.now().toString();

    public boolean addSpiritStones(long playerId, long amount) {
        boolean ok = addSpiritStonesAuto(playerId, amount);
        if (ok && amount > 0) {
            checkMillionaireTitle(playerId);
        }
        return ok;
    }

    public boolean addSpiritStones(long playerId, long amount, int grade) {
        if (grade > 0) {
            long limit = CurrencyEffect.MAX_HOLD_PER_GRADE[grade];
            if (limit > 0 && getItemCount(playerId, CurrencyEffect.getStoneKey(grade)) + amount > limit) {
                throw new RuntimeException("灵石持有已达上限");
            }
        }
        // 非下品直接添加，不合并
        String key = CurrencyEffect.getStoneKey(grade);
        boolean ok = addItem(playerId, key, amount);
        if (ok && amount > 0) {
            checkMillionaireTitle(playerId);
        }
        return ok;
    }

    private void checkMillionaireTitle(long playerId) {
        try {
            String today = java.time.LocalDate.now().toString();
            if (!today.equals(millionaireDate)) {
                MILLIONAIRE_CHECKED.clear();
                millionaireDate = today;
            }
            if (!MILLIONAIRE_CHECKED.add(playerId + ":" + today)) {
                return;
            }
            // 灵石价值 ≥ 100 万授予「富甲一方」
            if (getSpiritStoneCount(playerId) >= 1_000_000L) {
                com.mtxgdn.entity.Player p = playerService.getPlayerById(playerId);
                if (p != null) {
                    new com.mtxgdn.game.service.TitleService().checkAndGrantTitles(p);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public boolean addSpiritStones(Connection conn, long playerId, long amount) throws SQLException {
        return addSpiritStonesAuto(conn, playerId, amount);
    }

    public boolean addSpiritStones(Connection conn, long playerId, long amount, int grade) throws SQLException {
        if (grade > 0) {
            long limit = CurrencyEffect.MAX_HOLD_PER_GRADE[grade];
            if (limit > 0) {
                long current = getItemCount(conn, playerId, CurrencyEffect.getStoneKey(grade));
                if (current + amount > limit) {
                    throw new SQLException("灵石持有已达上限");
                }
            }
        }
        String key = CurrencyEffect.getStoneKey(grade);
        return addItem(conn, playerId, key, amount);
    }

    /**
     * 以"下品灵石等值"发放灵石：一律先记在下品，再按合并阈值向上压缩。
     *
     * <p>灵石的四档是<b>面额</b>而不是稀有度（1 极品 = 10 亿下品），所以高等级灵石
     * 只应该作为"存量过大的紧凑表示"出现，不该因为"这次奖励发得多"就凭空变成极品。
     * 旧实现从极品往下填（先塞满 99 个极品，再 9999 个上品…），结果是同样持有 10 亿
     * 灵石的两个人：领 1000 次小奖励的手里全是下品，领 1 次大奖励的手里是 1 个极品 ——
     * 极品成了"上次一次性拿了多少"的副产品，与难度、稀有度毫无关系。
     * 而全库没有任何内容直接发放中品/上品/极品，所以这条路是极品唯一的来源。</p>
     */
    private boolean addSpiritStonesAuto(long playerId, long amount) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addSpiritStonesAuto(conn, playerId, amount);
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("发放灵石失败", e);
        }
    }

    private boolean addSpiritStonesAuto(Connection conn, long playerId, long amount) throws SQLException {
        if (amount <= 0) return true;
        // 下品灵石没有持有上限（MAX_HOLD_PER_GRADE[0] == 0），先全量落账最省事也最不会算错，
        // 再交给 compressSpiritStones 决定要不要折成更高面额。
        addItem(conn, playerId, CurrencyEffect.SPIRIT_STONE_LOW, amount);
        compressSpiritStones(conn, playerId);
        return true;
    }

    /**
     * 按 {@link CurrencyEffect#CONSOLIDATE_THRESHOLD_LOW} 把超额灵石向上一级压缩：
     * <ul>
     *   <li>下品等值 ≥ 2000 → 折算为中品</li>
     *   <li>中品等值 ≥ 200 万 → 折算为上品</li>
     *   <li>上品等值 ≥ 20 亿 → 折算为极品</li>
     * </ul>
     *
     * <p>规则是<b>每档保留阈值以内的存量，只把超出的部分换成上一档</b>。
     * 刻意留够阈值以内的量：炼制配方点名要 {@code mtxgdn:spirit_stone_low}
     * （锻造灵剑要 3 个下品），手里必须常备一些下品，否则会出现
     * "揣着 1 个极品（=10 亿下品）却买不起一把需要 3 下品的灵剑"。</p>
     *
     * <p>压缩只按面额与阈值换算，<b>不受 {@link CurrencyEffect#MAX_HOLD_PER_GRADE} 约束</b>：
     * 那些上限是"显式发放某一档"时的护栏（见 {@link #addSpiritStones(long, long, int)}）。
     * 若在压缩里也套上限，中品 999999 的上限会变成整条阶梯的瓶颈 ——
     * 中品满仓后多余的灵石再也换不成上品，10 亿以上的存量会被永久堵在下品里，
     * 恰好和"压缩"的目的相反。</p>
     */
    public void compressSpiritStones(Connection conn, long playerId) throws SQLException {
        for (int grade = 0; grade <= 2; grade++) {
            long rate = CurrencyEffect.getExchangeRate(grade);
            long per = CurrencyEffect.getExchangeRate(grade + 1) / rate;
            if (per <= 1) continue;

            long threshold = CurrencyEffect.CONSOLIDATE_THRESHOLD_LOW[grade];
            if (threshold <= 0) continue;

            long count = getItemCount(conn, playerId, CurrencyEffect.getStoneKey(grade));
            if (count * rate <= threshold) continue;

            long keepCount = threshold / rate;
            long mergeUnits = (count - keepCount) / per;
            if (mergeUnits <= 0) continue;

            removeItem(conn, playerId, CurrencyEffect.getStoneKey(grade), mergeUnits * per);
            addItem(conn, playerId, CurrencyEffect.getStoneKey(grade + 1), mergeUnits);
        }
    }
    public Map<String, Object> exchangeSpiritStones(long playerId, int fromGrade, int toGrade, long amount) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (fromGrade < 0 || fromGrade > 3 || toGrade < 0 || toGrade > 3) {
            result.put("success", false);
            result.put("message", I18n.t("item.ha68b3ef0"));
            return result;
        }
        if (fromGrade <= toGrade) {
            result.put("success", false);
            result.put("message", I18n.t("item.hc1802354"));
            return result;
        }
        if (amount <= 0) {
            result.put("success", false);
            result.put("message", I18n.t("item.h855d6fe8"));
            return result;
        }

        String fromKey = CurrencyEffect.getStoneKey(fromGrade);
        if (getItemCount(playerId, fromKey) < amount) {
            result.put("success", false);
            result.put("message", I18n.t("item.hf663041d"));
            return result;
        }

        long rate = CurrencyEffect.getExchangeRate(fromGrade) / CurrencyEffect.getExchangeRate(toGrade);
        long targetAmount = amount * rate;

        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> innerResult = new LinkedHashMap<>();
            try {
                removeItem(conn, playerId, fromKey, amount);
                String toKey = CurrencyEffect.getStoneKey(toGrade);
                addItem(conn, playerId, toKey, targetAmount);
                innerResult.put("success", true);
                innerResult.put("fromAmount", amount);
                innerResult.put("fromGrade", fromGrade);
                innerResult.put("toAmount", targetAmount);
                innerResult.put("toGrade", toGrade);
                // 占位符顺序换成"先数量后名字"，让模板只描述一种语序；
                // 原 %d %s 的顺序在不同语言里可能都要调
                innerResult.put("message", I18n.t("item.stone_converted",
                        amount, getStoneName(fromGrade), targetAmount, getStoneName(toGrade)));
            } catch (SQLException e) {
                innerResult.put("success", false);
                innerResult.put("message", I18n.t("item.h35c6c480", e.getMessage()));
            }
            return innerResult;
        });
    }

    private String getStoneName(int grade) {
        return switch (grade) {
            case 1 -> "中品灵石";
            case 2 -> "上品灵石";
            case 3 -> "极品灵石";
            default -> "下品灵石";
        };
    }

    // ==================== 装备系统 ====================

    /**
     * 装备物品。使用事务包裹：先写数据库，再执行效果。
     * 如果效果执行失败，回滚数据库变更。
     */
    public Map<String, Object> equipItem(long playerId, String itemKey, String slot) {
        Map<String, Object> result = new LinkedHashMap<>();
        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            result.put("success", false);
            result.put("message", I18n.t("item.ha1976553"));
            return result;
        }
        String fullKey = item.getFullKey();

        try {
            return DatabaseManager.runTransaction(conn -> {
                Map<String, Object> innerResult = new LinkedHashMap<>();

                if (getItemCount(conn, playerId, fullKey) < 1) {
                    innerResult.put("success", false);
                    innerResult.put("message", I18n.t("item.h275bc0df"));
                    return innerResult;
                }

                String occupied = getEquippedItem(conn, playerId, slot);
                if (occupied != null && occupied.equals(fullKey)) {
                    innerResult.put("success", false);
                    innerResult.put("message", I18n.t("item.h058f56b2"));
                    return innerResult;
                }

                // 1. 回退旧装备效果
                if (occupied != null) {
                    Item oldItem = ItemRegistry.get(occupied);
                    if (oldItem != null) {
                        for (ItemEffect effect : oldItem.getEffects()) {
                            if (effect instanceof BuffEffect be) {
                                if (be.getAttackBonus() > 0) playerService.addAttack(conn, playerId, -be.getAttackBonus());
                                if (be.getDefenseBonus() > 0) playerService.addDefense(conn, playerId, -be.getDefenseBonus());
                                if (be.getSpeedBonus() > 0) playerService.addSpeed(conn, playerId, -be.getSpeedBonus());
                                if (be.getSpiritBonus() > 0) playerService.addSpirit(conn, playerId, -be.getSpiritBonus());
                            }
                        }
                        addItem(conn, playerId, occupied, 1);
                    }
                }

                // 2. 从背包移除
                if (!removeItem(conn, playerId, fullKey, 1)) {
                    throw new SQLException("背包物品扣除失败: " + fullKey);
                }

                // 3. 写入装备表
                String upsertSql;
                if (DatabaseManager.isSqlite()) {
                    upsertSql = """
                        INSERT INTO players_equipment (player_id, slot, item_key)
                        VALUES (?, ?, ?)
                        ON CONFLICT(player_id, slot) DO UPDATE SET item_key = excluded.item_key,
                            updated_at = CURRENT_TIMESTAMP
                        """;
                } else {
                    upsertSql = """
                        INSERT INTO players_equipment (player_id, slot, item_key)
                        VALUES (?, ?, ?)
                        ON DUPLICATE KEY UPDATE item_key = VALUES(item_key),
                            updated_at = CURRENT_TIMESTAMP
                        """;
                }
                try (PreparedStatement ps = conn.prepareStatement(upsertSql)) {
                    ps.setLong(1, playerId);
                    ps.setString(2, slot);
                    ps.setString(3, fullKey);
                    ps.executeUpdate();
                }

                // 4. 执行新装备效果（在数据库写入成功后）
                StringBuilder msgBuilder = new StringBuilder();
                for (ItemEffect effect : item.getEffects()) {
                    String effectMsg;
                    if (effect instanceof BuffEffect be) {
                        effectMsg = be.execute(conn, playerId, playerService, this);
                    } else {
                        effectMsg = effect.execute(playerId, playerService, this);
                    }
                    if (effectMsg != null && !effectMsg.isEmpty()) {
                        msgBuilder.append(effectMsg);
                    }
                }

                String itemDisplay = item.getName() + " (" + fullKey + ")";
                String msg;
                if (msgBuilder.length() > 0) {
                    msg = "装备了【" + itemDisplay + "】: " + msgBuilder.toString();
                    if (msg.endsWith("，")) {
                        msg = msg.substring(0, msg.length() - 1);
                    }
                } else {
                    msg = "装备了【" + itemDisplay + "】";
                }

                innerResult.put("success", true);
                innerResult.put("message", msg);
                return innerResult;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", I18n.t("item.hcb33d7f8", e.getMessage()));
            return result;
        }
    }

    /**
     * 卸下装备。使用事务包裹。
     */
    public Map<String, Object> unequipItem(long playerId, String slot) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            return DatabaseManager.runTransaction(conn -> {
                Map<String, Object> innerResult = new LinkedHashMap<>();

                String itemKey = getEquippedItem(conn, playerId, slot);
                if (itemKey == null) {
                    innerResult.put("success", false);
                    innerResult.put("message", I18n.t("item.h3b242f48"));
                    return innerResult;
                }
                Item item = ItemRegistry.get(itemKey);
                if (item == null) {
                    innerResult.put("success", false);
                    innerResult.put("message", I18n.t("item.h7132a04c"));
                    return innerResult;
                }

                // 耐久归零的装备不允许卸下：否则"损坏→卸下→装上"就能免费重置耐久，
                // 修理系统会被彻底绕过（耐久表是按 玩家+物品key 记的，卸下并不会清记录）。
                if (com.mtxgdn.common.service.ServiceRegistry.getDurabilityService()
                        .getDurability(conn, playerId, itemKey) <= 0) {
                    innerResult.put("success", false);
                    innerResult.put("message", I18n.t("equip.unequip_broken",
                            DurabilityService.getSlotDisplayName(slot)));
                    return innerResult;
                }

                // 1. 从装备表删除
                String delSql = "DELETE FROM players_equipment WHERE player_id = ? AND slot = ?";
                try (PreparedStatement ps = conn.prepareStatement(delSql)) {
                    ps.setLong(1, playerId);
                    ps.setString(2, slot);
                    ps.executeUpdate();
                }

                // 2. 物品回到背包
                addItem(conn, playerId, itemKey, 1);

                // 3. 回退装备效果
                StringBuilder msgBuilder = new StringBuilder();
                for (ItemEffect effect : item.getEffects()) {
                    if (effect instanceof BuffEffect be) {
                        if (be.getAttackBonus() > 0) {
                            playerService.addAttack(conn, playerId, -be.getAttackBonus());
                            msgBuilder.append("攻击力 -").append(be.getAttackBonus()).append("，");
                        }
                        if (be.getDefenseBonus() > 0) {
                            playerService.addDefense(conn, playerId, -be.getDefenseBonus());
                            msgBuilder.append("防御力 -").append(be.getDefenseBonus()).append("，");
                        }
                        if (be.getSpeedBonus() > 0) {
                            playerService.addSpeed(conn, playerId, -be.getSpeedBonus());
                            msgBuilder.append("速度 -").append(be.getSpeedBonus()).append("，");
                        }
                        if (be.getSpiritBonus() > 0) {
                            playerService.addSpirit(conn, playerId, -be.getSpiritBonus());
                            msgBuilder.append("灵力 -").append(be.getSpiritBonus()).append("，");
                        }
                    }
                }

                String msg;
                if (msgBuilder.length() > 0) {
                    msg = "卸下了【" + item.getName() + "】: " + msgBuilder.toString();
                    if (msg.endsWith("，")) {
                        msg = msg.substring(0, msg.length() - 1);
                    }
                } else {
                    msg = "卸下了【" + item.getName() + "】";
                }

                innerResult.put("success", true);
                innerResult.put("message", msg);
                return innerResult;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", I18n.t("item.h45b8f447", e.getMessage()));
            return result;
        }
    }

    public String getEquippedItem(long playerId, String slot) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getEquippedItem(conn, playerId, slot);
        } catch (SQLException e) {
            throw new RuntimeException("查询装备失败", e);
        }
    }

    private String getEquippedItem(Connection conn, long playerId, String slot) throws SQLException {
        String sql = "SELECT item_key FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("item_key");
                }
            }
        }
        return null;
    }

    public Map<String, String> getEquipment(long playerId) {
        Map<String, String> result = new LinkedHashMap<>();
        String sql = "SELECT slot, item_key FROM players_equipment WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("slot"), rs.getString("item_key"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取装备列表失败", e);
        }
        return result;
    }

    // ==================== 使用物品 ====================

    /**
     * 使用物品。使用事务包裹：先锁定物品，检查数量，执行效果，成功后再移除物品。
     * 如果效果执行失败，物品不会丢失。使用事务锁防止并发重复使用。
     */
    public Map<String, Object> useItem(long playerId, String itemKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            result.put("success", false);
            result.put("message", I18n.t("item.hadf213b8"));
            return result;
        }
        String fullKey = item.getFullKey();

        // 阵图物品（聚灵阵图/护山大阵图/净化阵图）：使用 = 消耗 1 张布置对应 1 级阵法（不另扣灵石）
        if (com.mtxgdn.game.service.FormationService.treasureKeyToFormationKey(fullKey) != null) {
            return com.mtxgdn.common.service.ServiceRegistry.getFormationService()
                    .placeFormationFromTreasure(playerId, fullKey);
        }

        try {
            return DatabaseManager.runTransaction(conn -> {
                Map<String, Object> innerResult = new LinkedHashMap<>();

                // 使用 SELECT FOR UPDATE 锁定物品，防止并发重复使用
                String lockSql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ? " +
                    DatabaseManager.getDialect().getForUpdateClause();
                try (java.sql.PreparedStatement ps = conn.prepareStatement(lockSql)) {
                    ps.setLong(1, playerId);
                    ps.setString(2, fullKey);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getLong("quantity") < 1) {
                            innerResult.put("success", false);
                            innerResult.put("message", I18n.t("item.h275bc0df"));
                            return innerResult;
                        }
                    }
                }

                // 1. 先执行效果（如果失败则回滚，物品不会丢失）
                StringBuilder msgBuilder = new StringBuilder();
                for (ItemEffect effect : item.getEffects()) {
                    String effectMsg;
                    if (effect instanceof BuffEffect be) {
                        effectMsg = be.execute(conn, playerId, playerService, this);
                    } else {
                        effectMsg = effect.execute(playerId, playerService, this);
                    }
                    if (effectMsg != null && !effectMsg.isEmpty()) {
                        msgBuilder.append(effectMsg);
                    }
                }

                // 2. 再从背包移除
                if (!removeItem(conn, playerId, fullKey, 1)) {
                    throw new SQLException("物品扣除失败: " + fullKey);
                }

                String itemDisplay = item.getName() + " (" + fullKey + ")";
                String msg;
                if (msgBuilder.length() > 0) {
                    msg = "使用了【" + itemDisplay + "】: " + msgBuilder.toString();
                    if (msg.endsWith("，")) {
                        msg = msg.substring(0, msg.length() - 1);
                    }
                } else {
                    msg = "使用了【" + itemDisplay + "】";
                }

                innerResult.put("success", true);
                innerResult.put("message", msg);
                innerResult.put("item", item);
                innerResult.put("effects", item.getEffects());

                // 插件事件：物品使用成功
                com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                        com.mtxgdn.plugin.event.PluginEvent.builder()
                                .type(com.mtxgdn.plugin.event.PluginEvent.Type.ITEM_USED)
                                .data("playerId", playerId)
                                .data("itemKey", fullKey)
                                .build());

                return innerResult;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", I18n.t("item.h0510fbe7", e.getMessage()));
            return result;
        }
    }

    // ==================== 内部类 ====================

    public static class InventoryEntry {
        private final Item item;
        private final long quantity;

        InventoryEntry(Item item, long quantity) {
            this.item = item;
            this.quantity = quantity;
        }

        public Item getItem() {
            return item;
        }

        public long getQuantity() {
            return quantity;
        }
    }
}

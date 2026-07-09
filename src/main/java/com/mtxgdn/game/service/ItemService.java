package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.CurrencyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemEffect;
import com.mtxgdn.game.item.ItemRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ItemService {

    private final PlayerService playerService = new PlayerService();

    // ==================== 添加物品 ====================

    /**
     * 添加物品到背包。使用 upsert：存在则累加数量，不存在则插入。
     *
     * @throws IllegalArgumentException 如果 quantity <= 0 或 itemKey 未注册
     */
    public boolean addItem(long playerId, String itemKey, long quantity) {
        validateAddParams(itemKey, quantity);
        try (Connection conn = DatabaseManager.getConnection()) {
            return addItem(conn, playerId, itemKey, quantity);
        } catch (SQLException e) {
            throw new RuntimeException("添加物品失败", e);
        }
    }

    /**
     * 在已有事务中批量添加物品，使用外部 Connection。
     */
    public boolean addItem(Connection conn, long playerId, String itemKey, long quantity) throws SQLException {
        validateAddParams(itemKey, quantity);
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
            ps.setString(2, itemKey);
            ps.setLong(3, quantity);
            return ps.executeUpdate() > 0;
        }
    }

    private void validateAddParams(String itemKey, long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于 0，当前值: " + quantity);
        }
        if (!ItemRegistry.contains(itemKey)) {
            throw new IllegalArgumentException("物品不存在: " + itemKey);
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
            return removeItem(conn, playerId, itemKey, quantity);
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

    private long getItemCount(Connection conn, long playerId, String itemKey) throws SQLException {
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

    public long getSpiritStoneCount(long playerId, int grade) {
        String key = CurrencyEffect.getStoneKey(grade);
        return getItemCount(playerId, key);
    }

    public long getTotalSpiritStonesInLowGrade(long playerId) {
        long total = 0;
        for (int i = 0; i < 4; i++) {
            String key = CurrencyEffect.getStoneKey(i);
            long count = getItemCount(playerId, key);
            total += count * CurrencyEffect.getExchangeRate(i);
        }
        long oldCount = getItemCount(playerId, CurrencyEffect.SPIRIT_STONE_OLD_KEY);
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
        long remaining = amount;

        try (Connection conn = DatabaseManager.getConnection()) {
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

                if (value >= remaining) {
                    long toRemove = (remaining + rate - 1) / rate;
                    removeItem(conn, playerId, key, toRemove);
                    if (remaining % rate > 0 && i < 3) {
                        addItem(conn, playerId, CurrencyEffect.getStoneKey(i + 1), remaining / rate);
                    }
                    return true;
                } else {
                    removeItem(conn, playerId, key, count);
                    remaining -= value;
                }
            }
            return false;
        } catch (SQLException e) {
            throw new RuntimeException("扣除灵石失败", e);
        }
    }

    public boolean addSpiritStones(long playerId, long amount) {
        return addSpiritStonesAuto(playerId, amount);
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
        return addItem(playerId, key, amount);
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
     * 以"下品灵石等值"发放灵石，自动按最优面额存入背包。
     * 不触发自动合并，灵石保持原始等级。
     * 同时检查各等级硬上限，溢出部分留在本级不合并。
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

        // 按面额从大到小分配，优先用高等级灵石，受硬上限约束
        long remaining = amount;
        for (int grade = 3; grade >= 0; grade--) {
            long rate = CurrencyEffect.getExchangeRate(grade);
            long maxHold = CurrencyEffect.MAX_HOLD_PER_GRADE[grade];
            String key = CurrencyEffect.getStoneKey(grade);

            long current = getItemCount(conn, playerId, key);
            long canAdd = (maxHold > 0) ? Math.max(0, maxHold - current) : Long.MAX_VALUE;
            long maxByLimit = canAdd * rate;

            long useFromThis = Math.min(remaining / rate * rate, maxByLimit);
            long countThis = useFromThis / rate;

            if (countThis > 0) {
                addItem(conn, playerId, key, countThis);
                remaining -= countThis * rate;
            }

            if (remaining < rate) break;
        }

        // 剩余尾数用下品灵石补齐
        if (remaining > 0) {
            addItem(conn, playerId, CurrencyEffect.SPIRIT_STONE_LOW, remaining);
        }

        return true;
    }

    /**
     * 将玩家背包中所有灵石按合并阈值向上一级压缩：
     *   - 下品 ≥2000等值 → 合并为中品
     *   - 中品 ≥2000000等值 → 合并为上品
     *   - 上品 ≥2000000000等值 → 合并为极品
     * 合并时受上级硬上限约束。
     */
    private void consolidateAll(Connection conn, long playerId) throws SQLException {
        for (int grade = 0; grade < 3; grade++) {
            long threshold = CurrencyEffect.CONSOLIDATE_THRESHOLD_LOW[grade];
            if (threshold == 0) continue;

            int nextGrade = grade + 1;
            long rate = CurrencyEffect.getExchangeRate(nextGrade);
            String lowerKey = CurrencyEffect.getStoneKey(grade);
            String higherKey = CurrencyEffect.getStoneKey(nextGrade);

            long lowerCount = getItemCount(conn, playerId, lowerKey);
            long lowerValue = lowerCount * CurrencyEffect.getExchangeRate(grade);

            if (lowerValue >= threshold) {
                long maxUp = CurrencyEffect.MAX_HOLD_PER_GRADE[nextGrade];
                long currentUp = getItemCount(conn, playerId, higherKey);
                long canUp = (maxUp > 0) ? Math.max(0, maxUp - currentUp) : Long.MAX_VALUE;

                long totalCanMergeValue = lowerValue / rate;
                long actualToUpCount = Math.min(totalCanMergeValue, canUp);
                long actualRemoveLower = actualToUpCount * rate / CurrencyEffect.getExchangeRate(grade);

                if (actualToUpCount > 0 && actualRemoveLower > 0) {
                    long realRemove = Math.min(actualRemoveLower, lowerCount);
                    removeItem(conn, playerId, lowerKey, realRemove);
                    addItem(conn, playerId, higherKey, actualToUpCount);
                }
            }
        }
    }

    public Map<String, Object> exchangeSpiritStones(long playerId, int fromGrade, int toGrade, long amount) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (fromGrade < 0 || fromGrade > 3 || toGrade < 0 || toGrade > 3) {
            result.put("success", false);
            result.put("message", "无效的灵石等级");
            return result;
        }
        if (fromGrade <= toGrade) {
            result.put("success", false);
            result.put("message", "只能将高等级灵石兑换为低等级灵石");
            return result;
        }
        if (amount <= 0) {
            result.put("success", false);
            result.put("message", "兑换数量必须大于0");
            return result;
        }

        String fromKey = CurrencyEffect.getStoneKey(fromGrade);
        if (getItemCount(playerId, fromKey) < amount) {
            result.put("success", false);
            result.put("message", "灵石不足");
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
                innerResult.put("message", String.format("兑换成功！%d %s → %d %s",
                        amount, getStoneName(fromGrade), targetAmount, getStoneName(toGrade)));
            } catch (SQLException e) {
                innerResult.put("success", false);
                innerResult.put("message", "兑换失败: " + e.getMessage());
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
            result.put("message", "物品不存在，请使用 /物品列表 查看可用物品");
            return result;
        }
        String fullKey = item.getFullKey();

        try {
            return DatabaseManager.runTransaction(conn -> {
                Map<String, Object> innerResult = new LinkedHashMap<>();

                if (!hasItem(playerId, fullKey, 1)) {
                    innerResult.put("success", false);
                    innerResult.put("message", "背包中没有该物品");
                    return innerResult;
                }

                String occupied = getEquippedItem(conn, playerId, slot);
                if (occupied != null && occupied.equals(fullKey)) {
                    innerResult.put("success", false);
                    innerResult.put("message", "已装备该物品");
                    return innerResult;
                }

                // 1. 回退旧装备效果
                if (occupied != null) {
                    Item oldItem = ItemRegistry.get(occupied);
                    if (oldItem != null) {
                        for (ItemEffect effect : oldItem.getEffects()) {
                            if (effect instanceof BuffEffect be) {
                                if (be.getAttackBonus() > 0) playerService.addAttack(playerId, -be.getAttackBonus());
                                if (be.getDefenseBonus() > 0) playerService.addDefense(playerId, -be.getDefenseBonus());
                                if (be.getSpeedBonus() > 0) playerService.addSpeed(playerId, -be.getSpeedBonus());
                                if (be.getSpiritBonus() > 0) playerService.addSpirit(playerId, -be.getSpiritBonus());
                            }
                        }
                        addItem(conn, playerId, occupied, 1);
                    }
                }

                // 2. 从背包移除
                if (!removeItem(conn, playerId, fullKey, 1)) {
                    // 回滚旧装备
                    if (occupied != null) {
                        Item oldItem = ItemRegistry.get(occupied);
                        if (oldItem != null) {
                            removeItem(conn, playerId, occupied, 1);
                            for (ItemEffect effect : oldItem.getEffects()) {
                                if (effect instanceof BuffEffect be) {
                                    if (be.getAttackBonus() > 0) playerService.addAttack(playerId, be.getAttackBonus());
                                    if (be.getDefenseBonus() > 0) playerService.addDefense(playerId, be.getDefenseBonus());
                                    if (be.getSpeedBonus() > 0) playerService.addSpeed(playerId, be.getSpeedBonus());
                                    if (be.getSpiritBonus() > 0) playerService.addSpirit(playerId, be.getSpiritBonus());
                                }
                            }
                        }
                    }
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
                    String effectMsg = effect.execute(playerId, playerService, this);
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
            result.put("message", "装备失败: " + e.getMessage());
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
                    innerResult.put("message", "该装备栏位为空");
                    return innerResult;
                }
                Item item = ItemRegistry.get(itemKey);
                if (item == null) {
                    innerResult.put("success", false);
                    innerResult.put("message", "物品数据异常");
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
                            playerService.addAttack(playerId, -be.getAttackBonus());
                            msgBuilder.append("攻击力 -").append(be.getAttackBonus()).append("，");
                        }
                        if (be.getDefenseBonus() > 0) {
                            playerService.addDefense(playerId, -be.getDefenseBonus());
                            msgBuilder.append("防御力 -").append(be.getDefenseBonus()).append("，");
                        }
                        if (be.getSpeedBonus() > 0) {
                            playerService.addSpeed(playerId, -be.getSpeedBonus());
                            msgBuilder.append("速度 -").append(be.getSpeedBonus()).append("，");
                        }
                        if (be.getSpiritBonus() > 0) {
                            playerService.addSpirit(playerId, -be.getSpiritBonus());
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
            result.put("message", "卸下装备失败: " + e.getMessage());
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
     * 使用物品。使用事务包裹：先执行效果，成功后再移除物品。
     * 如果效果执行失败，物品不会丢失。
     */
    public Map<String, Object> useItem(long playerId, String itemKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            result.put("success", false);
            result.put("message", "物品不存在");
            return result;
        }
        String fullKey = item.getFullKey();

        try {
            return DatabaseManager.runTransaction(conn -> {
                Map<String, Object> innerResult = new LinkedHashMap<>();

                if (getItemCount(conn, playerId, fullKey) < 1) {
                    innerResult.put("success", false);
                    innerResult.put("message", "背包中没有该物品");
                    return innerResult;
                }

                // 1. 先执行效果（如果失败则回滚，物品不会丢失）
                StringBuilder msgBuilder = new StringBuilder();
                for (ItemEffect effect : item.getEffects()) {
                    String effectMsg = effect.execute(playerId, playerService, this);
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
                return innerResult;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "使用物品失败: " + e.getMessage());
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

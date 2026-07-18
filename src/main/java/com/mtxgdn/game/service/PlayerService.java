package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.config.NewbieRewardConfig;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.game.title.TitleRegistry;

import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PlayerService {

    private static final GameLogger LOG = GameLogger.getLogger(PlayerService.class);

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        scheduler.scheduleAtFixedRate(PlayerService::tickAutoHeal, 10, 10, TimeUnit.SECONDS);
        LOG.info("玩家自动回血任务已启动（每10秒回复1%最大生命值）");
    }

    public static void shutdown() {
        scheduler.shutdown();
        LOG.info("玩家自动回血任务已停止");
    }

    public PlayerInfo getPlayerByUserId(long userId) {
        String sql = "SELECT * FROM players WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPlayerInfo(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
        return null;
    }

    public Player getPlayerById(long playerId) {
        String sql = "SELECT * FROM players WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPlayer(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
        return null;
    }

    public Player getPlayerRaw(long userId) {
        String sql = "SELECT * FROM players WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPlayer(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
        return null;
    }

    public PlayerInfo createPlayer(long userId, String name) {
        SpiritualRoot root = SpiritualRoot.drawRandom(new java.util.Random());
        String sql = """
            INSERT INTO players (user_id, name, spiritual_root, level, experience, realm, sub_realm,
                hp, max_hp, mp, max_mp, attack, defense, speed, spirit,
                gold, cultivation_progress, is_cultivating, cultivation_start_time, last_secret_realm_time, last_exploration_time, tutorial_step, tutorial_tips, current_location_id)
            VALUES (?, ?, ?, 1, 0, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 1, 0, 1)
            """;
        long playerId = -1;
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, userId);
                ps.setString(2, name);
                ps.setString(3, root.name());
                ps.setInt(4, root.applyHpBonus(100));
                ps.setInt(5, root.applyHpBonus(100));
                ps.setInt(6, root.applyMpBonus(50));
                ps.setInt(7, root.applyMpBonus(50));
                ps.setInt(8, root.applyAttackBonus(10));
                ps.setInt(9, root.applyDefenseBonus(5));
                ps.setInt(10, root.applySpeedBonus(5));
                ps.setInt(11, root.applySpiritBonus(5));
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        playerId = rs.getLong(1);
                    }
                }
            }

            NewbieRewardConfig rewardConfig = GameConfigLoader.getNewbieRewardConfig();
            if (rewardConfig.isEnabled()) {
                ItemService itemService = new ItemService();

                if (rewardConfig.getGoldReward() > 0) {
                    addGold(conn, playerId, rewardConfig.getGoldReward());
                }

                if (rewardConfig.getSpiritStoneReward() > 0) {
                    itemService.addSpiritStones(conn, playerId, rewardConfig.getSpiritStoneReward(), rewardConfig.getSpiritStoneGrade());
                }

                if (rewardConfig.getItems() != null && !rewardConfig.getItems().isEmpty()) {
                    for (NewbieRewardConfig.RewardItem item : rewardConfig.getItems()) {
                        if (item.getItemKey() != null && !item.getItemKey().isBlank() && item.getQuantity() > 0) {
                            itemService.addItem(conn, playerId, item.getItemKey(), item.getQuantity());
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("创建玩家失败: " + e.getMessage(), e);
        }
        return getPlayerByUserId(userId);
    }

    public void updatePlayer(long playerId, Player player) {
        String sql = """
            UPDATE players SET level = ?, experience = ?, realm = ?, sub_realm = ?,
                hp = ?, max_hp = ?, mp = ?, max_mp = ?, attack = ?, defense = ?,
                speed = ?, spirit = ?, gold = ?,
                cultivation_progress = ?, is_cultivating = ?, cultivation_start_time = ?,
                last_secret_realm_time = ?, last_exploration_time = ?, last_offline_time = ?,
                last_breakthrough_time = ?
            WHERE id = ?
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, player.getLevel());
            ps.setLong(2, player.getExperience());
            ps.setInt(3, player.getRealm());
            ps.setInt(4, player.getSubRealm());
            ps.setInt(5, player.getHp());
            ps.setInt(6, player.getMaxHp());
            ps.setInt(7, player.getMp());
            ps.setInt(8, player.getMaxMp());
            ps.setInt(9, player.getAttack());
            ps.setInt(10, player.getDefense());
            ps.setInt(11, player.getSpeed());
            ps.setInt(12, player.getSpirit());
            ps.setLong(13, player.getGold());
            ps.setInt(14, player.getCultivationProgress());
            ps.setBoolean(15, player.isCultivating());
            ps.setLong(16, player.getCultivationStartTime());
            ps.setLong(17, player.getLastSecretRealmTime());
            ps.setLong(18, player.getLastExplorationTime());
            ps.setLong(19, player.getLastOfflineTime());
            ps.setLong(20, player.getLastBreakthroughTime());
            ps.setLong(21, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新玩家失败", e);
        }
    }

    public boolean updatePlayerName(long playerId, String newName) {
        if (newName == null || newName.trim().isEmpty()) {
            return false;
        }
        String sql = "UPDATE players SET name = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newName.trim());
            ps.setLong(2, playerId);
            int affected = ps.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            throw new RuntimeException("修改玩家名称失败", e);
        }
    }

    public void addExperience(long playerId, long exp) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addExperience(conn, playerId, exp);
        } catch (SQLException e) {
            throw new RuntimeException("增加经验失败", e);
        }
    }

    public void addExperience(Connection conn, long playerId, long exp) throws SQLException {
        Player player = getPlayerById(playerId);
        if (player != null) {
            exp = (long) (exp * getFinalExpBonus(player));
        }
        String sql = "UPDATE players SET experience = experience + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, exp);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    public void updateCultivationProgress(long playerId, int progress) {
        String sql = "UPDATE players SET cultivation_progress = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, progress);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新修炼进度失败", e);
        }
    }

    public void setCultivating(long playerId, boolean cultivating) {
        String sql;
        if (cultivating) {
            sql = "UPDATE players SET is_cultivating = ?, cultivation_start_time = ?, last_offline_time = 0 WHERE id = ?";
        } else {
            sql = "UPDATE players SET is_cultivating = ?, cultivation_start_time = 0 WHERE id = ?";
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, cultivating);
            if (cultivating) {
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, playerId);
            } else {
                ps.setLong(2, playerId);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新修炼状态失败", e);
        }
    }

    public void setTutorialStep(long playerId, int step) {
        String sql = "UPDATE players SET tutorial_step = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, step);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新引导步骤失败", e);
        }
    }

    public void setTutorialTips(long playerId, int tips) {
        String sql = "UPDATE players SET tutorial_tips = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tips);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新引导提示标记失败", e);
        }
    }

    public boolean existsByUserId(long userId) {
        String sql = "SELECT COUNT(*) FROM players WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("检查玩家存在失败", e);
        }
        return false;
    }

    public void addHp(long playerId, int amount) {
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE players SET hp = MIN(hp + ?, max_hp) WHERE id = ?"
                : "UPDATE players SET hp = LEAST(hp + ?, max_hp) WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("恢复生命值失败", e);
        }
    }

    public Map<String, Object> healPlayer(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", "角色不存在");
            return result;
        }
        if (player.getHp() >= player.getMaxHp()) {
            result.put("success", true);
            result.put("message", "你的生命值已满，无需治疗。");
            return result;
        }

        long cost = (player.getRealm() + 1) * 50L;
        long currentStones = getSpiritStoneCount(playerId);

        if (currentStones < cost) {
            result.put("success", false);
            result.put("message", "灵石不足！疗伤需要 " + cost + " 灵石，你只有 " + currentStones + " 灵石。\n"
                    + "你可以服用回血丹回复生命，或等待自然恢复。");
            result.put("cost", cost);
            result.put("currentStones", currentStones);
            return result;
        }

        if (!removeSpiritStoneRaw(playerId, cost)) {
            result.put("success", false);
            result.put("message", "扣除灵石失败，请重试。");
            return result;
        }

        int hpBefore = player.getHp();
        String sql = "UPDATE players SET hp = max_hp, mp = max_mp WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("疗伤失败", e);
        }

        result.put("success", true);
        result.put("hpBefore", hpBefore);
        result.put("hpAfter", player.getMaxHp());
        result.put("cost", cost);
        result.put("message", "消耗 " + cost + " 灵石，伤势痊愈！\n生命值: " + hpBefore + " → " + player.getMaxHp() + "（法力也一并恢复）");
        return result;
    }

    private long getSpiritStoneCount(long playerId) {
        String sql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("quantity");
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询灵石数量失败", e);
        }
        return 0;
    }

    private boolean removeSpiritStoneRaw(long playerId, long amount) {
        String sql = "UPDATE players_items SET quantity = quantity - ? WHERE player_id = ? AND item_key = ? AND quantity >= ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, playerId);
            ps.setString(3, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY);
            ps.setLong(4, amount);
            int affected = ps.executeUpdate();
            // 清理零值行
            if (affected > 0) {
                String cleanSql = "DELETE FROM players_items WHERE player_id = ? AND item_key = ? AND quantity <= 0";
                try (PreparedStatement cps = conn.prepareStatement(cleanSql)) {
                    cps.setLong(1, playerId);
                    cps.setString(2, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY);
                    cps.executeUpdate();
                }
            }
            return affected > 0;
        } catch (SQLException e) {
            throw new RuntimeException("扣除灵石失败", e);
        }
    }

    public void addMp(long playerId, int amount) {
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE players SET mp = MIN(mp + ?, max_mp) WHERE id = ?"
                : "UPDATE players SET mp = LEAST(mp + ?, max_mp) WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("恢复法力失败", e);
        }
    }

    public void addGold(long playerId, long amount) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addGold(conn, playerId, amount);
        } catch (SQLException e) {
            throw new RuntimeException("增加金币失败", e);
        }
    }

    public void addGold(Connection conn, long playerId, long amount) throws SQLException {
        String sql = "UPDATE players SET gold = gold + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    public void addAttack(long playerId, int amount) {
        String sql = "UPDATE players SET attack = attack + ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("增加攻击力失败", e);
        }
    }

    public void addAttack(Connection conn, long playerId, int amount) throws SQLException {
        String sql = "UPDATE players SET attack = attack + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    public void addDefense(long playerId, int amount) {
        String sql = "UPDATE players SET defense = defense + ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("增加防御力失败", e);
        }
    }

    public void addDefense(Connection conn, long playerId, int amount) throws SQLException {
        String sql = "UPDATE players SET defense = defense + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    public void addSpeed(long playerId, int amount) {
        String sql = "UPDATE players SET speed = speed + ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("增加速度失败", e);
        }
    }

    public void addSpeed(Connection conn, long playerId, int amount) throws SQLException {
        String sql = "UPDATE players SET speed = speed + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    public void addSpirit(long playerId, int amount) {
        String sql = "UPDATE players SET spirit = spirit + ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("增加灵力失败", e);
        }
    }

    public void addSpirit(Connection conn, long playerId, int amount) throws SQLException {
        String sql = "UPDATE players SET spirit = spirit + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    public void updateSpiritualRoot(long playerId, SpiritualRoot newRoot) {
        Player player = getPlayerById(playerId);
        if (player == null) {
            throw new RuntimeException("玩家不存在: " + playerId);
        }

        int baseHp = 100, baseMp = 50, baseAtk = 10, baseDef = 5, baseSpd = 5, baseSpi = 5;
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE players SET spiritual_root = ?, max_hp = ?, hp = MIN(hp, ?), max_mp = ?, mp = MIN(mp, ?), attack = ?, defense = ?, speed = ?, spirit = ? WHERE id = ?"
                : "UPDATE players SET spiritual_root = ?, max_hp = ?, hp = LEAST(hp, ?), max_mp = ?, mp = LEAST(mp, ?), attack = ?, defense = ?, speed = ?, spirit = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newRoot.name());
            ps.setInt(2, newRoot.applyHpBonus(baseHp));
            ps.setInt(3, newRoot.applyHpBonus(baseHp));
            ps.setInt(4, newRoot.applyMpBonus(baseMp));
            ps.setInt(5, newRoot.applyMpBonus(baseMp));
            ps.setInt(6, newRoot.applyAttackBonus(baseAtk));
            ps.setInt(7, newRoot.applyDefenseBonus(baseDef));
            ps.setInt(8, newRoot.applySpeedBonus(baseSpd));
            ps.setInt(9, newRoot.applySpiritBonus(baseSpi));
            ps.setLong(10, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新灵根失败", e);
        }
    }

    public void updateLastSecretRealmTime(long playerId, long lastSecretRealmTime) {
        String sql = "UPDATE players SET last_secret_realm_time = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lastSecretRealmTime);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新秘境时间失败", e);
        }
    }

    public void updateLastExplorationTime(long playerId, long lastExplorationTime) {
        String sql = "UPDATE players SET last_exploration_time = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lastExplorationTime);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新游历时间失败", e);
        }
    }

    public void updateBattleStrategy(long playerId, String strategy) {
        String sql = "UPDATE players SET battle_strategy = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, strategy);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新战斗策略失败", e);
        }
    }

    public void updateLastOfflineTime(long playerId, long lastOfflineTime) {
        String sql = "UPDATE players SET last_offline_time = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lastOfflineTime);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新离线时间失败", e);
        }
    }

    public List<PlayerInfo> getAllPlayers(int limit, int offset) {
        String sql = "SELECT * FROM players ORDER BY level DESC, experience DESC LIMIT ? OFFSET ?";
        List<PlayerInfo> players = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家列表失败", e);
        }
        return players;
    }

    public List<PlayerInfo> searchPlayersByName(String name, int limit, int offset) {
        String sql = "SELECT * FROM players WHERE name LIKE ? ORDER BY level DESC, experience DESC LIMIT ? OFFSET ?";
        List<PlayerInfo> players = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + name + "%");
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("搜索玩家失败", e);
        }
        return players;
    }

    public int getPlayerCount() {
        String sql = "SELECT COUNT(*) FROM players";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家数量失败", e);
        }
        return 0;
    }

    public int getUserCount() {
        String sql = "SELECT COUNT(*) FROM users";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取用户数量失败", e);
        }
        return 0;
    }

    public List<PlayerInfo> getTopByRealm(int limit) {
        String sql = "SELECT * FROM players ORDER BY realm DESC, experience DESC LIMIT ?";
        return queryPlayerInfoList(sql, limit);
    }

    public List<PlayerInfo> getTopByPower(int limit) {
        String sql = "SELECT * FROM players ORDER BY (attack + defense + speed + max_hp) DESC LIMIT ?";
        return queryPlayerInfoList(sql, limit);
    }

    public List<PlayerInfo> getTopByWealth(int limit) {
        String sql = """
            SELECT p.*, COALESCE(SUM(CASE
                WHEN pi.item_key = 'mtxgdn:spirit_stone_low' THEN pi.quantity
                WHEN pi.item_key = 'mtxgdn:spirit_stone_mid' THEN pi.quantity * 1000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_high' THEN pi.quantity * 1000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_supreme' THEN pi.quantity * 1000000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone' THEN pi.quantity
                ELSE 0 END), 0) AS spirit_stones
            FROM players p
            LEFT JOIN players_items pi ON p.id = pi.player_id
            GROUP BY p.id
            ORDER BY (p.gold + COALESCE(SUM(CASE
                WHEN pi.item_key = 'mtxgdn:spirit_stone_low' THEN pi.quantity
                WHEN pi.item_key = 'mtxgdn:spirit_stone_mid' THEN pi.quantity * 1000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_high' THEN pi.quantity * 1000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_supreme' THEN pi.quantity * 1000000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone' THEN pi.quantity
                ELSE 0 END), 0)) DESC
            LIMIT ?
            """;
        List<PlayerInfo> players = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取财富榜失败", e);
        }
        return players;
    }

    public PlayerInfo getPlayerInfoById(long playerId) {
        String sql = "SELECT * FROM players WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPlayerInfo(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
        return null;
    }

    private List<PlayerInfo> queryPlayerInfoList(String sql, int limit) {
        List<PlayerInfo> players = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询玩家列表失败", e);
        }
        return players;
    }

    private PlayerInfo mapPlayerInfo(ResultSet rs) throws SQLException {
        PlayerInfo pi = new PlayerInfo();
        pi.setId(rs.getLong("id"));
        pi.setUserId(rs.getLong("user_id"));
        pi.setName(rs.getString("name"));
        pi.setSpiritualRoot(safeParseSpiritualRoot(rs.getString("spiritual_root")));
        pi.setLevel(rs.getInt("level"));
        pi.setExperience(rs.getLong("experience"));
        pi.setRealm(rs.getInt("realm"));
        pi.setSubRealm(rs.getInt("sub_realm"));
        pi.setHp(rs.getInt("hp"));
        pi.setMaxHp(rs.getInt("max_hp"));
        pi.setMp(rs.getInt("mp"));
        pi.setMaxMp(rs.getInt("max_mp"));
        pi.setAttack(rs.getInt("attack"));
        pi.setDefense(rs.getInt("defense"));
        pi.setSpeed(rs.getInt("speed"));
        pi.setSpirit(rs.getInt("spirit"));
        pi.setGold(rs.getLong("gold"));
        pi.setCultivationProgress(rs.getInt("cultivation_progress"));
        pi.setCultivating(rs.getBoolean("is_cultivating"));
        pi.setCultivationStartTime(rs.getLong("cultivation_start_time"));
        pi.setLastSecretRealmTime(rs.getLong("last_secret_realm_time"));
        pi.setLastExplorationTime(rs.getLong("last_exploration_time"));
        pi.setTutorialStep(rs.getInt("tutorial_step"));
        pi.setTutorialTips(rs.getInt("tutorial_tips"));
        pi.setLastOfflineTime(rs.getLong("last_offline_time"));
        pi.setCreatedAt(rs.getString("created_at"));
        pi.setUpdatedAt(rs.getString("updated_at"));

        RealmConfig realmConfig = GameConfigLoader.getRealmConfig(pi.getRealm(), pi.getSubRealm());
        if (realmConfig != null) {
            pi.setRealmName(realmConfig.getFullName());
        }

        return pi;
    }

    private Player mapPlayer(ResultSet rs) throws SQLException {
        Player p = new Player();
        p.setId(rs.getLong("id"));
        p.setUserId(rs.getLong("user_id"));
        p.setName(rs.getString("name"));
        p.setSpiritualRoot(safeParseSpiritualRoot(rs.getString("spiritual_root")));
        p.setLevel(rs.getInt("level"));
        p.setExperience(rs.getLong("experience"));
        p.setRealm(rs.getInt("realm"));
        p.setSubRealm(rs.getInt("sub_realm"));
        p.setHp(rs.getInt("hp"));
        p.setMaxHp(rs.getInt("max_hp"));
        p.setMp(rs.getInt("mp"));
        p.setMaxMp(rs.getInt("max_mp"));
        p.setAttack(rs.getInt("attack"));
        p.setDefense(rs.getInt("defense"));
        p.setSpeed(rs.getInt("speed"));
        p.setSpirit(rs.getInt("spirit"));
        p.setGold(rs.getLong("gold"));
        p.setCultivationProgress(rs.getInt("cultivation_progress"));
        p.setCultivating(rs.getBoolean("is_cultivating"));
        p.setCultivationStartTime(rs.getLong("cultivation_start_time"));
        p.setLastSecretRealmTime(rs.getLong("last_secret_realm_time"));
        p.setLastExplorationTime(rs.getLong("last_exploration_time"));
        p.setTutorialStep(rs.getInt("tutorial_step"));
        p.setTutorialTips(rs.getInt("tutorial_tips"));
        p.setLastOfflineTime(rs.getLong("last_offline_time"));
        try { p.setBattleStrategy(rs.getString("battle_strategy")); } catch (Exception ignored) {}
        try { p.setCurrentLocationId(rs.getLong("current_location_id")); } catch (Exception ignored) {}
        try { p.setLastTravelTime(rs.getLong("last_travel_time")); } catch (Exception ignored) {}
        try { p.setLastBreakthroughTime(rs.getLong("last_breakthrough_time")); } catch (Exception ignored) {}
        p.setCreatedAt(rs.getString("created_at"));
        p.setUpdatedAt(rs.getString("updated_at"));
        return p;
    }

    private Title getEquippedTitle(long playerId) {
        String sql = "SELECT title_key FROM player_titles WHERE player_id = ? AND is_equipped = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setBoolean(2, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return TitleRegistry.get(rs.getString("title_key"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public int getFinalAttack(Player player) {
        int base = player.getAttack();
        Title title = getEquippedTitle(player.getId());
        if (title != null) {
            base += title.getAttackBonus();
        }
        return base;
    }

    public int getFinalDefense(Player player) {
        int base = player.getDefense();
        Title title = getEquippedTitle(player.getId());
        if (title != null) {
            base += title.getDefenseBonus();
        }
        return base;
    }

    public int getFinalMaxHp(Player player) {
        int base = player.getMaxHp();
        Title title = getEquippedTitle(player.getId());
        if (title != null) {
            base += title.getHpBonus();
        }
        return base;
    }

    public int getFinalMaxMp(Player player) {
        int base = player.getMaxMp();
        Title title = getEquippedTitle(player.getId());
        if (title != null) {
            base += title.getMpBonus();
        }
        return base;
    }

    public int getFinalSpeed(Player player) {
        int base = player.getSpeed();
        Title title = getEquippedTitle(player.getId());
        if (title != null) {
            base += title.getSpeedBonus();
        }
        return base;
    }

    public int getFinalSpirit(Player player) {
        int base = player.getSpirit();
        Title title = getEquippedTitle(player.getId());
        if (title != null) {
            base += title.getSpiritBonus();
        }
        return base;
    }

    public double getFinalCultivationSpeed(Player player) {
        double base = 1.0;
        Title title = getEquippedTitle(player.getId());
        if (title != null) {
            base += title.getCultivationSpeedBonus();
        }
        return base;
    }

    public double getFinalExpBonus(Player player) {
        double base = 1.0;
        Title title = getEquippedTitle(player.getId());
        if (title != null) {
            base += title.getExpBonus();
        }
        return base;
    }

    public double getFinalDropRateBonus(Player player) {
        double base = 0.0;
        Title title = getEquippedTitle(player.getId());
        if (title != null) {
            base += title.getDropRateBonus();
        }
        return base;
    }

    private SpiritualRoot safeParseSpiritualRoot(String s) {
        if (s == null || s.isBlank()) return SpiritualRoot.CHAOS_MIXED;
        s = s.trim().toUpperCase();
        return switch (s) {
            case "METAL" -> SpiritualRoot.TAIYI_GOLDEN;
            case "WOOD" -> SpiritualRoot.QINGDI_WOOD;
            case "WATER" -> SpiritualRoot.XUANMING_WATER;
            case "FIRE" -> SpiritualRoot.LIHUO_FIRE;
            case "EARTH" -> SpiritualRoot.HOUTU_EARTH;
            case "WIND" -> SpiritualRoot.XUNFENG_WIND;
            case "THUNDER" -> SpiritualRoot.JINGLEI_THUNDER;
            case "ICE" -> SpiritualRoot.XUANBING_ICE;
            default -> {
                try { yield SpiritualRoot.valueOf(s); }
                catch (Exception e) { yield SpiritualRoot.CHAOS_MIXED; }
            }
        };
    }

    public boolean deletePlayer(long playerId) {
        Player player = getPlayerById(playerId);
        if (player == null) {
            return false;
        }

        String[] tables = {
            "players_items", "players_equipment", "players_skills", "players_techniques",
            "player_daily", "player_economy", "player_bank", "player_titles", "player_energy",
            "sect_members", "sect_applications", "auction_listings", "auction_bids",
            "trade_listings", "redeemed_codes", "chat_messages", "player_action_logs"
        };

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (String table : tables) {
                    String sql = "DELETE FROM " + table + " WHERE player_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setLong(1, playerId);
                        ps.executeUpdate();
                    }
                }

                String deleteFriendSql = "DELETE FROM friends WHERE player_id = ? OR friend_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deleteFriendSql)) {
                    ps.setLong(1, playerId);
                    ps.setLong(2, playerId);
                    ps.executeUpdate();
                }

                String deletePlayerSql = "DELETE FROM players WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deletePlayerSql)) {
                    ps.setLong(1, playerId);
                    int affected = ps.executeUpdate();
                    if (affected == 0) {
                        conn.rollback();
                        return false;
                    }
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw new RuntimeException("删除玩家失败", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("删除玩家失败", e);
        }
    }

    private static void tickAutoHeal() {
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = "UPDATE players SET hp = MIN(hp + CAST(CEILING(max_hp * 0.01) AS INTEGER), max_hp) WHERE hp < max_hp";
        } else {
            sql = "UPDATE players SET hp = LEAST(hp + CEIL(max_hp * 0.01), max_hp) WHERE hp < max_hp";
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int affected = ps.executeUpdate();
            if (affected > 0) {
                LOG.debug("自动回血：" + affected + " 名玩家恢复了生命值");
            }
        } catch (SQLException e) {
            LOG.error("自动回血失败", e);
        }
    }
}

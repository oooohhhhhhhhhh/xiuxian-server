package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.SpiritualRoot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class PlayerService {

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
                gold, cultivation_progress, is_cultivating, cultivation_start_time, last_secret_realm_time, last_exploration_time, tutorial_step, tutorial_tips)
            VALUES (?, ?, ?, 1, 0, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 1, 0)
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
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
                last_secret_realm_time = ?, last_exploration_time = ?, last_offline_time = ?
            WHERE id = ?
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, player.getLevel());
            ps.setLong(2, player.getExperience());
            ps.setInt(3, player.getRealm());
            ps.setInt(4, 0);
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
            ps.setLong(20, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新玩家失败", e);
        }
    }

    public void addExperience(long playerId, long exp) {
        String sql = "UPDATE players SET experience = experience + ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, exp);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("增加经验失败", e);
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
        String sql = "UPDATE players SET hp = LEAST(hp + ?, max_hp) WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("恢复生命值失败", e);
        }
    }

    public void addMp(long playerId, int amount) {
        String sql = "UPDATE players SET mp = LEAST(mp + ?, max_mp) WHERE id = ?";
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
        String sql = "UPDATE players SET gold = gold + ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("增加金币失败", e);
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
        p.setCreatedAt(rs.getString("created_at"));
        p.setUpdatedAt(rs.getString("updated_at"));
        return p;
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
}

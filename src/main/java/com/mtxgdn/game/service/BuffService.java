package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BuffService {

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        scheduler.scheduleAtFixedRate(BuffService::checkExpiredBuffs, 1, 1, TimeUnit.SECONDS);
    }

    public static void shutdownScheduler() {
        scheduler.shutdown();
    }

    public static class ActiveBuff {
        private String buffId;
        private int attackBonus;
        private int defenseBonus;
        private int speedBonus;
        private int spiritBonus;
        private long expireTime;

        public ActiveBuff(String buffId, int attackBonus, int defenseBonus, int speedBonus, int spiritBonus, long expireTime) {
            this.buffId = buffId;
            this.attackBonus = attackBonus;
            this.defenseBonus = defenseBonus;
            this.speedBonus = speedBonus;
            this.spiritBonus = spiritBonus;
            this.expireTime = expireTime;
        }

        public String getBuffId() { return buffId; }
        public int getAttackBonus() { return attackBonus; }
        public int getDefenseBonus() { return defenseBonus; }
        public int getSpeedBonus() { return speedBonus; }
        public int getSpiritBonus() { return spiritBonus; }
        public long getExpireTime() { return expireTime; }
        public boolean isExpired() { return System.currentTimeMillis() >= expireTime; }
    }

    public void addBuff(long playerId, String buffId, int attackBonus, int defenseBonus, int speedBonus, int spiritBonus, int durationSeconds) {
        long expireTime = System.currentTimeMillis() + (long) durationSeconds * 1000;

        DatabaseManager.runTransaction(conn -> {
            String sql = """
                INSERT INTO player_buffs (player_id, buff_id, attack_bonus, defense_bonus, speed_bonus, spirit_bonus, expire_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, playerId);
                ps.setString(2, buffId);
                ps.setInt(3, attackBonus);
                ps.setInt(4, defenseBonus);
                ps.setInt(5, speedBonus);
                ps.setInt(6, spiritBonus);
                ps.setLong(7, expireTime);
                ps.executeUpdate();
            }

            PlayerService playerService = new PlayerService();
            if (attackBonus != 0) playerService.addAttack(playerId, attackBonus);
            if (defenseBonus != 0) playerService.addDefense(playerId, defenseBonus);
            if (speedBonus != 0) playerService.addSpeed(playerId, speedBonus);
            if (spiritBonus != 0) playerService.addSpirit(playerId, spiritBonus);

            return true;
        });
    }

    public void removeBuff(long playerId, String buffId) {
        DatabaseManager.runTransaction(conn -> {
            String sql = "SELECT attack_bonus, defense_bonus, speed_bonus, spirit_bonus FROM player_buffs WHERE player_id = ? AND buff_id = ?";
            int atk = 0, def = 0, spd = 0, spi = 0;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, playerId);
                ps.setString(2, buffId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        atk = rs.getInt("attack_bonus");
                        def = rs.getInt("defense_bonus");
                        spd = rs.getInt("speed_bonus");
                        spi = rs.getInt("spirit_bonus");
                    }
                }
            }

            String delSql = "DELETE FROM player_buffs WHERE player_id = ? AND buff_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(delSql)) {
                ps.setLong(1, playerId);
                ps.setString(2, buffId);
                ps.executeUpdate();
            }

            PlayerService playerService = new PlayerService();
            if (atk != 0) playerService.addAttack(playerId, -atk);
            if (def != 0) playerService.addDefense(playerId, -def);
            if (spd != 0) playerService.addSpeed(playerId, -spd);
            if (spi != 0) playerService.addSpirit(playerId, -spi);

            return true;
        });
    }

    public Map<String, Object> getActiveBuffs(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> buffList = new ArrayList<>();

        String sql = "SELECT buff_id, attack_bonus, defense_bonus, speed_bonus, spirit_bonus, expire_time FROM player_buffs WHERE player_id = ? AND expire_time > ?";
        long now = System.currentTimeMillis();

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> buffInfo = new LinkedHashMap<>();
                    buffInfo.put("id", rs.getString("buff_id"));
                    buffInfo.put("attackBonus", rs.getInt("attack_bonus"));
                    buffInfo.put("defenseBonus", rs.getInt("defense_bonus"));
                    buffInfo.put("speedBonus", rs.getInt("speed_bonus"));
                    buffInfo.put("spiritBonus", rs.getInt("spirit_bonus"));
                    buffInfo.put("remainingSeconds", Math.max(0, (rs.getLong("expire_time") - now) / 1000));
                    buffList.add(buffInfo);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询buff失败", e);
        }

        result.put("buffs", buffList);
        result.put("count", buffList.size());
        return result;
    }

    private static void checkExpiredBuffs() {
        String sql = "SELECT player_id, buff_id, attack_bonus, defense_bonus, speed_bonus, spirit_bonus FROM player_buffs WHERE expire_time <= ?";
        long now = System.currentTimeMillis();

        List<Map<String, Object>> expiredList = new ArrayList<>();

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> buff = new LinkedHashMap<>();
                    buff.put("playerId", rs.getLong("player_id"));
                    buff.put("buffId", rs.getString("buff_id"));
                    buff.put("atk", rs.getInt("attack_bonus"));
                    buff.put("def", rs.getInt("defense_bonus"));
                    buff.put("spd", rs.getInt("speed_bonus"));
                    buff.put("spi", rs.getInt("spirit_bonus"));
                    expiredList.add(buff);
                }
            }
        } catch (SQLException e) {
            return;
        }

        PlayerService playerService = new PlayerService();
        for (Map<String, Object> buff : expiredList) {
            long playerId = (long) buff.get("playerId");
            int atk = (int) buff.get("atk");
            int def = (int) buff.get("def");
            int spd = (int) buff.get("spd");
            int spi = (int) buff.get("spi");

            if (atk != 0) playerService.addAttack(playerId, -atk);
            if (def != 0) playerService.addDefense(playerId, -def);
            if (spd != 0) playerService.addSpeed(playerId, -spd);
            if (spi != 0) playerService.addSpirit(playerId, -spi);
        }

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM player_buffs WHERE expire_time <= ?")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public void clearAllBuffs(long playerId) {
        DatabaseManager.runTransaction(conn -> {
            String sql = "SELECT attack_bonus, defense_bonus, speed_bonus, spirit_bonus FROM player_buffs WHERE player_id = ?";
            int totalAtk = 0, totalDef = 0, totalSpd = 0, totalSpi = 0;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        totalAtk += rs.getInt("attack_bonus");
                        totalDef += rs.getInt("defense_bonus");
                        totalSpd += rs.getInt("speed_bonus");
                        totalSpi += rs.getInt("spirit_bonus");
                    }
                }
            }

            String delSql = "DELETE FROM player_buffs WHERE player_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(delSql)) {
                ps.setLong(1, playerId);
                ps.executeUpdate();
            }

            PlayerService playerService = new PlayerService();
            if (totalAtk != 0) playerService.addAttack(playerId, -totalAtk);
            if (totalDef != 0) playerService.addDefense(playerId, -totalDef);
            if (totalSpd != 0) playerService.addSpeed(playerId, -totalSpd);
            if (totalSpi != 0) playerService.addSpirit(playerId, -totalSpi);

            return true;
        });
    }

    public int getTotalAttackBonus(long playerId) {
        return getTotalBonus(playerId, "attack_bonus");
    }

    public int getTotalDefenseBonus(long playerId) {
        return getTotalBonus(playerId, "defense_bonus");
    }

    public int getTotalSpeedBonus(long playerId) {
        return getTotalBonus(playerId, "speed_bonus");
    }

    public int getTotalSpiritBonus(long playerId) {
        return getTotalBonus(playerId, "spirit_bonus");
    }

    private int getTotalBonus(long playerId, String column) {
        String sql = "SELECT COALESCE(SUM(" + column + "), 0) as total FROM player_buffs WHERE player_id = ? AND expire_time > ?";
        long now = System.currentTimeMillis();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("total");
                }
            }
        } catch (SQLException e) {
            return 0;
        }
        return 0;
    }

    public void reloadBuffsOnStartup(long playerId) {
        String sql = "SELECT attack_bonus, defense_bonus, speed_bonus, spirit_bonus FROM player_buffs WHERE player_id = ? AND expire_time > ?";
        long now = System.currentTimeMillis();
        int totalAtk = 0, totalDef = 0, totalSpd = 0, totalSpi = 0;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    totalAtk += rs.getInt("attack_bonus");
                    totalDef += rs.getInt("defense_bonus");
                    totalSpd += rs.getInt("speed_bonus");
                    totalSpi += rs.getInt("spirit_bonus");
                }
            }
        } catch (SQLException e) {
            return;
        }

        PlayerService playerService = new PlayerService();
        if (totalAtk != 0) playerService.addAttack(playerId, totalAtk);
        if (totalDef != 0) playerService.addDefense(playerId, totalDef);
        if (totalSpd != 0) playerService.addSpeed(playerId, totalSpd);
        if (totalSpi != 0) playerService.addSpirit(playerId, totalSpi);
    }
}
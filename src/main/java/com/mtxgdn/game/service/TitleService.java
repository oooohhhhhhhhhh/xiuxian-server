package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.game.title.TitleRegistry;

import java.sql.*;
import java.util.*;
import com.mtxgdn.i18n.I18n;

public class TitleService {

    private final PlayerService playerService = new PlayerService();

    public TitleService() {
        TitleRegistry.init();
    }

    /** 授予称号 */
    public Map<String, Object> grantTitle(long playerId, String titleKey) {
        Title title = TitleRegistry.get(titleKey);
        if (title == null) {
            return createResult(false, I18n.t("title.h59382207", titleKey));
        }

        if (hasTitle(playerId, titleKey)) {
            return createResult(false, I18n.t("title.h56c44c8c"));
        }

        String sql = "INSERT INTO player_titles (player_id, title_key, is_equipped) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, titleKey);
            ps.setBoolean(3, false);
            ps.executeUpdate();
            Map<String, Object> result = createResult(true, I18n.t("title.hc147083f", title.getName()));
            result.put("titleKey", titleKey);
            result.put("titleName", title.getName());
            return result;
        } catch (SQLException e) {
            return createResult(false, I18n.t("title.h1b0b95d4", e.getMessage()));
        }
    }

    /**
     * 集中检查并自动授予"玩法可获得"的称号（幂等：已拥有则跳过）。
     * 在突破/签到/战斗/炼丹/秘境等关键行为后调用，返回本次新授予的称号名列表。
     */
    public List<String> checkAndGrantTitles(com.mtxgdn.entity.Player player) {
        List<String> granted = new ArrayList<>();
        if (player == null) {
            return granted;
        }
        long playerId = player.getId();
        com.mtxgdn.game.service.ItemService itemService = new com.mtxgdn.game.service.ItemService();

        // 境界类
        grantIf(granted, playerId, "immortal_venerable", player.getRealm() >= 5);   // 不朽尊者：化神
        grantIf(granted, playerId, "heaven_defier", player.getRealm() >= 8);        // 逆天者：渡劫
        grantIf(granted, playerId, "dao_lord", player.getRealm() >= 9);             // 大道之主：真仙
        grantIf(granted, playerId, "chaos_emperor", player.getRealm() >= 10);       // 混沌帝尊：金仙
        // 战力类
        grantIf(granted, playerId, "sword_master", playerService.getFinalAttack(player) >= 500); // 剑道宗师
        // 财富类
        grantIf(granted, playerId, "millionaire", itemService.getSpiritStoneCount(playerId) >= 1_000_000L); // 富甲一方
        // 行为计数类
        grantIf(granted, playerId, "exploration_master", player.getSecretRealmCount() >= 20); // 秘境探险家
        grantIf(granted, playerId, "arena_champion", player.getPvpWins() >= 50);              // 擂台王者
        grantIf(granted, playerId, "alchemy_lord", player.getCraftCount() >= 100);            // 丹道尊者
        grantIf(granted, playerId, "pill_apprentice", player.getCraftCount() >= 1);           // 炼丹学徒
        return granted;
    }

    private void grantIf(List<String> granted, long playerId, String titleKey, boolean condition) {
        if (!condition) {
            return;
        }
        try {
            Map<String, Object> r = grantTitle(playerId, titleKey);
            if (Boolean.TRUE.equals(r.get("success"))) {
                granted.add(titleKey);
            }
        } catch (Exception ignored) {
            // 授予失败不阻断主流程
        }
    }

    /** 撤销称号 */
    public Map<String, Object> revokeTitle(long playerId, String titleKey) {        String sql = "DELETE FROM player_titles WHERE player_id = ? AND title_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, titleKey);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                Title title = TitleRegistry.get(titleKey);
                // 查不到称号时退回原 key 而不是编一个名字——
                // 这里的 key 是装备/称号的内部标识，玩家认得出
                return createResult(true, I18n.t("title.revoked",
                        title != null ? title.getName() : titleKey));
            } else {
                return createResult(false, I18n.t("title.h4f740e50"));
            }
        } catch (SQLException e) {
            return createResult(false, I18n.t("title.h9856ec28", e.getMessage()));
        }
    }

    /** 装备称号 */
    public Map<String, Object> equipTitle(long playerId, String titleKey) {
        Title title = TitleRegistry.get(titleKey);
        if (title == null) {
            return createResult(false, I18n.t("title.h59382207", titleKey));
        }

        if (!hasTitle(playerId, titleKey)) {
            return createResult(false, I18n.t("title.h3a7cd8ac"));
        }

        try {
            DatabaseManager.runTransaction(conn -> {
                unequipCurrentTitle(conn, playerId);
                equipNewTitle(conn, playerId, titleKey);
                return null;
            });

            Map<String, Object> result = createResult(true, I18n.t("title.h564f1978", title.getName()));
            result.put("titleKey", titleKey);
            result.put("titleName", title.getName());
            return result;
        } catch (Exception e) {
            return createResult(false, I18n.t("title.h0b6bcf28", e.getMessage()));
        }
    }

    /** 卸下称号 */
    public Map<String, Object> unequipTitle(long playerId) {
        String sql = "UPDATE player_titles SET is_equipped = ? WHERE player_id = ? AND is_equipped = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, false);
            ps.setLong(2, playerId);
            ps.setBoolean(3, true);
            int rows = ps.executeUpdate();
            Map<String, Object> result = createResult(true,
                    rows > 0 ? I18n.t("title.unequipped") : I18n.t("title.none_equipped"));
            return result;
        } catch (SQLException e) {
            return createResult(false, I18n.t("title.h3e363884", e.getMessage()));
        }
    }

    /** 获取玩家所有称号 */
    public List<Map<String, Object>> getPlayerTitles(long playerId) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT title_key, is_equipped, acquired_at FROM player_titles WHERE player_id = ? ORDER BY acquired_at DESC";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("title_key");
                    Title title = TitleRegistry.get(key);
                    if (title == null) continue;
                    list.add(buildTitleMap(key, title, rs.getBoolean("is_equipped"), rs.getString("acquired_at")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /** 获取当前装备的称号 */
    public Title getEquippedTitle(long playerId) {
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

    public boolean hasTitle(long playerId, String titleKey) {
        String sql = "SELECT 1 FROM player_titles WHERE player_id = ? AND title_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, titleKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private Map<String, Object> createResult(boolean success, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("message", message);
        return result;
    }

    private void unequipCurrentTitle(Connection conn, long playerId) throws SQLException {
        String sql = "UPDATE player_titles SET is_equipped = ? WHERE player_id = ? AND is_equipped = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, false);
            ps.setLong(2, playerId);
            ps.setBoolean(3, true);
            ps.executeUpdate();
        }
    }

    private void equipNewTitle(Connection conn, long playerId, String titleKey) throws SQLException {
        String sql = "UPDATE player_titles SET is_equipped = ? WHERE player_id = ? AND title_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, true);
            ps.setLong(2, playerId);
            ps.setString(3, titleKey);
            ps.executeUpdate();
        }
    }

    private Map<String, Object> buildTitleMap(String key, Title title, boolean isEquipped, String acquiredAt) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("titleKey", key);
        map.put("name", title.getName());
        map.put("description", title.getDescription());
        map.put("rarity", title.getRarity().name());
        map.put("rarityLabel", title.getRarityLabel());
        map.put("isEquipped", isEquipped);
        map.put("acquiredAt", acquiredAt);
        map.put("attackBonus", title.getAttackBonus());
        map.put("defenseBonus", title.getDefenseBonus());
        map.put("hpBonus", title.getHpBonus());
        map.put("mpBonus", title.getMpBonus());
        map.put("speedBonus", title.getSpeedBonus());
        map.put("spiritBonus", title.getSpiritBonus());
        map.put("cultivationSpeedBonus", title.getCultivationSpeedBonus());
        map.put("expBonus", title.getExpBonus());
        map.put("dropRateBonus", title.getDropRateBonus());
        return map;
    }
}
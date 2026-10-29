package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.db.TitleDAO;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.game.title.TitleRegistry;

import java.util.*;
import com.mtxgdn.common.result.OperationResult;
import com.mtxgdn.game.title.TitleChangeResult;
import com.mtxgdn.i18n.I18n;

public class TitleService {

    private static final com.mtxgdn.util.GameLogger LOG =
            com.mtxgdn.util.GameLogger.getLogger(TitleService.class);

    private final PlayerService playerService = ServiceRegistry.getPlayerService();

    /** {@code player_titles} 的读写入口（无状态，可共享）。 */
    private static final TitleDAO TITLE_DAO = new TitleDAO();

    public TitleService() {
        TitleRegistry.init();
    }

    /** 授予称号 */
    public TitleChangeResult grantTitle(long playerId, String titleKey) {
        Title title = TitleRegistry.get(titleKey);
        if (title == null) {
            return TitleChangeResult.fail(I18n.t("title.title_does_not_exist", titleKey));
        }

        if (hasTitle(playerId, titleKey)) {
            return TitleChangeResult.fail(I18n.t("title.you_already_own_that_title"));
        }

        try {
            TITLE_DAO.grantTitle(playerId, titleKey);
            return TitleChangeResult.ok(
                    I18n.t("title.successfully_granted_title", title.getName()),
                    titleKey, title.getName());
        } catch (RuntimeException e) {
            return TitleChangeResult.fail(
                    I18n.t("title.failed_to_grant_the_title", e.getMessage()));
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
            TitleChangeResult r = grantTitle(playerId, titleKey);
            if (r.success()) {
                granted.add(titleKey);
            }
        } catch (Exception ignored) {
            // 授予失败不阻断主流程
        }
    }

    /** 撤销称号 */
    public OperationResult revokeTitle(long playerId, String titleKey) {
        try {
            if (!TITLE_DAO.revokeTitle(playerId, titleKey)) {
                return OperationResult.fail(I18n.t("title.the_player_does_not_own"));
            }
            Title title = TitleRegistry.get(titleKey);
            // 查不到称号时退回原 key 而不是编一个名字——
            // 这里的 key 是装备/称号的内部标识，玩家认得出
            return OperationResult.ok(I18n.t("title.revoked",
                    title != null ? title.getName() : titleKey));
        } catch (RuntimeException e) {
            return OperationResult.fail(
                    I18n.t("title.failed_to_revoke_the_title", e.getMessage()));
        }
    }

    /** 装备称号 */
    public TitleChangeResult equipTitle(long playerId, String titleKey) {
        Title title = TitleRegistry.get(titleKey);
        if (title == null) {
            return TitleChangeResult.fail(I18n.t("title.title_does_not_exist", titleKey));
        }

        if (!hasTitle(playerId, titleKey)) {
            return TitleChangeResult.fail(I18n.t("title.you_do_not_own"));
        }

        try {
            com.mtxgdn.db.DatabaseManager.runTransaction(conn -> {
                TITLE_DAO.unequipAll(conn, playerId);
                TITLE_DAO.equipTitle(conn, playerId, titleKey);
                return null;
            });

            return TitleChangeResult.ok(
                    I18n.t("title.equipped_title", title.getName()),
                    titleKey, title.getName());
        } catch (Exception e) {
            return TitleChangeResult.fail(
                    I18n.t("title.failed_to_equip_the_title", e.getMessage()));
        }
    }

    /** 卸下称号 */
    public OperationResult unequipTitle(long playerId) {
        try {
            int rows = TITLE_DAO.unequipAll(playerId);
            return OperationResult.ok(
                    rows > 0 ? I18n.t("title.unequipped") : I18n.t("title.none_equipped"));
        } catch (RuntimeException e) {
            return OperationResult.fail(
                    I18n.t("title.failed_to_unequip_the_title", e.getMessage()));
        }
    }

    /** 获取玩家所有称号 */
    public List<Map<String, Object>> getPlayerTitles(long playerId) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            for (Object[] row : TITLE_DAO.findPlayerTitles(playerId)) {
                String key = (String) row[0];
                Title title = TitleRegistry.get(key);
                if (title == null) continue;
                list.add(buildTitleMap(key, title, (Boolean) row[1], (String) row[2]));
            }
        } catch (RuntimeException e) {
            LOG.error("查询玩家称号列表失败", e);
        }
        return list;
    }

    /** 获取当前装备的称号 */
    public Title getEquippedTitle(long playerId) {
        try {
            String key = TITLE_DAO.findEquippedTitleKey(playerId);
            if (key != null) {
                return TitleRegistry.get(key);
            }
        } catch (RuntimeException e) {
            LOG.error("查询当前装备称号失败", e);
        }
        return null;
    }

    public boolean hasTitle(long playerId, String titleKey) {
        return TITLE_DAO.hasTitle(playerId, titleKey);
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

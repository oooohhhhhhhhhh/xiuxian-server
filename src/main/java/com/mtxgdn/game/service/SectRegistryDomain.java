package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.SectRegistryDAO;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.i18n.I18n;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宗门建档域：创建、解散、升级、宗主转让。
 *
 * <p>这一域操作的对象是"宗门这个<b>实体本身</b>"（{@code sects} 表那一行），
 * 而不是它下面的成员或仓库条目 —— 成员进出、申请审批、仓库捐献都在
 * {@link SectMembershipDomain} 与 {@link SectWarehouseDomain} 里。判断标准很朴素：
 * 一次操作之后，这个宗门还是不是原来那个宗门？创建/解散会改变"存在性"，
 * 升级/转让会改变"由谁代表它"。</p>
 *
 * <p>方法体、SQL、异常文案、{@code Map} 里 key 的顺序都与拆分前逐字相同。</p>
 *
 * <p><b>事务边界</b>：创建宗门（扣灵石 + 插宗门 + 插宗主成员）与宗主转让
 * （扣灵石 + 换宗主 + 改两人职位）原本就各自是一个 {@code runTransaction}，
 * 拆分时这两个事务块整体搬了过来，块内只使用事务传入的 {@code conn}
 * （扣灵石走 {@code itemService} 的 {@code conn} 重载），不会在事务里再开连接。</p>
 */
final class SectRegistryDomain {

    private final PlayerService playerService;
    private final ItemService itemService;
    private final SectQueryDomain queryDomain;

    /** {@code sects} 建档/升级/转让/解散的读写入口（无状态，可共享）。 */
    private static final SectRegistryDAO REGISTRY_DAO = new SectRegistryDAO();

    SectRegistryDomain(PlayerService playerService, ItemService itemService, SectQueryDomain queryDomain) {
        this.playerService = playerService;
        this.itemService = itemService;
        this.queryDomain = queryDomain;
    }

    // ==================== 宗门创建 ====================

    Map<String, Object> createSect(long playerId, String sectName, String description) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (sectName == null || sectName.trim().isEmpty() || sectName.trim().length() < 2 || sectName.trim().length() > 8) {
            result.put("success", false);
            result.put("message", I18n.t("sect.sect_names_must_be_2"));
            return result;
        }
        final String name = sectName.trim();
        if (description != null && description.length() > 100) {
            result.put("success", false);
            result.put("message", I18n.t("sect.the_sect_description"));
            return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.character_does_not_exist"));
            return result;
        }

        if (player.getRealm() < Sect.MIN_LEVEL_CREATE) {
            String realmName = GameConfigLoader.getRealmConfig(player.getRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(player.getRealm(), 0).getFullName() : "凡人";
            result.put("success", false);
            result.put("message", I18n.t("sect.your_realm_is_too_low", realmName));
            return result;
        }

        if (queryDomain.getPlayerSect(playerId) != null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.you_already_belong_2"));
            return result;
        }

        if (queryDomain.getSectByName(name) != null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.the_sect_name_is_already", name));
            return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < Sect.CREATE_COST_SPIRIT_STONES) {
            result.put("success", false);
            result.put("message", I18n.t("sect.not_enough_spirit_stones_3", Sect.CREATE_COST_SPIRIT_STONES, spiritStones));
            return result;
        }

        Sect sect = DatabaseManager.runTransaction(conn -> {
            // 扣除灵石（在事务内，跨等级扣除）
            if (!itemService.removeSpiritStones(conn, playerId, Sect.CREATE_COST_SPIRIT_STONES)) {
                throw new SQLException("灵石扣除失败");
            }
            long sectId = REGISTRY_DAO.createSect(conn, name,
                    description != null ? description : "一个新兴的修仙宗门", playerId);
            if (sectId > 0) {
                REGISTRY_DAO.insertLeaderMember(conn, sectId, playerId);
                // 直接构造 Sect 返回，避免事务内跨连接查询读不到未提交数据
                Sect created = new Sect();
                created.setId(sectId);
                created.setName(name);
                created.setDescription(description != null ? description : "一个新兴的修仙宗门");
                created.setLeaderPlayerId(playerId);
                created.setLeaderName(player.getName());
                created.setLevel(1);
                created.setPrestige(1000L);
                created.setMemberCount(1);
                return created;
            }
            return null;
        });

        if (sect != null) {
            // 创建宗门授予「开山祖师」称号
            try {
                new com.mtxgdn.game.service.TitleService().grantTitle(playerId, "sect_founder");
            } catch (Exception ignored) {
            }
            result.put("success", true);
            result.put("message", I18n.t("sect.congratulations_to_on_founding", player.getName(), name, Sect.CREATE_COST_SPIRIT_STONES));
            result.put("sect", sect);
        } else {
            result.put("success", false);
            result.put("message", I18n.t("sect.failed_to_found_the_sect"));
        }
        return result;
    }

    // ==================== 宗门升级 ====================

    Map<String, Object> levelUp(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = queryDomain.getPlayerMember(playerId);
        if (member == null || !member.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.only_the_sect_leader_7")); return result;
        }

        Sect sect = queryDomain.getSectById(member.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.sect_does_not_exist")); return result;
        }
        if (sect.getLevel() >= Sect.MAX_LEVEL) {
            result.put("success", false);
            result.put("message", I18n.t("sect.the_sect_has_already_reached", Sect.MAX_LEVEL)); return result;
        }

        long cost = Sect.getLevelUpCost(sect.getLevel());
        if (sect.getPrestige() < cost) {
            result.put("success", false);
            result.put("message", I18n.t("sect.not_enough_prestige", cost, sect.getPrestige()));
            return result;
        }

        int newLevel = sect.getLevel() + 1;
        int newMaxMembers = Sect.getMaxMembersForLevel(newLevel);
        try {
            REGISTRY_DAO.levelUp(sect.getId(), newLevel, cost, newMaxMembers);
        } catch (RuntimeException e) {
            throw new RuntimeException("宗门升级失败", e);
        }

        result.put("success", true);
        result.put("message", I18n.t("sect.sect_advanced_to_level", sect.getName(), newLevel, cost, newMaxMembers));
        return result;
    }

    // ==================== 宗主转让 ====================

    Map<String, Object> transferLeader(long fromPlayerId, long toPlayerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember fromMember = queryDomain.getPlayerMember(fromPlayerId);
        if (fromMember == null || !fromMember.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.only_the_sect_leader_5")); return result;
        }

        Sect sect = queryDomain.getSectById(fromMember.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.sect_does_not_exist")); return result;
        }

        SectMember toMember = queryDomain.getMember(sect.getId(), toPlayerId);
        if (toMember == null) {
            result.put("success", false); result.put("message", I18n.t("sect.that_player_2")); return result;
        }
        if (toMember.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.that_player_is_already")); return result;
        }

        Player toPlayer = playerService.getPlayerById(toPlayerId);
        if (toPlayer == null) {
            result.put("success", false); result.put("message", I18n.t("sect.the_recipient_s_character_does")); return result;
        }
        if (toPlayer.getRealm() < Sect.MIN_LEVEL_CREATE) {
            String realmName = GameConfigLoader.getRealmConfig(toPlayer.getRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(toPlayer.getRealm(), 0).getFullName() : "凡人";
            result.put("success", false);
            result.put("message", I18n.t("sect.the_recipient_s_realm", realmName));
            return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(fromPlayerId);
        if (spiritStones < Sect.TRANSFER_COST_SPIRIT_STONES) {
            result.put("success", false);
            result.put("message", I18n.t("sect.not_enough_spirit_stones_2", Sect.TRANSFER_COST_SPIRIT_STONES, spiritStones));
            return result;
        }
        DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeSpiritStones(conn, fromPlayerId, Sect.TRANSFER_COST_SPIRIT_STONES)) {
                throw new Exception("扣除灵石失败");
            }
            REGISTRY_DAO.setLeader(conn, sect.getId(), toPlayerId);
            REGISTRY_DAO.demoteToViceLeader(conn, sect.getId(), fromPlayerId);
            REGISTRY_DAO.promoteToLeader(conn, sect.getId(), toPlayerId);
            return null;
        });

        result.put("success", true);
        result.put("message", I18n.t("sect.transfer_done",
                fromMember.getPlayerName(), toMember.getPlayerName(),
                Sect.TRANSFER_COST_SPIRIT_STONES));
        return result;
    }

    // ==================== 解散宗门 ====================

    Map<String, Object> disbandSect(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = queryDomain.getPlayerMember(playerId);
        if (member == null || !member.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.only_the_sect_leader_6")); return result;
        }

        Sect sect = queryDomain.getSectById(member.getSectId());
        if (sect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.sect_does_not_exist")); return result;
        }

        // 四条 DELETE 有顺序：子表清完再删 sects 父行，否则启用外键时会失败
        DatabaseManager.runTransaction(conn -> {
            REGISTRY_DAO.deleteWarehouse(conn, sect.getId());
            REGISTRY_DAO.deleteApplications(conn, sect.getId());
            REGISTRY_DAO.deleteMembers(conn, sect.getId());
            REGISTRY_DAO.deleteSect(conn, sect.getId());
            return null;
        });

        result.put("success", true);
        result.put("message", I18n.t("sect.sect_has_been_disbanded", sect.getName()));
        return result;
    }
}

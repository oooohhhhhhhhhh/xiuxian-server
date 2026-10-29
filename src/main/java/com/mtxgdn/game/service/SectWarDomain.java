package com.mtxgdn.game.service;

import com.mtxgdn.game.sect.WarResult;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.SectWarDAO;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.i18n.I18n;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 宗门战域：宣战、逐场对拼、结算声望、落战报。
 *
 * <p>整场宗门战是<b>一次同步执行</b>的：宣战 → 扣灵石与声望 → 选出战成员 →
 * 逐场对拼 → 发放声望 → 保存战报。中间没有异步任务，因此这里的方法体是从
 * {@code SectService#declareWar} 整体搬过来的，一行未拆 —— 拆开反而会让
 * "扣了钱却没打起来"这类中间态变得可能。</p>
 *
 * <p>对拼规则本身在 {@link SectDuelDomain}（与宗主争夺战共用）；本类只负责
 * 组织流程与落库。方法体、SQL、战报文案都与拆分前逐字相同。</p>
 *
 * <p><b>事务边界</b>：宣战的"扣灵石 + 扣声望"是一个 {@code runTransaction}，
 * 拆分时整块搬了过来，块内只使用事务传入的 {@code conn}（扣灵石走
 * {@code itemService} 的 {@code conn} 重载）。声望结算与战报写入在拆分前
 * 就各自是一个独立短连接（<b>不在</b>事务内），这里同样保持独立 ——
 * 把它们并进事务属于行为改动，不属于本次纯结构重构。</p>
 */
final class SectWarDomain {

    /** 实验性插件名：宗门战（开关见 config/plugins.yml 的 plugins.experimental_sect_war，默认关闭）。 */
    private static final String SECT_WAR_PLUGIN = "experimental_sect_war";

    private final PlayerService playerService;
    private final ItemService itemService;
    private final SectQueryDomain queryDomain;
    private final SectDuelDomain duelDomain;

    /** {@code sect_wars} 的读写入口（无状态，可共享）。 */
    private static final SectWarDAO WAR_DAO = new SectWarDAO();

    SectWarDomain(PlayerService playerService, ItemService itemService,
                  SectQueryDomain queryDomain, SectDuelDomain duelDomain) {
        this.playerService = playerService;
        this.itemService = itemService;
        this.queryDomain = queryDomain;
        this.duelDomain = duelDomain;
    }

    WarResult declareWar(long attackerPlayerId, long defenderSectId) {
        // 实验性插件门控：宗门战停用后，REST / WebSocket / 指令三个入口统一拒绝
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown(SECT_WAR_PLUGIN)) {
            return WarResult.fail(I18n.t("sect.sect_war_is_experimental_content"));
        }
        SectMember attackerMember = queryDomain.getPlayerMember(attackerPlayerId);
        if (attackerMember == null || !attackerMember.isLeader()) {
            return WarResult.fail(I18n.t("sect.only_the_sect_leader_2"));
        }

        Sect attackerSect = queryDomain.getSectById(attackerMember.getSectId());
        if (attackerSect == null) {
            return WarResult.fail(I18n.t("sect.your_sect_does_not_exist"));
        }
        if (attackerSect.getId() == defenderSectId) {
            return WarResult.fail(I18n.t("sect.you_cannot_declare_war"));
        }

        Sect defenderSect = queryDomain.getSectById(defenderSectId);
        if (defenderSect == null) {
            return WarResult.fail(I18n.t("sect.the_target_sect_does"));
        }

        List<SectMember> defenderMembers = queryDomain.getSectMembers(defenderSectId);
        if (defenderMembers.isEmpty()) {
            return WarResult.fail(I18n.t("sect.the_target_sect"));
        }

        // 宗门战冷却：同一防守方 24 小时内只能被宣战一次，防止无休止骚扰
        long warCooldownMs = 24 * 3600 * 1000L;
        java.sql.Timestamp lastWarTime;
        try {
            lastWarTime = WAR_DAO.lastWarTime(defenderSectId);
        } catch (RuntimeException e) {
            throw new RuntimeException("查询宗门战记录失败", e);
        }
        if (lastWarTime != null) {
            long remaining = warCooldownMs - (System.currentTimeMillis() - lastWarTime.getTime());
            if (remaining > 0) {
                return WarResult.fail(I18n.t("sect.the_target_sect_just_fought", Math.max(1, remaining / 3600000)));
            }
        }
        if (attackerSect.getPrestige() < Sect.DECLARE_WAR_PRESTIGE_COST) {
            return WarResult.fail(I18n.t("sect.the_sect_lacks_prestige", Sect.DECLARE_WAR_PRESTIGE_COST, attackerSect.getPrestige()));
        }

        long spiritStones = itemService.getSpiritStoneCount(attackerPlayerId);
        if (spiritStones < Sect.DECLARE_WAR_SPIRIT_STONE_COST) {
            return WarResult.fail(I18n.t("sect.not_enough_spirit_stones", Sect.DECLARE_WAR_SPIRIT_STONE_COST, spiritStones));
        }
        // 扣除灵石和声望（事务包裹）
        try {
            DatabaseManager.runTransaction(conn -> {
                if (!itemService.removeSpiritStones(conn, attackerPlayerId, Sect.DECLARE_WAR_SPIRIT_STONE_COST)) {
                    throw new SQLException("扣除灵石失败");
                }
                WAR_DAO.deductPrestige(conn, attackerSect.getId(), Sect.DECLARE_WAR_PRESTIGE_COST);
                return null;
            });
        } catch (Exception e) {
            return WarResult.fail(I18n.t("sect.failed_to_declare_war", e.getMessage()));
        }

        // 选出战成员：按境界和攻击力排序取前N名
        List<SectMember> attackerMembers = queryDomain.getSectMembers(attackerSect.getId());
        List<SectMember> attackerFighters = duelDomain.selectFighters(attackerMembers, Sect.WAR_MEMBERS_PER_SIDE);
        List<SectMember> defenderFighters = duelDomain.selectFighters(defenderMembers, Sect.WAR_MEMBERS_PER_SIDE);

        List<String> battleLog = new ArrayList<>();
        battleLog.add("⚔【" + attackerSect.getName() + "】VS【" + defenderSect.getName() + "】宗门战");
        battleLog.add("出战人数：" + attackerFighters.size() + " vs " + defenderFighters.size());
        battleLog.add("");

        int attackerWins = 0;
        int defenderWins = 0;
        int totalBattles = Math.min(attackerFighters.size(), defenderFighters.size());

        for (int i = 0; i < totalBattles; i++) {
            SectMember af = attackerFighters.get(i);
            SectMember df = defenderFighters.get(i);
            Player ap = playerService.getPlayerById(af.getPlayerId());
            Player dp = playerService.getPlayerById(df.getPlayerId());

            if (ap == null || dp == null) continue;

            battleLog.add("第" + (i + 1) + "场：" + ap.getName() + " VS " + dp.getName());
            String winner = duelDomain.simulateDuel(ap, dp, battleLog);
            if (winner.equals(ap.getName())) {
                attackerWins++;
                battleLog.add("  ▶ " + ap.getName() + " 获胜！");
            } else {
                defenderWins++;
                battleLog.add("  ▶ " + dp.getName() + " 获胜！");
            }
            battleLog.add("");
        }

        boolean attackerWin = attackerWins > defenderWins;
        String winnerSectName = attackerWin ? attackerSect.getName() : defenderSect.getName();
        long winnerSectId = attackerWin ? attackerSect.getId() : defenderSect.getId();

        battleLog.add("===== 宗门战结果 =====");
        battleLog.add(attackerSect.getName() + " " + attackerWins + "胜 - " + defenderWins + "胜 " + defenderSect.getName());
        battleLog.add("胜者：【" + winnerSectName + "】，额外获得 " + Sect.WAR_WIN_PRESTIGE + " 声望");

        // 发放声望奖励（赢家加、输家扣，下限 0）。
        // 刻意不在上面的事务里：拆分前就是两条独立的 UPDATE，合并进行务属于行为改动
        try {
            WAR_DAO.settleWarPrestige(winnerSectId,
                    attackerWin ? defenderSect.getId() : attackerSect.getId(),
                    Sect.WAR_WIN_PRESTIGE);
        } catch (RuntimeException e) {
            throw new RuntimeException("发放宗门战奖励失败", e);
        }

        // 保存战报
        String logText = String.join("\n", battleLog);
        try {
            WAR_DAO.saveWarLog(attackerSect.getId(), defenderSect.getId(), winnerSectId,
                    attackerWins, defenderWins, Sect.WAR_WIN_PRESTIGE, logText);
        } catch (RuntimeException e) {
            throw new RuntimeException("保存宗门战记录失败", e);
        }
        return WarResult.fought(
                I18n.t("sect.war_result",
                        attackerSect.getName(), defenderSect.getName(), winnerSectName,
                        attackerWins, defenderWins),
                logText, attackerWins, defenderWins, winnerSectName);
    }
}

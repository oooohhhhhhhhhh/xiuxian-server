package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.i18n.I18n;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宗门战域：宣战、逐场对拼、结算声望、落战报。
 *
 * <p>整场宗门战是<b>一次同步执行</b>的：宣战 → 扣灵石与声望 → 选出战成员 →
 * 逐场对拼 → 发放声望 → 保存战报。中间没有异步任务，因此这里的方法体是从
 * {@code SectService#declareWar} 整体搬过来的，一行未拆 —— 拆开反而会让
 * "扣了钱却没打起来"这类中间态变得可能。</p>
 *
 * <p>对拼规则本身在 {@link SectDuelDomain}（与宗主争夺战共用）；本类只负责
 * 组织流程与落库。方法体、SQL、战报文案、{@code Map} 里 key 的顺序都与拆分前逐字相同。</p>
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

    SectWarDomain(PlayerService playerService, ItemService itemService,
                  SectQueryDomain queryDomain, SectDuelDomain duelDomain) {
        this.playerService = playerService;
        this.itemService = itemService;
        this.queryDomain = queryDomain;
        this.duelDomain = duelDomain;
    }

    Map<String, Object> declareWar(long attackerPlayerId, long defenderSectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 实验性插件门控：宗门战停用后，REST / WebSocket / 指令三个入口统一拒绝
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown(SECT_WAR_PLUGIN)) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hfe4ed1bc"));
            return result;
        }
        SectMember attackerMember = queryDomain.getPlayerMember(attackerPlayerId);
        if (attackerMember == null || !attackerMember.isLeader()) {
            result.put("success", false); result.put("message", I18n.t("sect.h07a48c10")); return result;
        }

        Sect attackerSect = queryDomain.getSectById(attackerMember.getSectId());
        if (attackerSect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.ha5ef1b70")); return result;
        }
        if (attackerSect.getId() == defenderSectId) {
            result.put("success", false); result.put("message", I18n.t("sect.h12f51fdc")); return result;
        }

        Sect defenderSect = queryDomain.getSectById(defenderSectId);
        if (defenderSect == null) {
            result.put("success", false); result.put("message", I18n.t("sect.h091dc05c")); return result;
        }

        List<SectMember> defenderMembers = queryDomain.getSectMembers(defenderSectId);
        if (defenderMembers.isEmpty()) {
            result.put("success", false); result.put("message", I18n.t("sect.h50602204")); return result;
        }

        // 宗门战冷却：同一防守方 24 小时内只能被宣战一次，防止无休止骚扰
        long warCooldownMs = 24 * 3600 * 1000L;
        String lastWarSql = "SELECT MAX(created_at) FROM sect_wars WHERE defender_sect_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(lastWarSql)) {
            ps.setLong(1, defenderSectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getTimestamp(1) != null) {
                    long lastWarTime = rs.getTimestamp(1).getTime();
                    long remaining = warCooldownMs - (System.currentTimeMillis() - lastWarTime);
                    if (remaining > 0) {
                        result.put("success", false);
                        result.put("message", I18n.t("sect.hb6f9926c", Math.max(1, remaining / 3600000)));
                        return result;
                    }
                }
            }
        } catch (SQLException e) { throw new RuntimeException("查询宗门战记录失败", e); }

        if (attackerSect.getPrestige() < Sect.DECLARE_WAR_PRESTIGE_COST) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h72ced260", Sect.DECLARE_WAR_PRESTIGE_COST, attackerSect.getPrestige()));
            return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(attackerPlayerId);
        if (spiritStones < Sect.DECLARE_WAR_SPIRIT_STONE_COST) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h1009eea4", Sect.DECLARE_WAR_SPIRIT_STONE_COST, spiritStones));
            return result;
        }
        // 扣除灵石和声望（事务包裹）
        try {
            DatabaseManager.runTransaction(conn -> {
                if (!itemService.removeSpiritStones(conn, attackerPlayerId, Sect.DECLARE_WAR_SPIRIT_STONE_COST)) {
                    throw new SQLException("扣除灵石失败");
                }
                String deductPrestigeSql = "UPDATE sects SET prestige = prestige - ? WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deductPrestigeSql)) {
                    ps.setLong(1, Sect.DECLARE_WAR_PRESTIGE_COST);
                    ps.setLong(2, attackerSect.getId());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hfda59a4f", e.getMessage()));
            return result;
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

        // 发放声望奖励
        String rewardSql = "UPDATE sects SET prestige = prestige + ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(rewardSql)) {
                ps.setLong(1, Sect.WAR_WIN_PRESTIGE);
                ps.setLong(2, winnerSectId);
                ps.executeUpdate();
            }
            // 失败方扣声望
            long loserSectId = attackerWin ? defenderSect.getId() : attackerSect.getId();
            String loseSql = DatabaseManager.isSqlite()
                    ? "UPDATE sects SET prestige = MAX(0, prestige - ?) WHERE id = ?"
                    : "UPDATE sects SET prestige = GREATEST(0, prestige - ?) WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(loseSql)) {
                ps.setLong(1, Sect.WAR_WIN_PRESTIGE);
                ps.setLong(2, loserSectId);
                ps.executeUpdate();
            }
        } catch (SQLException e) { throw new RuntimeException("发放宗门战奖励失败", e); }

        // 保存战报
        String logText = String.join("\n", battleLog);
        String insertSql = "INSERT INTO sect_wars (attacker_sect_id, defender_sect_id, winner_sect_id, attacker_wins, defender_wins, prestige_stake, battle_log) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setLong(1, attackerSect.getId());
            ps.setLong(2, defenderSect.getId());
            ps.setLong(3, winnerSectId);
            ps.setInt(4, attackerWins);
            ps.setInt(5, defenderWins);
            ps.setLong(6, Sect.WAR_WIN_PRESTIGE);
            ps.setString(7, logText);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("保存宗门战记录失败", e); }

        result.put("success", true);
        result.put("message", I18n.t("sect.war_result",
                attackerSect.getName(), defenderSect.getName(), winnerSectName,
                attackerWins, defenderWins));
        result.put("battleLog", logText);
        result.put("attackerWins", attackerWins);
        result.put("defenderWins", defenderWins);
        result.put("winner", winnerSectName);
        return result;
    }
}

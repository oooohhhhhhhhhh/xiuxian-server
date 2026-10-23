package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.util.AppConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宗主域：缺位继位、主动退位、竞争期（发起 / 应战 / 到期清理）与低频扫描。
 *
 * <p>这一域解决的问题只有一个 ——「<b>宗主不玩了，宗门怎么活下去</b>」。
 * 拆分前它是 {@code SectService} 里最长的一段（约 600 行），并且自带一套
 * 配置项（缺位天数 / 竞争期天数 / 境界差门槛 / 扫描间隔）与并发不变量，
 * 因此单独成类：这些不变量分散到别的域里就没人看得全了。</p>
 *
 * <p>三条不变量原样保留（{@link #resolveSuccession} 的 javadoc 里有详细说明）：
 * 幂等（{@code leader_absent_since = 0} 或竞争期未结束时直接返回）、
 * 并发安全（落库用条件更新）、现任宗主不是候选人。</p>
 *
 * <p>方法体、SQL、日志文案、异常文案、{@code Map} 里 key 的顺序都与拆分前逐字相同。</p>
 *
 * <p><b>事务边界</b>：{@link #resolveSuccession} 与 {@link #acceptContest} 各有一个
 * {@code runTransaction}（条件更新 sects + 改两人职位），拆分时整块搬了过来，
 * 块内只使用事务传入的 {@code conn} —— 改职位走 {@link #setMemberRole} 的
 * {@code conn} 重载，绝不在事务里再开连接。其余方法（标记缺位、清理竞争期、
 * 发起挑战）在拆分前就是单条 SQL 的独立短连接，这里保持独立。</p>
 *
 * <p>对拼规则在 {@link SectDuelDomain}（与宗门战共用），本类只做归一化与流程编排。</p>
 */
final class SectSuccessionDomain {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** 归一化后强者战力至少保留的比例：再强的宗主也不会被压到弱者的一半以下。 */
    private static final double MIN_CONTEST_RATIO = 0.5;

    private final PlayerService playerService;
    private final SectQueryDomain queryDomain;
    private final SectDuelDomain duelDomain;

    SectSuccessionDomain(PlayerService playerService, SectQueryDomain queryDomain, SectDuelDomain duelDomain) {
        this.playerService = playerService;
        this.queryDomain = queryDomain;
        this.duelDomain = duelDomain;
    }

    // ==================== 配置 ====================

    /** 宗主连续不活跃多少天判定弃坑；0 = 关闭该判定（只保留主动退位路径）。 */
    static int leaderAbsentDays() {
        return Math.max(0, AppConfig.getInt("sect.leader_absent_days", Sect.LEADER_ABSENT_DAYS_DEFAULT));
    }

    /** 竞争期持续天数。 */
    static int contestDays() {
        return Math.max(1, AppConfig.getInt("sect.contest_days", Sect.CONTEST_DAYS_DEFAULT));
    }

    /** 竞争期开启的境界序位差门槛：小于该值才开竞争期。 */
    static int contestLevelGap() {
        return Math.max(0, AppConfig.getInt("sect.contest_level_gap", Sect.CONTEST_LEVEL_GAP_DEFAULT));
    }

    /** 继位扫描间隔（秒），最小 30 秒。 */
    static int successionSweepSeconds() {
        return Math.max(30, AppConfig.getInt("sect.succession_sweep_seconds", 600));
    }

    /**
     * 境界序位：把 (大境界, 小境界) 压成一个单调递增的整数。
     * <p>用于"继任者与竞争者实力是否接近"的判定。注意 {@code Player.level} 在本项目里
     * <b>并不随修炼推进</b>（只有建档与后台改库会写），因此不能拿它做实力比较。</p>
     */
    private static int realmStage(Player player) {
        if (player == null) {
            return 0;
        }
        return player.getRealmStage();
    }

    // ==================== 退位与继位 ====================

    /**
     * 宗主主动退位：卸任但仍留在宗门（降为副宗主），随后立刻按贡献选出继任者。
     *
     * <p>这是打破"宗主弃坑 → 宗门死锁"的关键入口：改造前宗主<b>既不能退位也不能退宗</b>
     * （{@code leaveSect} 直接拒绝），一旦宗主不玩了，这个宗门就永远没人能接手。
     * 现在宗主可以先 {@code /宗门 退位}，再正常 {@code /宗门 退出}。</p>
     */
    Map<String, Object> stepDown(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        SectMember member = queryDomain.getPlayerMember(playerId);
        if (member == null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h6b4e3074"));
            return result;
        }
        if (!member.isLeader()) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h0349c594"));
            return result;
        }

        Sect sect = queryDomain.getSectById(member.getSectId());
        if (sect == null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hbffa2528"));
            return result;
        }
        if (sect.getMemberCount() <= 1) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h2a6b3b5e"));
            return result;
        }

        long now = System.currentTimeMillis();
        markLeaderAbsent(sect.getId(), now);
        SectService.SuccessionOutcome outcome = resolveSuccession(sect.getId(), now);

        result.put("success", true);
        result.put("message", buildStepDownMessage(member.getPlayerName(), outcome));
        if (outcome.succeeded) {
            result.put("successorId", outcome.successorId);
            result.put("successorName", outcome.successorName);
            result.put("contestUntil", outcome.contestUntil);
        }
        return result;
    }

    /**
     * 按贡献继位：为缺位的宗门选出新宗主，并在继任者与最强竞争者实力接近时开启竞争期。
     *
     * <p>三条不变量：</p>
     * <ol>
     *   <li><b>幂等</b>：{@code leader_absent_since = 0} 或竞争期未结束时直接返回，不重复换人；</li>
     *   <li><b>并发安全</b>：落库用条件更新（{@code WHERE leader_absent_since = 读到的值}），
     *       指令与扫描任务同时触发时只有一个生效；</li>
     *   <li><b>现任宗主不是候选人</b>：被替换的人不会再被"选"回来 —— 否则退位等于没退。</li>
     * </ol>
     *
     * @param now 统一由调用方传入，便于测试注入时间（不要在方法内部取系统时间）
     */
    SectService.SuccessionOutcome resolveSuccession(long sectId, long now) {
        Sect sect = queryDomain.getSectById(sectId);
        if (sect == null) {
            return SectService.SuccessionOutcome.none("宗门不存在");
        }
        if (!sect.isLeaderAbsent()) {
            return SectService.SuccessionOutcome.none("宗主在任");
        }
        if (sect.getContestUntil() > now) {
            return SectService.SuccessionOutcome.none("宗主竞争期正在进行中");
        }

        List<SectMember> members = queryDomain.getSectMembers(sectId);
        long incumbentId = sect.getLeaderPlayerId();

        List<SectMember> candidates = new ArrayList<>();
        for (SectMember m : members) {
            if (m.getPlayerId() != incumbentId) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) {
            // 无人可继位：清掉缺位标记，等有新成员加入后由扫描任务重新判定。
            // 不清的话每次扫描都会重复命中同一条记录。
            clearLeaderAbsent(sectId, sect.getLeaderAbsentSince());
            return SectService.SuccessionOutcome.none("宗门内没有可继位的成员");
        }

        Map<Long, Player> players = loadPlayers(candidates);

        // 继任者：贡献 → 境界序位 → 玩家 ID（末位保证排序稳定，避免同分时结果抖动）
        candidates.sort(Comparator
                .comparingLong((SectMember m) -> -m.getContribution())
                .thenComparingInt(m -> -realmStage(players.get(m.getPlayerId())))
                .thenComparingLong(SectMember::getPlayerId));
        SectMember successor = candidates.get(0);

        // 竞争者：余下成员里「职位最高 → 境界最高 → 贡献最高」的第一名
        List<SectMember> rivals = new ArrayList<>(candidates.subList(1, candidates.size()));
        rivals.sort(Comparator
                .comparingInt(SectMember::getRoleRank)
                .thenComparingInt(m -> -realmStage(players.get(m.getPlayerId())))
                .thenComparingLong(m -> -m.getContribution())
                .thenComparingLong(SectMember::getPlayerId));
        SectMember rival = rivals.isEmpty() ? null : rivals.get(0);

        long contestUntil = 0;
        long challengerId = 0;
        if (rival != null) {
            int gap = realmStage(players.get(successor.getPlayerId())) - realmStage(players.get(rival.getPlayerId()));
            // 差距过大时挑战毫无意义，直接让继任者坐稳，省掉一次无意义的弹窗骚扰
            if (gap < contestLevelGap()) {
                contestUntil = now + (long) contestDays() * DAY_MS;
                challengerId = rival.getPlayerId();
            }
        }

        final long expectedAbsent = sect.getLeaderAbsentSince();
        final long newLeaderId = successor.getPlayerId();
        final long finalContestUntil = contestUntil;
        final long finalChallengerId = challengerId;

        Boolean applied = DatabaseManager.runTransaction(conn -> {
            int rows;
            String sql = "UPDATE sects SET leader_player_id = ?, leader_absent_since = 0, "
                    + "contest_until = ?, contest_challenger_id = ?, contest_requested = 0, "
                    + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND leader_absent_since = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, newLeaderId);
                ps.setLong(2, finalContestUntil);
                if (finalChallengerId > 0) {
                    ps.setLong(3, finalChallengerId);
                } else {
                    ps.setNull(3, Types.BIGINT);
                }
                ps.setLong(4, sectId);
                ps.setLong(5, expectedAbsent);
                rows = ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            if (rows == 0) {
                return false; // 状态已被其他请求推进，放弃本次继位
            }
            setMemberRole(conn, sectId, newLeaderId, SectMember.ROLE_LEADER);
            // 原宗主降为副宗主（若已退宗则这条 UPDATE 自然不命中任何行）
            if (incumbentId > 0) {
                setMemberRole(conn, sectId, incumbentId, SectMember.ROLE_VICE_LEADER);
            }
            return true;
        });

        if (!Boolean.TRUE.equals(applied)) {
            return SectService.SuccessionOutcome.none("继位状态已被其他请求处理");
        }

        SectSupport.LOG.info("宗门[" + sect.getName() + "] 宗主继位：playerId=" + newLeaderId
                + (challengerId > 0 ? "，开启竞争期至 " + contestUntil + "，挑战者 playerId=" + challengerId : ""));

        return SectService.SuccessionOutcome.success(
                newLeaderId,
                players.containsKey(newLeaderId) ? players.get(newLeaderId).getName() : successor.getPlayerName(),
                challengerId,
                challengerId > 0 && players.containsKey(challengerId) ? players.get(challengerId).getName() : null,
                contestUntil);
    }

    // ==================== 竞争期 ====================

    /**
     * 竞争期操作入口：挑战者发起、宗主查看状态、宗主应战。
     *
     * <p>两段式设计（而不是挑战者一发起就打）是为了给宗主一个明确的应战入口，
     * 也让"发起但宗主不应战"成为一种可记录的状态 —— 到期后宗主保住位置。</p>
     *
     * @param accept true = 宗主应战；false = 挑战者发起 / 宗主查看竞争期状态
     */
    Map<String, Object> challengeLeader(long playerId, boolean accept) {
        Map<String, Object> result = new LinkedHashMap<>();
        Sect sect = queryDomain.getPlayerSect(playerId);
        if (sect == null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h6b4e3074"));
            return result;
        }

        long now = System.currentTimeMillis();
        if (!sect.hasActiveContest(now)) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hd8088058"));
            return result;
        }

        boolean isChallenger = playerId == sect.getContestChallengerId();
        boolean isLeader = playerId == sect.getLeaderPlayerId();
        if (!isChallenger && !isLeader) {
            result.put("success", false);
            result.put("message", I18n.t("sect.he21dc790"));
            return result;
        }

        if (isChallenger) {
            if (accept) {
                result.put("success", false);
                result.put("message", I18n.t("sect.h3e4690e4"));
                return result;
            }
            return requestContest(sect, now);
        }

        // 宗主
        if (!accept) {
            result.put("success", true);
            result.put("message", buildContestStatus(sect));
            return result;
        }
        return acceptContest(sect, now);
    }

    /**
     * 竞争期 / 宗主缺位状态的一行式描述，供 {@code /宗门 info} 展示。
     *
     * @return 无竞争期且宗主在任时返回 {@code null}（调用方据此决定是否追加这一行）
     */
    String leadershipStatus(Sect sect) {
        if (sect == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (sect.isLeaderAbsent()) {
            return "宗主缺位中：等待同门按贡献继任（自 " + SectSupport.formatTime(sect.getLeaderAbsentSince()) + " 起）";
        }
        if (sect.hasActiveContest(now)) {
            SectMember challenger = queryDomain.getMember(sect.getId(), sect.getContestChallengerId());
            String name = challenger != null && challenger.getPlayerName() != null
                    ? challenger.getPlayerName() : ("UID " + sect.getContestChallengerId());
            return "宗主竞争期至 " + SectSupport.formatTime(sect.getContestUntil()) + "，挑战者：" + name
                    + (sect.isContestRequested() ? "（已发起，待宗主应战）" : "（尚未发起）");
        }
        return null;
    }

    /** 宗主视角的竞争期状态说明。 */
    private String buildContestStatus(Sect sect) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 宗主竞争期 =====\n");
        sb.append("截止：").append(SectSupport.formatTime(sect.getContestUntil())).append("\n");
        sb.append("挑战者：");
        Player rival = playerService.getPlayerById(sect.getContestChallengerId());
        sb.append(rival != null ? rival.getName() : ("UID " + sect.getContestChallengerId())).append("\n");
        sb.append("状态：").append(sect.isContestRequested() ? "挑战者已发起，等待你应战" : "挑战者尚未发起").append("\n");
        if (sect.isContestRequested()) {
            sb.append("\n使用 /宗门 挑战 接受 应战（三局两胜，双方战力归一化）。\n");
            sb.append("不应战则竞争期结束后你继续担任宗主。");
        } else {
            sb.append("\n挑战者需先发起挑战，你才能应战。");
        }
        return sb.toString();
    }

    /** 挑战者发起挑战：置 {@code contest_requested}，等宗主应战。 */
    private Map<String, Object> requestContest(Sect sect, long now) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (sect.isContestRequested()) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h937adf7c"));
            return result;
        }
        String sql = "UPDATE sects SET contest_requested = 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND contest_challenger_id = ? AND contest_until > ?";
        int rows;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sect.getId());
            ps.setLong(2, sect.getContestChallengerId());
            ps.setLong(3, now);
            rows = ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("发起宗主挑战失败", e);
        }
        if (rows == 0) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hb77b5854"));
            return result;
        }
        result.put("success", true);
        result.put("message", I18n.t("sect.h63a32578", SectSupport.formatTime(sect.getContestUntil())));
        return result;
    }

    /**
     * 宗主应战：三局两胜决定宗主之位。
     *
     * <p><b>归一化</b>：先把境界更高的一方按境界序位比例压低（下限 {@link #MIN_CONTEST_RATIO}），
     * 让争夺战考校的是灵根 / 装备 / 属性分配这些"构筑"，而不是单纯比谁境界高。
     * 归一化只作用于内存副本，<b>绝不回写</b>玩家真实属性。</p>
     */
    private Map<String, Object> acceptContest(Sect sect, long now) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!sect.isContestRequested()) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hb12ade10"));
            return result;
        }

        long leaderId = sect.getLeaderPlayerId();
        long challengerId = sect.getContestChallengerId();
        Player leader = playerService.getPlayerById(leaderId);
        Player challenger = playerService.getPlayerById(challengerId);
        if (leader == null || challenger == null) {
            result.put("success", false);
            result.put("message", I18n.t("sect.h07ebe188"));
            return result;
        }

        int leaderStage = realmStage(leader);
        int challengerStage = realmStage(challenger);
        Player left = leader;
        Player right = challenger;
        if (leaderStage > challengerStage) {
            left = scaleDown(leader, challengerStage, leaderStage);
        } else if (challengerStage > leaderStage) {
            right = scaleDown(challenger, leaderStage, challengerStage);
        }

        List<String> battleLog = new ArrayList<>();
        battleLog.add("===== 宗主争夺战（三局两胜）=====");
        battleLog.add("宗主：" + leader.getName() + "（境界序位 " + leaderStage + "）");
        battleLog.add("挑战者：" + challenger.getName() + "（境界序位 " + challengerStage + "）");
        battleLog.add("双方战力已归一化至相近水位，胜负取决于灵根与构筑。");

        int challengerWins = 0;
        for (int round = 1; round <= SectService.CONTEST_ROUNDS; round++) {
            battleLog.add("── 第 " + round + " 局 ──");
            boolean leaderWon = duelDomain.duelFirstWins(left, right, battleLog);
            if (!leaderWon) {
                challengerWins++;
            }
            battleLog.add("  → 本局胜者：" + (leaderWon ? leader.getName() : challenger.getName()));
        }

        boolean challengerTakes = challengerWins >= SectService.CONTEST_WINS_NEEDED;
        long winnerId = challengerTakes ? challengerId : leaderId;
        long loserId = challengerTakes ? leaderId : challengerId;
        String winnerName = challengerTakes ? challenger.getName() : leader.getName();
        String loserName = challengerTakes ? leader.getName() : challenger.getName();

        final long finalWinnerId = winnerId;
        final long finalLoserId = loserId;
        Boolean applied = DatabaseManager.runTransaction(conn -> {
            int rows;
            // 条件更新：只有"挑战已发起 + 挑战者/宗主都没变"才落库，杜绝双端重复结算
            String sql = "UPDATE sects SET leader_player_id = ?, contest_until = 0, "
                    + "contest_challenger_id = NULL, contest_requested = 0, leader_absent_since = 0, "
                    + "updated_at = CURRENT_TIMESTAMP "
                    + "WHERE id = ? AND contest_requested = 1 AND contest_challenger_id = ? AND leader_player_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, finalWinnerId);
                ps.setLong(2, sect.getId());
                ps.setLong(3, challengerId);
                ps.setLong(4, leaderId);
                rows = ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            if (rows == 0) {
                return false;
            }
            setMemberRole(conn, sect.getId(), finalWinnerId, SectMember.ROLE_LEADER);
            setMemberRole(conn, sect.getId(), finalLoserId, SectMember.ROLE_VICE_LEADER);
            return true;
        });

        if (!Boolean.TRUE.equals(applied)) {
            result.put("success", false);
            result.put("message", I18n.t("sect.hfad55360"));
            return result;
        }

        battleLog.add("");
        battleLog.add("══════════════════════");
        battleLog.add("挑战者胜局：" + challengerWins + " / " + SectService.CONTEST_ROUNDS);
        battleLog.add("【" + winnerName + "】继任宗主，" + loserName + " 改任副宗主");

        result.put("success", true);
        result.put("challengerWins", challengerWins);
        result.put("winnerId", winnerId);
        result.put("winnerName", winnerName);
        result.put("battleLog", String.join("\n", battleLog));
        result.put("message", I18n.t("sect.he75cb234", winnerName));
        return result;
    }

    // ==================== 扫描任务 ====================

    /**
     * 低频维护扫描：清理到期竞争期 → 标记不活跃宗主为缺位 → 执行继位。
     *
     * @param now 统一由调用方传入（测试可注入任意时间）
     * @return 本次实际处理（换宗主 / 标记缺位 / 清理竞争期）的宗门数量
     */
    int sweepLeaderSuccession(long now) {
        if (!DatabaseManager.isAvailable()) {
            return 0;
        }
        int handled = clearExpiredContests(now);

        int absentDays = leaderAbsentDays();
        if (absentDays > 0) {
            handled += markInactiveLeaders(now, absentDays);
        }

        for (long sectId : listAbsentLeaderSects(now)) {
            if (resolveSuccession(sectId, now).succeeded) {
                handled++;
            }
        }
        return handled;
    }

    /** 竞争期到期但未被挑战：宗主保住位置，只清字段。 */
    private int clearExpiredContests(long now) {
        String sql = "UPDATE sects SET contest_until = 0, contest_challenger_id = NULL, "
                + "contest_requested = 0, updated_at = CURRENT_TIMESTAMP "
                + "WHERE contest_until > 0 AND contest_until <= ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("清理到期宗主竞争期失败", e);
        }
    }

    /**
     * 把"长期不活跃的宗主"标记为缺位。
     *
     * <p>用 {@code players.last_active_time} 判定，且<b>只认 {@code > 0} 的行</b>：
     * 老库升级后该列全为 0（表示"尚未观测到活跃"），若把 0 当成纪元起点，
     * 升级瞬间会把全服宗主一次性判定成弃坑。</p>
     *
     * <p>SQL 刻意写成"先 SELECT id 再逐条 UPDATE"：MySQL 不允许在 UPDATE 的子查询里
     * 直接引用被更新的表（ERROR 1093），拆开写两种方言都安全。</p>
     */
    private int markInactiveLeaders(long now, int absentDays) {
        long cutoff = now - (long) absentDays * DAY_MS;
        List<Long> sectIds = new ArrayList<>();
        // 「成员数 > 1」是必须的：单人宗门无人可继位，标了缺位也只会在下一轮被清掉，
        // 徒增两次 UPDATE 并让 /宗门 info 的"宗主缺位中"来回闪烁。
        String sql = "SELECT s.id FROM sects s JOIN players p ON s.leader_player_id = p.id "
                + "WHERE s.leader_absent_since = 0 AND s.contest_until <= ? "
                + "AND p.last_active_time > 0 AND p.last_active_time < ? "
                + "AND (SELECT COUNT(*) FROM sect_members m WHERE m.sect_id = s.id) > 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sectIds.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询不活跃宗主失败", e);
        }

        int marked = 0;
        for (long sectId : sectIds) {
            marked += markLeaderAbsent(sectId, now);
        }
        return marked;
    }

    /** 列出"缺位且无进行中竞争期"的宗门，交给 {@link #resolveSuccession} 逐个处理。 */
    private List<Long> listAbsentLeaderSects(long now) {
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT id FROM sects WHERE leader_absent_since > 0 AND contest_until <= ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询宗主缺位的宗门失败", e);
        }
        return ids;
    }

    /** 标记宗主缺位（条件更新：仅在当前仍在任时生效）。返回受影响行数。 */
    private int markLeaderAbsent(long sectId, long now) {
        String sql = "UPDATE sects SET leader_absent_since = ?, contest_until = 0, "
                + "contest_challenger_id = NULL, contest_requested = 0, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND leader_absent_since = 0";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, sectId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("标记宗主缺位失败", e);
        }
    }

    /** 清除缺位标记（条件更新：仅在值未被他人改动时生效）。 */
    private void clearLeaderAbsent(long sectId, long expectedAbsentSince) {
        String sql = "UPDATE sects SET leader_absent_since = 0, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND leader_absent_since = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, expectedAbsentSince);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("清除宗主缺位标记失败", e);
        }
    }

    /** 事务内设置成员职位。 */
    private void setMemberRole(Connection conn, long sectId, long playerId, String role) {
        String sql = "UPDATE sect_members SET role = ? WHERE sect_id = ? AND player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role);
            ps.setLong(2, sectId);
            ps.setLong(3, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<Long, Player> loadPlayers(List<SectMember> members) {
        Map<Long, Player> players = new LinkedHashMap<>();
        for (SectMember member : members) {
            Player player = playerService.getPlayerById(member.getPlayerId());
            if (player != null) {
                players.put(player.getId(), player);
            }
        }
        return players;
    }

    /**
     * 按境界序位比例把强者压到弱者水位，返回<b>内存副本</b>（绝不回写真实属性）。
     */
    private Player scaleDown(Player stronger, int weakerStage, int strongerStage) {
        double ratio = (double) Math.max(1, weakerStage) / Math.max(1, strongerStage);
        ratio = Math.max(MIN_CONTEST_RATIO, Math.min(1.0, ratio));
        if (ratio >= 1.0) {
            return stronger;
        }
        Player copy = new Player();
        copy.setId(stronger.getId());
        copy.setName(stronger.getName());
        copy.setSpiritualRoot(stronger.getSpiritualRoot());
        copy.setRealm(stronger.getRealm());
        copy.setSubRealm(stronger.getSubRealm());
        copy.setMaxHp(scaleInt(stronger.getMaxHp(), ratio));
        copy.setHp(copy.getMaxHp());
        copy.setAttack(scaleInt(stronger.getAttack(), ratio));
        copy.setDefense(scaleInt(stronger.getDefense(), ratio));
        copy.setSpeed(scaleInt(stronger.getSpeed(), ratio));
        copy.setSpirit(scaleInt(stronger.getSpirit(), ratio));
        return copy;
    }

    private static int scaleInt(int value, double ratio) {
        return Math.max(1, (int) Math.round(value * ratio));
    }

    private String buildStepDownMessage(String formerLeaderName, SectService.SuccessionOutcome outcome) {
        if (!outcome.succeeded) {
            return "你已卸任宗主之位。\n" + (outcome.note != null ? outcome.note : "")
                    + "\n宗门暂由你继续代管，待有成员符合继位条件后自动交接。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你已卸任宗主之位，改任副宗主。\n");
        sb.append("宗门按贡献选出新宗主：").append(outcome.successorName != null ? outcome.successorName : "未知").append("\n");
        if (outcome.hasContest()) {
            sb.append("\n⚠ 你与新宗主的境界接近，已开启 ").append(contestDays()).append(" 天宗主竞争期：\n");
            sb.append("  挑战者：").append(outcome.rivalName != null ? outcome.rivalName : "未知").append("\n");
            sb.append("  截止：").append(SectSupport.formatTime(outcome.contestUntil)).append("\n");
            sb.append("  期内挑战者可发起三局两胜的争夺战，胜者继任宗主。");
        }
        sb.append("\n\n你现在可以正常使用 /宗门 退出 离开宗门。");
        return sb.toString();
    }
}

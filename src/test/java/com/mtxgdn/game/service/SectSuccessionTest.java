package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.sect.SectOperationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宗主缺位继位 / 竞争期（B5）回归测试。
 *
 * <p>覆盖的不变量：</p>
 * <ol>
 *   <li>宗主可以主动退位，系统按<b>贡献</b>选出继任者，退位者降为副宗主；</li>
 *   <li>继任者与最强竞争者境界接近才开竞争期，差距过大直接坐稳；</li>
 *   <li>竞争期是<b>两段式</b>：挑战者发起 → 宗主应战，未发起不能应战；</li>
 *   <li>继位<b>幂等</b>，并发/重复调用只生效一次；</li>
 *   <li>长期不活跃的宗主会被扫描任务判定弃坑并换人；</li>
 *   <li><b>升级安全</b>：{@code last_active_time = 0}（老库尚未观测到活跃）绝不能被判成弃坑；</li>
 *   <li>竞争期到期无人挑战 → 宗主保住位置，只清字段。</li>
 * </ol>
 */
class SectSuccessionTest extends TestBase {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final SectService sectService = new SectService();

    // ==================== 退位与继位 ====================

    @Test
    @DisplayName("退位: 按贡献选出继任者，退位者降为副宗主")
    void stepDownHandsOverToTopContributor() throws SQLException {
        long leader = createPlayer(5, 0, now());
        long topContributor = createPlayer(5, 0, now());
        long bystander = createPlayer(5, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 100);
        addMember(sectId, topContributor, SectMember.ROLE_OUTER_MEMBER, 500);
        addMember(sectId, bystander, SectMember.ROLE_OUTER_MEMBER, 10);

        Map<String, Object> result = sectService.stepDown(leader);

        assertTrue((Boolean) result.get("success"), String.valueOf(result.get("message")));
        assertEquals(topContributor, leaderOf(sectId), "贡献最高者应继任宗主");
        assertEquals(SectMember.ROLE_LEADER, roleOf(sectId, topContributor));
        assertEquals(SectMember.ROLE_VICE_LEADER, roleOf(sectId, leader), "退位者应降为副宗主");
    }

    @Test
    @DisplayName("退位: 退位后原宗主可以正常退宗（死锁已解除）")
    void stepDownUnblocksLeaderLeave() throws SQLException {
        long leader = createPlayer(6, 0, now());
        long heir = createPlayer(6, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 100);
        addMember(sectId, heir, SectMember.ROLE_OUTER_MEMBER, 900);

        // 改造前：宗主退宗会被直接拒绝
        var blocked = sectService.leaveSect(leader);
        assertFalse(blocked.success());
        assertTrue(String.valueOf(blocked.message()).contains("退位"),
                "拒绝文案必须指路 /宗门 退位，否则玩家无从下手：" + blocked.message());

        sectService.stepDown(leader);
        var allowed = sectService.leaveSect(leader);

        assertTrue(allowed.success(), String.valueOf(allowed.message()));
        assertNull(memberRow(sectId, leader), "退位后应已离开宗门");
    }

    @Test
    @DisplayName("退位: 宗门仅剩宗主一人时拒绝，并提示解散")
    void stepDownRefusedWhenAlone() throws SQLException {
        long leader = createPlayer(5, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 0);

        Map<String, Object> result = sectService.stepDown(leader);

        assertFalse((Boolean) result.get("success"));
        assertTrue(String.valueOf(result.get("message")).contains("解散"), String.valueOf(result.get("message")));
        assertEquals(leader, leaderOf(sectId), "拒绝退位后宗主不应变化");
    }

    @Test
    @DisplayName("退位: 非宗主不能退位")
    void stepDownRefusedForNonLeader() throws SQLException {
        long leader = createPlayer(5, 0, now());
        long member = createPlayer(5, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 100);
        addMember(sectId, member, SectMember.ROLE_OUTER_MEMBER, 900);

        Map<String, Object> result = sectService.stepDown(member);

        assertFalse((Boolean) result.get("success"));
        assertEquals(leader, leaderOf(sectId));
    }

    // ==================== 竞争期开启条件 ====================

    @Test
    @DisplayName("竞争期: 继任者与竞争者境界接近时开启，挑战者为最强竞争者")
    void contestOpensWhenStagesClose() throws SQLException {
        long leader = createPlayer(5, 0, now());
        long heir = createPlayer(5, 0, now());
        long rival = createPlayer(5, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 100);
        addMember(sectId, heir, SectMember.ROLE_OUTER_MEMBER, 500);
        addMember(sectId, rival, SectMember.ROLE_OUTER_MEMBER, 10);

        long before = System.currentTimeMillis();
        sectService.stepDown(leader);

        Sect sect = sectService.getSectById(sectId);
        assertNotNull(sect);
        assertEquals(heir, sect.getLeaderPlayerId());
        assertEquals(rival, sect.getContestChallengerId(), "境界相同的最强竞争者应成为挑战者");
        assertTrue(sect.getContestUntil() > before, "应开启竞争期");
        assertFalse(sect.isContestRequested(), "挑战者尚未发起");
    }

    @Test
    @DisplayName("竞争期: 境界差超过门槛时不开竞争期，继任者直接坐稳")
    void noContestWhenStageGapTooLarge() throws SQLException {
        long leader = createPlayer(8, 0, now());
        long heir = createPlayer(8, 0, now());
        long weakling = createPlayer(1, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 100);
        addMember(sectId, heir, SectMember.ROLE_OUTER_MEMBER, 500);
        addMember(sectId, weakling, SectMember.ROLE_OUTER_MEMBER, 10);

        sectService.stepDown(leader);

        Sect sect = sectService.getSectById(sectId);
        assertEquals(heir, sect.getLeaderPlayerId());
        assertEquals(0, sect.getContestUntil(), "境界差距过大不应开竞争期");
        assertEquals(0, sect.getContestChallengerId());
    }

    @Test
    @DisplayName("竞争期: 竞争者按「职位优先」挑选，而非只看境界")
    void rivalPrefersHigherPosition() throws SQLException {
        long leader = createPlayer(5, 0, now());
        long heir = createPlayer(5, 0, now());
        long elder = createPlayer(5, 0, now());
        long outerHighContribution = createPlayer(5, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 100);
        addMember(sectId, heir, SectMember.ROLE_OUTER_MEMBER, 900);
        addMember(sectId, elder, SectMember.ROLE_ELDER, 20);
        addMember(sectId, outerHighContribution, SectMember.ROLE_OUTER_MEMBER, 300);

        sectService.stepDown(leader);

        Sect sect = sectService.getSectById(sectId);
        assertEquals(heir, sect.getLeaderPlayerId(), "继任者按贡献");
        assertEquals(elder, sect.getContestChallengerId(), "挑战者应优先职位最高的长老");
    }

    // ==================== 两段式挑战 ====================

    @Test
    @DisplayName("挑战: 未发起时宗主应战被拒绝")
    void acceptRefusedBeforeRequest() throws SQLException {
        long[] ctx = buildContestSect();
        long sectId = ctx[0];
        long heir = ctx[1];

        Map<String, Object> result = sectService.challengeLeader(heir, true);

        assertFalse((Boolean) result.get("success"));
        assertTrue(String.valueOf(result.get("message")).contains("尚未发起"), String.valueOf(result.get("message")));
        assertEquals(heir, leaderOf(sectId), "未应战成功时宗主不应变化");
    }

    @Test
    @DisplayName("挑战: 挑战者发起 → 宗主应战 → 三局两胜换人并清理竞争期")
    void requestThenAcceptResolvesContest() throws SQLException {
        long[] ctx = buildContestSect();
        long sectId = ctx[0];
        long heir = ctx[1];
        long challenger = ctx[2];

        Map<String, Object> request = sectService.challengeLeader(challenger, false);
        assertTrue((Boolean) request.get("success"), String.valueOf(request.get("message")));
        assertTrue(sectService.getSectById(sectId).isContestRequested(), "发起后应置 contest_requested");

        // 挑战者自己不能应战
        Map<String, Object> selfAccept = sectService.challengeLeader(challenger, true);
        assertFalse((Boolean) selfAccept.get("success"));

        Map<String, Object> accept = sectService.challengeLeader(heir, true);
        assertTrue((Boolean) accept.get("success"), String.valueOf(accept.get("message")));
        assertNotNull(accept.get("battleLog"), "应战应产出战报");
        assertTrue(((Number) accept.get("challengerWins")).intValue() >= 0);

        long winnerId = ((Number) accept.get("winnerId")).longValue();
        long loserId = winnerId == challenger ? heir : challenger;

        Sect after = sectService.getSectById(sectId);
        assertEquals(winnerId, after.getLeaderPlayerId(), "胜者应为宗主");
        assertEquals(0, after.getContestUntil(), "竞争期应已结束");
        assertEquals(0, after.getContestChallengerId());
        assertFalse(after.isContestRequested());
        assertFalse(after.isLeaderAbsent(), "新宗主就位后缺位标记必须清除");
        assertEquals(SectMember.ROLE_LEADER, roleOf(sectId, winnerId));
        assertEquals(SectMember.ROLE_VICE_LEADER, roleOf(sectId, loserId), "败者应降为副宗主");
    }

    @Test
    @DisplayName("挑战: 无竞争期时任何人操作都被拒绝")
    void challengeRefusedWithoutContest() throws SQLException {
        long leader = createPlayer(5, 0, now());
        long member = createPlayer(5, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 100);
        addMember(sectId, member, SectMember.ROLE_OUTER_MEMBER, 10);

        assertFalse((Boolean) sectService.challengeLeader(member, false).get("success"));
        assertFalse((Boolean) sectService.challengeLeader(leader, true).get("success"));
    }

    // ==================== 幂等 / 并发 ====================

    @Test
    @DisplayName("幂等: 重复调用 resolveSuccession 只生效一次")
    void resolveSuccessionIsIdempotent() throws SQLException {
        long leader = createPlayer(8, 0, now());
        long heir = createPlayer(8, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 100);
        addMember(sectId, heir, SectMember.ROLE_OUTER_MEMBER, 500);

        long now = now();
        markAbsent(sectId, now);

        SectService.SuccessionOutcome first = sectService.resolveSuccession(sectId, now);
        SectService.SuccessionOutcome second = sectService.resolveSuccession(sectId, now);

        assertTrue(first.succeeded, first.note);
        assertEquals(heir, first.successorId);
        assertFalse(second.succeeded, "第二次应因缺位标记已清除而跳过");
        assertEquals(heir, leaderOf(sectId));
    }

    // ==================== 扫描任务：不活跃宗主 ====================

    @Test
    @DisplayName("扫描: 长期不活跃的宗主被判定弃坑，成员按贡献继位")
    void sweepReplacesInactiveLeader() throws SQLException {
        long inactiveLeader = createPlayer(9, 0, now() - 20 * DAY_MS);
        long activeMember = createPlayer(1, 0, now());
        long sectId = createSect(inactiveLeader);
        addMember(sectId, inactiveLeader, SectMember.ROLE_LEADER, 5000);
        addMember(sectId, activeMember, SectMember.ROLE_OUTER_MEMBER, 10);

        sectService.sweepLeaderSuccession(now());

        assertEquals(activeMember, leaderOf(sectId), "不活跃宗主应被替换");
        assertEquals(SectMember.ROLE_VICE_LEADER, roleOf(sectId, inactiveLeader));
        assertEquals(0, sectService.getSectById(sectId).getLeaderAbsentSince(), "继位后缺位标记应清除");
    }

    @Test
    @DisplayName("扫描: 升级安全 —— last_active_time=0 的宗主绝不能被判成弃坑")
    void sweepNeverTreatsUnknownActivityAsAbsent() throws SQLException {
        long neverSeenLeader = createPlayer(5, 0, 0); // 老库升级后该列全为 0
        long member = createPlayer(5, 0, now());
        long sectId = createSect(neverSeenLeader);
        addMember(sectId, neverSeenLeader, SectMember.ROLE_LEADER, 10);
        addMember(sectId, member, SectMember.ROLE_OUTER_MEMBER, 9000);

        sectService.sweepLeaderSuccession(now());

        assertEquals(neverSeenLeader, leaderOf(sectId),
                "last_active_time=0 表示尚未观测到活跃，不能当作纪元起点，否则升级瞬间会全服换宗主");
    }

    @Test
    @DisplayName("扫描: 活跃宗主不会被替换")
    void sweepKeepsActiveLeader() throws SQLException {
        long leader = createPlayer(5, 0, now());
        long member = createPlayer(5, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 10);
        addMember(sectId, member, SectMember.ROLE_OUTER_MEMBER, 9000);

        sectService.sweepLeaderSuccession(now());

        assertEquals(leader, leaderOf(sectId));
    }

    @Test
    @DisplayName("扫描: 单人宗门即使宗主不活跃也不标缺位（无人可继位）")
    void sweepIgnoresSoloInactiveSect() throws SQLException {
        long lonelyLeader = createPlayer(5, 0, now() - 60 * DAY_MS);
        long sectId = createSect(lonelyLeader);
        addMember(sectId, lonelyLeader, SectMember.ROLE_LEADER, 0);

        sectService.sweepLeaderSuccession(now());

        Sect sect = sectService.getSectById(sectId);
        assertEquals(lonelyLeader, sect.getLeaderPlayerId());
        assertEquals(0, sect.getLeaderAbsentSince(),
                "单人宗门标了缺位也无人继位，只会让状态来回闪烁");
    }

    // ==================== 扫描任务：竞争期到期 ====================

    @Test
    @DisplayName("扫描: 竞争期到期无人应战 → 宗主保住位置，竞争字段清空")
    void sweepClearsExpiredContest() throws SQLException {
        long leader = createPlayer(5, 0, now());
        long challenger = createPlayer(5, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 100);
        addMember(sectId, challenger, SectMember.ROLE_OUTER_MEMBER, 200);

        long now = now();
        setContest(sectId, now - 1000, challenger, true);

        sectService.sweepLeaderSuccession(now);

        Sect sect = sectService.getSectById(sectId);
        assertEquals(leader, sect.getLeaderPlayerId(), "无人应战，现任宗主继续担任");
        assertEquals(0, sect.getContestUntil());
        assertEquals(0, sect.getContestChallengerId());
        assertFalse(sect.isContestRequested());
    }

    @Test
    @DisplayName("扫描: 竞争期未到期不会被清理")
    void sweepKeepsLiveContest() throws SQLException {
        long leader = createPlayer(5, 0, now());
        long challenger = createPlayer(5, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 100);
        addMember(sectId, challenger, SectMember.ROLE_OUTER_MEMBER, 200);

        long now = now();
        long until = now + 3 * DAY_MS;
        setContest(sectId, until, challenger, false);

        sectService.sweepLeaderSuccession(now);

        Sect sect = sectService.getSectById(sectId);
        assertEquals(until, sect.getContestUntil());
        assertEquals(challenger, sect.getContestChallengerId());
    }

    // ==================== 辅助 ====================

    /** 建一个"已退位 + 竞争期已开启"的宗门，返回 {sectId, 现任宗主, 挑战者}。 */
    private long[] buildContestSect() throws SQLException {
        long leader = createPlayer(5, 0, now());
        long heir = createPlayer(5, 0, now());
        long rival = createPlayer(5, 0, now());
        long sectId = createSect(leader);
        addMember(sectId, leader, SectMember.ROLE_LEADER, 100);
        addMember(sectId, heir, SectMember.ROLE_OUTER_MEMBER, 500);
        addMember(sectId, rival, SectMember.ROLE_OUTER_MEMBER, 10);
        sectService.stepDown(leader);

        Sect sect = sectService.getSectById(sectId);
        assertEquals(heir, sect.getLeaderPlayerId(), "前置条件：继任者已就位");
        assertEquals(rival, sect.getContestChallengerId(), "前置条件：竞争期已开启");
        return new long[]{sectId, heir, rival};
    }

    private long createPlayer(int realm, int subRealm, long lastActiveTime) throws SQLException {
        int n = SEQ.incrementAndGet();
        String username = "sectuser" + n;
        try (Connection conn = DatabaseManager.getConnection()) {
            long userId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, username);
                ps.setString(2, "x");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "用户写入失败");
                    userId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (user_id, name, realm, sub_realm, hp, max_hp, mp, max_mp, "
                            + "attack, defense, speed, spirit, last_active_time) "
                            + "VALUES (?, ?, ?, ?, 1000, 1000, 500, 500, 100, 50, 30, 20, ?)")) {
                ps.setLong(1, userId);
                ps.setString(2, "道友" + n);
                ps.setInt(3, realm);
                ps.setInt(4, subRealm);
                ps.setLong(5, lastActiveTime);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM players WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "角色写入失败");
                    return rs.getLong(1);
                }
            }
        }
    }

    private long createSect(long leaderPlayerId) throws SQLException {
        String name = "测试宗" + SEQ.incrementAndGet();
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sects (name, description, leader_player_id, level, prestige, max_members) "
                            + "VALUES (?, '', ?, 1, 0, 20)")) {
                ps.setString(1, name);
                ps.setLong(2, leaderPlayerId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM sects WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "宗门写入失败");
                    return rs.getLong(1);
                }
            }
        }
    }

    private void addMember(long sectId, long playerId, String role, long contribution) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO sect_members (sect_id, player_id, role, contribution) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            ps.setString(3, role);
            ps.setLong(4, contribution);
            ps.executeUpdate();
        }
    }

    private long leaderOf(long sectId) {
        Sect sect = sectService.getSectById(sectId);
        assertNotNull(sect, "宗门应存在");
        return sect.getLeaderPlayerId();
    }

    private String roleOf(long sectId, long playerId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT role FROM sect_members WHERE sect_id = ? AND player_id = ?")) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private Object memberRow(long sectId, long playerId) throws SQLException {
        return roleOf(sectId, playerId);
    }

    private void markAbsent(long sectId, long since) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE sects SET leader_absent_since = ? WHERE id = ?")) {
            ps.setLong(1, since);
            ps.setLong(2, sectId);
            ps.executeUpdate();
        }
    }

    private void setContest(long sectId, long until, long challengerId, boolean requested) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE sects SET contest_until = ?, contest_challenger_id = ?, contest_requested = ? "
                             + "WHERE id = ?")) {
            ps.setLong(1, until);
            ps.setLong(2, challengerId);
            ps.setBoolean(3, requested);
            ps.setLong(4, sectId);
            ps.executeUpdate();
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}

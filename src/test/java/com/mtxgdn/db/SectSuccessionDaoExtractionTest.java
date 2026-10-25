package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宗主继任域的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code SectSuccessionDomain} 原来 6 处
 * {@code DatabaseManager.getConnection()}，其中继位与宗主争夺战各是一个
 * {@code runTransaction}，块内"一条条件 UPDATE + 两次 {@code setMemberRole}"
 * 必须共用同一条 {@code conn}。</p>
 *
 * <p>这个域最容易踩的坑是<b>把条件更新的 WHERE 条件"顺手简化"</b>：继位用
 * {@code leader_absent_since = ?} 当乐观锁版本号，争夺战结算同时钉住三个值。
 * 少了任何一个，指令与扫描任务同时触发时两边都会判成功，后果是"换了两次宗主"
 * 或"下一轮扫描又把新宗主当缺位宗主再换一次"。</p>
 */
class SectSuccessionDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return SectSuccessionDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/SectSuccessionDomain.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/SectSuccessionDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                // 事务仍在服务层
                "DatabaseManager.runTransaction(",
                // 继位 / 争夺战的编排
                "SUCCESSION_DAO.applySuccession(conn,",
                "SUCCESSION_DAO.applyContestOutcome(conn,",
                "SUCCESSION_DAO.setMemberRole(conn,",
                // 业务规则：谁能继位、旧宗主怎么降级
                "incumbentId > 0",
                "SectMember.ROLE_VICE_LEADER");
    }

    @Override
    protected List<String> daoForbiddenTokens() {
        return List.of(
                "PlayerService", "I18n", "GameConfigLoader", "SectSuccessionDomain",
                "System.currentTimeMillis()");
    }

    @Test
    @DisplayName("六个意图各一对重载，自取连接版整体委托")
    void everyIntentHasBothOverloads() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String intent : new String[]{
                "markLeaderAbsent", "clearLeaderAbsent", "clearExpiredContests",
                "requestContest", "listAbsentLeaderSectIds", "findInactiveLeaderSectIds"}) {
            // 3 次 = 两个签名 + 自取连接版里那一行委托调用
            assertEquals(3, countOccurrences(code, intent + "("),
                    intent + " 应有自取连接版与 Connection 版两个重载，"
                            + "且自取连接版必须是整体委托（否则 SQL 会被复制两份）");
        }
        for (String sig : new String[]{
                "public int markLeaderAbsent(long sectId, long now)",
                "public void clearLeaderAbsent(long sectId, long expectedAbsentSince)",
                "public int clearExpiredContests(long now)",
                "public int requestContest(long sectId, long challengerPlayerId, long now)",
                "public List<Long> listAbsentLeaderSectIds(long now)",
                "public List<Long> findInactiveLeaderSectIds(long now, long cutoff)"}) {
            String body = bodyOf(code, sig);
            assertTrue(body.contains("DatabaseManager.getConnection()"),
                    sig + " 没有自己开连接");
            assertFalse(body.contains("UPDATE ") || body.contains("SELECT "),
                    sig + " 自己写了 SQL：同一条语句复制两份，改一处漏一处");
        }
    }

    @Test
    @DisplayName("事务内的三个方法只有 conn 版：没有自取连接版可误用")
    void transactionOnlyMethodsHaveNoSelfConnectionOverload() throws IOException {
        String code = stripComments(read(daoPath()));
        // 这三个方法只可能在 runTransaction 的 lambda 里被调用。
        // 给它们加自取连接版等于给"事务外单独改一张子表"开门。
        for (String sig : new String[]{
                "public int applySuccession(Connection conn,",
                "public int applyContestOutcome(Connection conn,",
                "public void setMemberRole(Connection conn,"}) {
            assertTrue(code.contains(sig), sig + " 应该存在");
            String body = bodyOf(code, sig);
            assertEquals(0, countOccurrences(body, "DatabaseManager.getConnection()"),
                    sig + " 只该跑在调用方的连接上");
        }
        // 三个方法名都不该出现第二次（没有自取连接重载）
        for (String name : new String[]{"applySuccession", "applyContestOutcome", "setMemberRole"}) {
            assertEquals(1, countOccurrences(code, name + "("),
                    name + " 只该有一个 Connection 版签名，多出来一个就是自取连接版");
        }
    }

    @Test
    @DisplayName("继位的乐观锁：WHERE 钉住 leader_absent_since = ?")
    void successionUsesAbsentSinceAsVersion() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public int applySuccession(Connection conn,");
        assertTrue(body.contains("WHERE id = ? AND leader_absent_since = ?"),
                "缺位起始时间是乐观锁版本号：去掉它，指令与扫描任务同时触发会换两次宗主");
        assertTrue(body.contains("leader_absent_since = 0"),
                "继位成功必须自己把缺位标记归 0：否则下一轮扫描又把新宗主当缺位宗主");
        assertTrue(body.contains("ps.setLong(5, expectedAbsentSince);"),
                "expectedAbsentSince 必须是第 5 个参数，顺序与 WHERE 里的占位符对应");
        // 参数顺序写反编译得过，逐个点名
        assertTrue(body.contains("ps.setLong(1, newLeaderPlayerId);"), "第 1 个参数是新宗主 id");
        assertTrue(body.contains("ps.setLong(4, sectId);"), "第 4 个参数是宗门 id");
    }

    @Test
    @DisplayName("争夺战结算同时钉住 contest_requested / challenger / leader 三个值")
    void contestOutcomePinsThreeValues() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public int applyContestOutcome(Connection conn,");
        assertTrue(body.contains("WHERE id = ? AND contest_requested = 1 "
                        + "AND contest_challenger_id = ? AND leader_player_id = ?"),
                "三个值一个都不能少：少任何一个，双端重复结算都会判成功");
        assertTrue(body.contains("leader_absent_since = 0"),
                "结算必须把缺位标记一并归 0，否则新宗主一就位又被下一轮扫描当成缺位");
        assertTrue(body.contains("contest_challenger_id = NULL"),
                "挑战者列用字面量 NULL 清，不是 0");
    }

    @Test
    @DisplayName("没有挑战者时写 SQL NULL 而不是 0")
    void nullChallengerIsRealNull() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public int applySuccession(Connection conn,");
        assertTrue(body.contains("ps.setNull(3, Types.BIGINT)"),
                "「无挑战者」与「挑战者是 0 号玩家」必须能区分，所以写 NULL");
        assertTrue(body.contains("if (challengerPlayerId > 0) {"),
                "只有 > 0 才写实值");
    }

    @Test
    @DisplayName("退位不动 leader_player_id，只清竞争期三列")
    void markLeaderAbsentKeepsLeaderId() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public int markLeaderAbsent(Connection conn,");
        assertFalse(body.contains("leader_player_id"),
                "退位只是「等继任者」，宗门仍然有宗主：动了 leader_player_id "
                        + "就把「宗主已退位」和「宗门没有宗主」混成一件事");
        assertTrue(body.contains("contest_until = 0, "), "竞争期截止时间要清");
        assertTrue(body.contains("contest_challenger_id = NULL"), "挑战者要清成 NULL");
        assertTrue(body.contains("contest_requested = 0"), "挑战请求标记要清");
        assertTrue(body.contains("WHERE id = ? AND leader_absent_since = 0"),
                "条件更新：重复调用不会推进缺位时间");
    }

    @Test
    @DisplayName("清理到期竞争期带 contest_until > 0，没有竞争期的行不被反复清")
    void clearExpiredContestsSkipsIdleRows() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public int clearExpiredContests(Connection conn,");
        assertTrue(body.contains("WHERE contest_until > 0 AND contest_until <= ?"),
                "contest_until > 0 让扫描计数只反映真正到期的那一批");
    }

    @Test
    @DisplayName("发起挑战是条件更新：竞争期结束或挑战者换人都挡在外面")
    void requestContestIsConditional() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public int requestContest(Connection conn,");
        assertTrue(body.contains("WHERE id = ? AND contest_challenger_id = ? AND contest_until > ?"),
                "三个条件对应三种「挑战已失效」，返回 0 行时调用方要给「竞争期已结束」的文案");
    }

    @Test
    @DisplayName("扫不活跃宗主刻意先 SELECT id：MySQL 不允许 UPDATE 子查询引用同表")
    void inactiveLeaderScanAvoidsError1093() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public List<Long> findInactiveLeaderSectIds(Connection conn,");
        assertTrue(body.contains("SELECT s.id FROM sects s JOIN players p ON s.leader_player_id = p.id"),
                "必须是 SELECT 而不是 UPDATE ... WHERE id IN (SELECT ...)：MySQL ERROR 1093");
        // 三个门槛一个都不能少
        assertTrue(body.contains("s.leader_absent_since = 0"), "已在缺位的不重复标");
        assertTrue(body.contains("p.last_active_time > 0"),
                "老库升级后该列全为 0，当成纪元起点会在升级瞬间把全服宗主判成弃坑");
        assertTrue(body.contains("COUNT(*) FROM sect_members m WHERE m.sect_id = s.id) > 1"),
                "单人宗门无人可继位，标了只会来回闪烁");
    }

    @Test
    @DisplayName("缺位列表的查询条件：缺位中且无进行中的竞争期")
    void absentLeaderListFiltersContest() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public List<Long> listAbsentLeaderSectIds(Connection conn,");
        assertTrue(body.contains("SELECT id FROM sects WHERE leader_absent_since > 0 AND contest_until <= ?"),
                "竞争期未结束的宗门不参与继位：继位与争夺战只能有一个发生");
    }

    @Test
    @DisplayName("异常文案逐字保留")
    void exceptionMessagesKept() throws IOException {
        String code = stripComments(read(daoPath()));
        // 每条异常文案只该出现一次：出现两次说明自取连接版与 conn 版各抛各的
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"标记宗主缺位失败\", e)"),
                "标记宗主缺位的异常文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"清除宗主缺位标记失败\", e)"),
                "清除缺位标记的异常文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"清理到期宗主竞争期失败\", e)"),
                "清理到期竞争期的异常文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"发起宗主挑战失败\", e)"),
                "发起宗主挑战的异常文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询宗主缺位的宗门失败\", e)"),
                "查询缺位宗门的异常文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询不活跃宗主失败\", e)"),
                "查询不活跃宗主的异常文案");
    }

    @Test
    @DisplayName("时间戳一律由调用方给，DAO 不自己取")
    void timestampsComeFromCaller() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("System.currentTimeMillis()"),
                "继位/竞争期的边界判定对「现在」极其敏感，DAO 自己取会让一次重试里的时间悄悄变化");
        assertFalse(code.contains("Instant.now()"), "同上");
    }

    @Test
    @DisplayName("setMemberRole 的 role 是绑定参数而不是字面量")
    void memberRoleIsParameter() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void setMemberRole(Connection conn,");
        assertTrue(body.contains("UPDATE sect_members SET role = ? WHERE sect_id = ? AND player_id = ?"),
                "role 走占位符：这个域既要升 LEADER 也要降 VICE_LEADER");
        assertTrue(body.contains("ps.setString(1, role);"), "第 1 个参数是角色名");
        assertFalse(body.contains("'LEADER'"),
                "不写死角色名 —— 与 SectRegistryDAO 那两个专用方法不同");
        assertFalse(body.contains("'VICE_LEADER'"),
                "同上：升与降都走同一个方法");
    }

    // 「DAO 不碰事务」由基类的 daoOwnsNoTransaction 覆盖，不在此重复。
}

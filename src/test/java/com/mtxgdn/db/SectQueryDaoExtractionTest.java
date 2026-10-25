package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宗门只读查询域的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code SectQueryDomain} 原来 8 处
 * {@code DatabaseManager.getConnection()}，而它是被其它五个域共用的查询域
 * （成员域查成员、仓库域、继位域列成员、金库域遍历全部宗门）。</p>
 *
 * <p>这个域的抽取风险不在"抽没抽出来"，而在<b>顺手改查询</b>：合并两条 SQL、
 * 把 INNER JOIN 统一成 LEFT JOIN、给成员查询补个 LIMIT、把相关子查询换成
 * JOIN + GROUP BY —— 每一处都是行为改动，而且测试很难发现（数据一多才显现）。
 * 所以下面的断言几乎都在钉 SQL 的逐字形状。</p>
 */
class SectQueryDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return SectQueryDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/SectQueryDomain.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/SectQueryDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                "QUERY_DAO.getAllSects()",
                "QUERY_DAO.getSectById(sectId)",
                "QUERY_DAO.getSectByName(name)",
                "QUERY_DAO.getPlayerSect(playerId)",
                "QUERY_DAO.getTopSects(limit)",
                "QUERY_DAO.getSectMembers(sectId)",
                "QUERY_DAO.getMember(sectId, playerId)",
                "QUERY_DAO.getPlayerMember(playerId)");
    }

    @Override
    protected List<String> daoForbiddenTokens() {
        return List.of(
                "INSERT ", "UPDATE ", "DELETE ",
                "setAutoCommit(", "runTransaction(", "commit(", "rollback(",
                "PlayerService", "I18n", "SectQueryDomain",
                "System.currentTimeMillis()", "CURRENT_TIMESTAMP");
    }

    @Test
    @DisplayName("八个意图各一对重载，自取连接版整体委托")
    void everyIntentHasBothOverloads() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String intent : new String[]{
                "getAllSects", "getSectById", "getSectByName", "getPlayerSect",
                "getTopSects", "getSectMembers", "getMember", "getPlayerMember"}) {
            // 3 次 = 两个签名 + 自取连接版里那一行委托调用
            assertEquals(3, countOccurrences(code, intent + "("),
                    intent + " 应有自取连接版与 Connection 版两个重载，"
                            + "且自取连接版必须是整体委托（否则 SQL 会被复制两份）");
        }
        for (String sig : new String[]{
                "public List<Sect> getAllSects()",
                "public Sect getSectById(long sectId)",
                "public Sect getSectByName(String name)",
                "public Sect getPlayerSect(long playerId)",
                "public List<Sect> getTopSects(int limit)",
                "public List<SectMember> getSectMembers(long sectId)",
                "public SectMember getMember(long sectId, long playerId)",
                "public SectMember getPlayerMember(long playerId)"}) {
            String body = bodyOf(code, sig);
            assertTrue(body.contains("DatabaseManager.getConnection()"),
                    sig + " 没有自己开连接");
            assertFalse(body.contains("SELECT ") || body.contains("prepareStatement("),
                    sig + " 自己写了 SQL：同一条语句复制两份，改一处漏一处");
        }
    }

    @Test
    @DisplayName("这个域必须一直只读：DAO 里一条写语句都没有")
    void daoStaysReadOnly() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("INSERT "), "查询域出现 INSERT 就破坏了「调用方无法判断会不会偷偷落库」的契约");
        assertFalse(code.contains("UPDATE "), "同上");
        assertFalse(code.contains("DELETE "), "同上");
        assertFalse(code.contains("setAutoCommit("), "DAO 不该动 autoCommit");
        assertFalse(code.contains("commit("), "DAO 不该提交事务");
        assertFalse(code.contains("rollback("), "DAO 不该回滚事务");
        assertFalse(code.contains("runTransaction("), "DAO 不该自己开事务");
        assertFalse(code.contains("CURRENT_TIMESTAMP"), "八条查询一条时间戳都不碰");
    }

    @Test
    @DisplayName("「全部宗门」与「前 N 名」是两条 SQL，没有合并")
    void allSectsAndTopSectsStaySeparate() throws IOException {
        String code = stripComments(read(daoPath()));
        String all = bodyOf(code, "public List<Sect> getAllSects(Connection conn)");
        String top = bodyOf(code, "public List<Sect> getTopSects(Connection conn,");
        // 全部宗门：两级排序，不带 LIMIT
        assertTrue(all.contains("ORDER BY s.prestige DESC, s.level DESC"),
                "全量列表按声望倒序、同声望再按等级倒序");
        assertFalse(all.contains("LIMIT"),
                "全量列表不带 LIMIT：WebSocket 宗门列表与 /宗门 列表 要的就是全量");
        // 排行：一级排序 + LIMIT
        assertTrue(top.contains("ORDER BY s.prestige DESC LIMIT ?"),
                "排行只按声望一级排序，且带 LIMIT");
        // 两条 SQL 都存在，没被合并成一个 findSects(int limit)。
        // 用 ORDER BY 的出现次数证明：全量列表是两级排序，排行是一级 + LIMIT。
        assertEquals(1, countOccurrences(code, "ORDER BY s.prestige DESC, s.level DESC"),
                "只有全量列表用两级排序");
        assertEquals(1, countOccurrences(code, "ORDER BY s.prestige DESC LIMIT ?"),
                "只有排行用一级排序 + LIMIT");
    }

    @Test
    @DisplayName("LIMIT 是绑定参数，不是字符串拼接")
    void limitIsBindParameter() throws IOException {
        String code = stripComments(read(daoPath()));
        String top = bodyOf(code, "public List<Sect> getTopSects(Connection conn,");
        assertTrue(top.contains("ORDER BY s.prestige DESC LIMIT ?"),
                "LIMIT 必须是占位符");
        assertTrue(top.contains("ps.setInt(1, limit);"),
                "用 setInt 绑定：拼字符串会打开 SQL 注入，用 setMaxRows 会改变 SQL 本身");
        assertFalse(top.contains("\"LIMIT \" +"), "不许拼接");
    }

    @Test
    @DisplayName("member_count 是标量相关子查询，不是 JOIN + GROUP BY")
    void memberCountIsCorrelatedSubquery() throws IOException {
        String code = stripComments(read(daoPath()));
        // 五条宗门查询每条都有自己的子查询
        assertEquals(5, countOccurrences(code,
                        "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count"),
                "五条宗门查询各自带这个相关子查询：它保证 s.* 那一行不会因为多 JOIN 一张表而重复，"
                        + "也就不需要 DISTINCT");
        assertFalse(code.contains("GROUP BY"),
                "没有 GROUP BY：相关子查询 + s.* 不需要分组");
        assertFalse(code.contains("DISTINCT"),
                "没有 DISTINCT：同上");
    }

    @Test
    @DisplayName("players 在五条宗门查询里都是 LEFT JOIN")
    void playersIsAlwaysLeftJoin() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(5, countOccurrences(code, "LEFT JOIN players p ON s.leader_player_id = p.id"),
                "宗主被删号时这一行还要在，只是 leader_name 退化为 null —— "
                        + "五条一条都不能改成 INNER JOIN");
    }

    @Test
    @DisplayName("getPlayerSect 的 sect_members 是 INNER JOIN，与其它查询不同")
    void playerSectUsesInnerJoin() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public Sect getPlayerSect(Connection conn, long playerId)");
        assertTrue(body.contains("FROM sects s JOIN sect_members sm ON s.id = sm.sect_id"),
                "INNER JOIN 让「不是这个宗门成员的人」直接查不到行，从而返回 null"
                        + "（= 玩家还没有宗门）");
        assertTrue(body.contains("LEFT JOIN players p ON s.leader_player_id = p.id"),
                "players 仍然是 LEFT JOIN：两个 JOIN 的左右之别是刻意的");
        assertTrue(body.contains("WHERE sm.player_id = ?"), "按玩家 id 过滤");
        // 子查询里的 sm 与外层同名，靠 SQL 作用域规则，逐字保留
        assertTrue(body.contains("(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id)"),
                "子查询的别名 sm 与外层同名是原实现如此，没有「顺手」改名");
    }

    @Test
    @DisplayName("成员列表的排序是 CASE 表达式 + 二级排序，且不带 LIMIT")
    void memberOrderIsCaseExpression() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public List<SectMember> getSectMembers(Connection conn, long sectId)");
        assertTrue(body.contains("CASE sm.role WHEN 'LEADER' THEN 0 WHEN 'VICE_LEADER' THEN 1 "
                        + "WHEN 'ELDER' THEN 2 WHEN 'INNER_MEMBER' THEN 3 ELSE 4 END, sm.joined_at"),
                "五档职位排序 + 同职位按加入时间正序（先入门在前）。"
                        + "ELSE 4 把外门弟子与未知角色一起排在最后，缺了它未知角色会排到最前面");
        assertFalse(body.contains("LIMIT"),
                "不带 LIMIT：服务层要拿全量去挑继任者和出战成员");
    }

    @Test
    @DisplayName("getMember / getPlayerMember 不带 ORDER BY、不带 LIMIT，只判一次 next()")
    void singleMemberQueriesHaveNoOrdering() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String sig : new String[]{
                "public SectMember getMember(Connection conn, long sectId, long playerId)",
                "public SectMember getPlayerMember(Connection conn, long playerId)"}) {
            String body = bodyOf(code, sig);
            assertFalse(body.contains("ORDER BY"), sig + " 不该有 ORDER BY");
            assertFalse(body.contains("LIMIT"), sig + " 不该有 LIMIT");
            assertTrue(body.contains("if (rs.next()) return mapMember(rs);"),
                    "(sect_id, player_id) 组合唯一，只判一次 next() 就是唯一行");
            assertTrue(body.contains("return null;"), "查不到返回 null，调用方靠 == null 走失败文案");
        }
    }

    @Test
    @DisplayName("列头是 s.* / sm.*，映射保留逐列容错")
    void mappingKeepsPerColumnFallback() throws IOException {
        String code = stripComments(read(daoPath()));
        // 五条宗门查询都用 s.*
        assertEquals(5, countOccurrences(code, "SELECT s.*, p.name AS leader_name"),
                "五条宗门查询的列头逐字保留，没有改成逐列");
        assertTrue(code.contains("private static Sect mapSect(ResultSet rs)"),
                "映射搬进了 DAO：SectSupport.mapSect 是 com.mtxgdn.game.service 包里的"
                        + "包级静态方法，db 包根本看不见，让 DAO 依赖服务层也是层级倒置");
        // 九列容错，一列都不能少（老库可能没有这些列）
        for (String column : new String[]{
                "leader_absent_since", "contest_until", "contest_challenger_id",
                "contest_requested", "treasury", "salary_per_member",
                "last_salary_time", "leader_name", "member_count"}) {
            assertTrue(code.contains("rs.getLong(\"" + column + "\")")
                            || code.contains("rs.getString(\"" + column + "\")")
                            || code.contains("rs.getInt(\"" + column + "\")")
                            || code.contains("rs.getBoolean(\"" + column + "\")"),
                    column + " 的容错读取不能丢：老库缺列时要退化成默认值，而不是让查询失败");
        }
    }

    @Test
    @DisplayName("SectMember.playerRealmName 依旧不被赋值")
    void playerRealmNameStillUnassigned() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("setPlayerRealmName("),
                "成员查询只 SELECT 了 p.name / p.realm / p.level，没有境界名列，"
                        + "mapMember 也从未 set 它。逐字保留，没有「补全」这个字段");
        String member = bodyOf(code, "public List<SectMember> getSectMembers(Connection conn, long sectId)");
        assertTrue(member.contains("SELECT sm.*, p.name AS player_name, p.realm AS player_realm, p.level AS player_level"),
                "三列玩家信息的列清单逐字保留");
    }

    @Test
    @DisplayName("异常文案按查询粒度区分，且每条只出现一次")
    void exceptionMessagesKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询宗门列表失败\", e)"),
                "列表的文案");
        assertEquals(2, countOccurrences(code, "throw new RuntimeException(\"查询宗门失败\", e)"),
                "getSectById 与 getSectByName 共用「查询宗门失败」是原实现如此");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询玩家宗门失败\", e)"),
                "玩家宗门的文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询宗门排行失败\", e)"),
                "排行的文案");
        assertEquals(3, countOccurrences(code, "throw new RuntimeException(\"查询宗门成员失败\", e)"),
                "三条成员查询共用「查询宗门成员失败」");
    }

    @Test
    @DisplayName("DAO 没有私有 SQL 片段方法：五条前缀各写一遍是刻意的")
    void noSharedSqlPrefixMethod() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("private static String sectSelectPrefix()"),
                "抽成私有常量或方法属于「顺手重构」：会让字符串拼接形状与拆分前不一致，"
                        + "也会让人以为改一处就能改全部");
        // 私有方法只有两个映射
        assertEquals(2, countOccurrences(code, "private static "),
                "私有方法只有 mapSect 与 mapMember 两个映射");
    }

    @Test
    @DisplayName("八个 Connection 版都 throws SQLException")
    void allConnectionOverloadsThrow() throws IOException {
        String code = stripComments(read(daoPath()));
        // 8 个 conn 版 + mapSect / mapMember 两个映射方法
        assertEquals(10, countOccurrences(code, "throws SQLException"),
                "八个 conn 版都要把 SQLException 抛给调用方的事务，两个映射方法也抛");
        for (String sig : new String[]{
                "public List<Sect> getAllSects(Connection conn) throws SQLException",
                "public Sect getSectById(Connection conn, long sectId) throws SQLException",
                "public Sect getSectByName(Connection conn, String name) throws SQLException",
                "public Sect getPlayerSect(Connection conn, long playerId) throws SQLException",
                "public List<Sect> getTopSects(Connection conn, int limit) throws SQLException",
                "public List<SectMember> getSectMembers(Connection conn, long sectId) throws SQLException",
                "public SectMember getMember(Connection conn, long sectId, long playerId) throws SQLException",
                "public SectMember getPlayerMember(Connection conn, long playerId) throws SQLException"}) {
            assertTrue(code.contains(sig), sig + " 应该存在且 throws SQLException");
        }
    }
}

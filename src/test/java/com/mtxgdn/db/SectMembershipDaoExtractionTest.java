package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宗门成员域的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code SectMembershipDomain} 原来 350 行里 10 处
 * {@code DatabaseManager.getConnection()}，{@code sect_applications} 与
 * {@code sect_members} 两张表的读写散落在申请、审批、退宗、踢人、任命、贡献六个方法里，
 * 而"宗主退宗要解散整个宗门"那段四连删写在事务 lambda 内部，
 * 只有读代码的人知道它必须同生共死。</p>
 */
class SectMembershipDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return SectMembershipDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/SectMembershipDomain.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/SectMembershipDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                "Sect.getMaxMembersForLevel",
                "canManage()",
                "canKickOrAppoint(",
                "TitleService",
                "OperationResult",
                "\"未知\"",
                "sect.role_already",
                "DatabaseManager.runTransaction(");
    }

    @Override
    protected List<String> daoForbiddenTokens() {
        return List.of("I18n", "OperationResult", "TitleService", "SectSupport");
    }

    @Test
    @DisplayName("六条语句逐字搬进 DAO，条件与列清单没有被顺手优化")
    void sqlIsVerbatim() throws IOException {
        String code = stripComments(read(daoPath()));
        // 重复申请判定：status = 'pending' 是存在性查询，原实现只判 rs.next()
        assertTrue(code.contains("SELECT id FROM sect_applications WHERE sect_id = ? AND player_id = ? AND status = 'pending'"),
                "重复申请的判定条件不能丢：只看 pending，已拒绝过的还能再申请");
        // 新成员固定外门弟子：字面量而不是绑定参数
        assertTrue(code.contains("INSERT INTO sect_members (sect_id, player_id, role) VALUES (?, ?, 'OUTER_MEMBER')"),
                "role 列写的是字面量 'OUTER_MEMBER'，不是占位符 —— 改成传参会多出一个调用方才能给的参数");
        // 成员数查询：宗主退宗前的门槛
        assertTrue(code.contains("SELECT COUNT(*) FROM sect_members WHERE sect_id = ?"),
                "退宗前的成员数门槛不能丢");
        // 贡献值：先改成员、命中且为正再给宗门加声望
        assertTrue(code.contains("UPDATE sect_members SET contribution = contribution + ? WHERE sect_id = ? AND player_id = ?"),
                "贡献值是累加不是覆盖");
        assertTrue(code.contains("UPDATE sects SET prestige = prestige + ? WHERE id = ?"),
                "加贡献的同时给宗门加声望，这条不能丢");
        // 申请列表的排序：按提交时间正序，先申请先审批
        assertTrue(code.contains("ORDER BY sa.created_at"),
                "待审申请按提交时间正序，不能改成倒序");
        assertTrue(code.contains("SELECT sa.*, p.name AS player_name FROM sect_applications sa "),
                "申请查询要 JOIN 出玩家名，列清单不能丢");
    }

    @Test
    @DisplayName("自取连接版整体委托给 Connection 版，SQL 一份都不复制")
    void everyIntentHasExactlyOneCopyOfSql() throws IOException {
        String code = stripComments(read(daoPath()));
        // 自取连接版一律委托，所以每条 SQL 只出现一次。
        // 出现两次说明某个重载把 SQL 又抄了一遍，改需求时会漏改其中一份。
        assertEquals(1, countOccurrences(code, "SELECT id FROM sect_applications WHERE sect_id = ? AND player_id = ? AND status = 'pending'"),
                "重复申请判定的 SQL 只该有一份");
        assertEquals(1, countOccurrences(code, "INSERT INTO sect_applications (sect_id, player_id, message) VALUES (?, ?, ?)"),
                "提交申请的 SQL 只该有一份");
        assertEquals(1, countOccurrences(code, "UPDATE sect_applications SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"),
                "改申请状态的 SQL 只该有一份");
        assertEquals(1, countOccurrences(code, "INSERT INTO sect_members (sect_id, player_id, role) VALUES (?, ?, 'OUTER_MEMBER')"),
                "加成员的 SQL 只该有一份");
        assertEquals(1, countOccurrences(code, "DELETE FROM sect_members WHERE sect_id = ? AND player_id = ?"),
                "删成员的 SQL 只该有一份");
        assertEquals(1, countOccurrences(code, "UPDATE sect_members SET role = ? WHERE sect_id = ? AND player_id = ?"),
                "任命的 SQL 只该有一份");
        assertEquals(1, countOccurrences(code, "UPDATE sect_members SET contribution = contribution + ? WHERE sect_id = ? AND player_id = ?"),
                "加贡献的 SQL 只该有一份");
        assertEquals(1, countOccurrences(code, "SELECT COUNT(*) FROM sect_members WHERE sect_id = ?"),
                "数成员的 SQL 只该有一份");
        assertEquals(1, countOccurrences(code, "UPDATE sects SET prestige = prestige + ? WHERE id = ?"),
                "加声望的 SQL 只该有一份");
        // 待审列表与单条申请共用同一段 SELECT 前缀，两个意图共两份
        assertEquals(2, countOccurrences(code, "SELECT sa.*, p.name AS player_name FROM sect_applications sa "),
                "待审列表与单条申请各一份 SQL，两份都带 JOIN 出来的 player_name");
        // 十个自取连接的重载 = 十次 getConnection，一个不多一个不少
        assertEquals(10, countOccurrences(code, "getConnection("),
                "十个自取连接的重载各开一条短连接；多出来说明某个 Connection 版又自己开了一条");
    }

    @Test
    @DisplayName("自取连接版整体委托给 Connection 版，不复制 SQL")
    void selfConnectingOverloadsDelegate() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String sig : List.of(
                "public boolean hasPendingApplication(long sectId, long playerId)",
                "public void insertApplication(long sectId, long playerId, String message)",
                "public List<SectApplication> findPendingApplications(long sectId)",
                "public SectApplication findApplicationById(long applicationId)",
                "public int updateApplicationStatus(long applicationId, String status)",
                "public void addMember(long sectId, long playerId)",
                "public int countMembers(long sectId)",
                "public void deleteMember(long sectId, long playerId)",
                "public int setMemberRole(long sectId, long playerId, String role)",
                "public int addContribution(long sectId, long playerId, long amount)")) {
            String body = bodyOf(code, sig);
            assertTrue(body.contains("DatabaseManager.getConnection()"),
                    sig + " 应该自己开一条短连接");
            assertTrue(body.contains("catch (SQLException e)"),
                    sig + " 必须把 SQLException 包成 RuntimeException，不能让受检异常漏到服务层");
            // 委托调用的形状：方法名(conn, 其余参数)
            int paren = sig.indexOf('(');
            String name = sig.substring(sig.lastIndexOf(' ', paren) + 1, paren);
            assertTrue(body.contains(name + "(conn,"),
                    sig + " 必须整体委托给 Connection 版，而不是把 SQL 再抄一遍");
        }
    }

    @Test
    @DisplayName("解散宗门的四连删只有 Connection 版，顺序不能反")
    void cascadeIsConnectionOnlyAndOrdered() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void deleteSectCascade(Connection conn, long sectId)");
        assertTrue(body.contains("throws SQLException"), "四连删直接把 SQLException 抛给事务 lambda");
        assertFalse(body.contains("DatabaseManager.getConnection()"),
                "解散只在事务里发生：给一个自取连接的重载，等于允许调用方在事务外把宗门拆一半");
        assertFalse(body.contains("runTransaction("), "DAO 不该自己开事务");
        // 顺序固定为 仓库 → 申请 → 成员 → 宗门，逐字对应原实现的四条语句
        int warehouse = body.indexOf("DELETE FROM sect_warehouse WHERE sect_id = ?");
        int applications = body.indexOf("DELETE FROM sect_applications WHERE sect_id = ?");
        int members = body.indexOf("DELETE FROM sect_members WHERE sect_id = ?");
        int sects = body.indexOf("DELETE FROM sects WHERE id = ?");
        assertTrue(warehouse >= 0 && applications > warehouse && members > applications && sects > members,
                "清库顺序必须是 仓库 → 申请 → 成员 → 宗门：先删成员就查不出这个宗门还有谁");
    }

    @Test
    @DisplayName("加贡献与加声望跑在同一条连接上，行数仍是业务分支依据")
    void addContributionSharesOneConnection() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public int addContribution(Connection conn, long sectId, long playerId, long amount)");
        assertTrue(body.contains("if (updated > 0 && amount > 0)"),
                "声望只在「成员真的更新了」且「贡献为正」时才加：负贡献（取出捐献）不该倒扣声望");
        assertTrue(body.indexOf("UPDATE sect_members SET contribution") < body.indexOf("UPDATE sects SET prestige"),
                "必须先改成员贡献再改宗门声望");
        assertTrue(body.contains("return updated;"),
                "返回成员表受影响行数，服务层据此区分「成员不存在」与「已更新」");
        // 自取连接版也必须是同一条连接，否则两步会落在两次独立提交上
        String selfBody = bodyOf(code, "public int addContribution(long sectId, long playerId, long amount)");
        assertTrue(selfBody.contains("addContribution(conn,"),
                "自取连接版整体委托，两步共用同一条连接");
    }

    @Test
    @DisplayName("成员数门槛与解散的事务边界留在服务层")
    void membershipRulesStayInService() throws IOException {
        String service = stripComments(read(servicePath()));
        String leaveBody = bodyOf(service, "OperationResult leaveSect(long playerId)");
        assertTrue(leaveBody.contains("MEMBERSHIP_DAO.countMembers(sect.getId()) > 1"),
                "「只剩一个人就不许退」是业务规则，判断必须留在服务层");
        assertTrue(leaveBody.contains("DatabaseManager.runTransaction(conn -> {"),
                "宗主退宗引发的解散必须仍是事务");
        assertTrue(leaveBody.contains("MEMBERSHIP_DAO.deleteSectCascade(conn, sect.getId())"),
                "事务内必须用 Connection 版四连删");
        int deleteIdx = leaveBody.indexOf("MEMBERSHIP_DAO.deleteMember(member.getSectId(), playerId)");
        int txIdx = leaveBody.indexOf("DatabaseManager.runTransaction(");
        assertTrue(deleteIdx >= 0 && txIdx > deleteIdx,
                "deleteMember 必须在事务之前单独执行 —— 位置与拆分前一致，挪进事务会改变提交边界");
        assertFalse(leaveBody.contains("getConnection("), "服务层不该再自己开连接");

        String applyBody = bodyOf(service, "OperationResult applyToSect(long playerId, long sectId, String message)");
        assertTrue(applyBody.contains("members.size() >= maxMembers"),
                "成员上限判断必须留在服务层");
        assertTrue(applyBody.contains("MEMBERSHIP_DAO.hasPendingApplication(sectId, playerId)"),
                "重复申请先查再插，两步顺序不能反");
        assertTrue(applyBody.indexOf("MEMBERSHIP_DAO.hasPendingApplication(")
                        < applyBody.indexOf("MEMBERSHIP_DAO.insertApplication("),
                "必须先查重再插入，否则唯一约束冲突的异常会直接冒到玩家脸上");
    }

    @Test
    @DisplayName("审批的称号授予与文案组装仍在服务层")
    void approvalSideEffectsStayInService() throws IOException {
        String service = stripComments(read(servicePath()));
        String body = bodyOf(service, "OperationResult approveApplication(long approverPlayerId, long applicationId, boolean approved)");
        assertTrue(body.contains("new com.mtxgdn.game.service.TitleService().grantTitle(app.getPlayerId(), \"senior_disciple\")"),
                "首次入宗授予「首席弟子」称号是业务副作用，必须留在服务层");
        assertTrue(body.contains("msg = \"已通过玩家【\" + app.getPlayerName() + \"】的加入申请\""),
                "通过/拒绝的文案逐字保留");
        assertTrue(body.contains("msg = \"已拒绝玩家【\" + app.getPlayerName() + \"】的加入申请\""),
                "拒绝文案逐字保留");
        assertTrue(body.contains("MEMBERSHIP_DAO.updateApplicationStatus(applicationId, status)"),
                "改申请状态走 DAO，但 accepted / rejected 的取值必须留在服务层");
    }

    @Test
    @DisplayName("DAO 不碰业务：没有文案、没有权限、没有 Map 组装")
    void daoStaysPurePersistence() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("I18n"), "DAO 不该做文案组装");
        assertFalse(code.contains("OperationResult"), "DAO 不该知道返回值 DTO");
        assertFalse(code.contains("TitleService"), "DAO 不该授予称号");
        assertFalse(code.contains("Sect.getMaxMembersForLevel"), "DAO 不该判成员上限");
        assertFalse(code.contains("canManage"), "DAO 不该做权限判断");
        assertFalse(code.contains("SectQueryDomain"), "DAO 不该回调查询域");
    }

    @Test
    @DisplayName("ResultSet → 实体的逐列容错搬进了 DAO")
    void rowMappingKeepsPerColumnTolerance() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("private static SectApplication mapApplication(ResultSet rs) throws SQLException"),
                "申请行的映射要跟着 SQL 走，否则 DAO 只能把 ResultSet 抛回服务层");
        assertTrue(code.contains("try { a.setPlayerName(rs.getString(\"player_name\")); } catch (SQLException ignored) {}"),
                "player_name 是 JOIN 出来的列，读不到时要退化为 null —— 老库或改表后不能整份列表失败");
        // 映射是私有方法，两个重载共用，不复制第二遍
        assertEquals(2, countOccurrences(code, "mapApplication(rs)"),
                "两个重载共用同一个映射方法，各调用一次");
    }

}

package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宗门建档模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code SectRegistryDomain} 原来 300 行里 1 处
 * {@code DatabaseManager.getConnection()}，另有三段写操作的 SQL 直接写在
 * {@code runTransaction} 的 lambda 体里（创建、转让、解散），
 * 解散更是四条 DELETE 挤在一个事务块内。</p>
 */
class SectRegistryDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return SectRegistryDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/SectRegistryDomain.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/SectRegistryDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of("createSect", "levelUp", "transferLeader", "disbandSect",
                "GameConfigLoader.getRealmConfig", "getPlayerById", "getSpiritStoneCount",
                "removeSpiritStones", "getPlayerSect", "getSectByName", "getPlayerMember",
                "CREATE_COST_SPIRIT_STONES", "TRANSFER_COST_SPIRIT_STONES", "getLevelUpCost",
                "getMaxMembersForLevel", "MAX_LEVEL");
    }

    @Test
    @DisplayName("创建仍是「扣灵石 + 插宗门 + 插 LEADER」一个事务，两步插库共用 conn")
    void createSectKeepsOneTransaction() throws IOException {
        String service = stripComments(read(servicePath()));
        assertEquals(3, countOccurrences(service, "DatabaseManager.runTransaction("),
                "创建、转让、解散原本各一个事务，合并或拆散都是改行为");
        int txStart = service.indexOf("DatabaseManager.runTransaction(");
        String body = service.substring(txStart, service.indexOf("if (sect != null)", txStart));
        assertTrue(body.contains("itemService.removeSpiritStones(conn, playerId, Sect.CREATE_COST_SPIRIT_STONES)"),
                "扣灵石仍走 itemService 的 conn 重载");
        assertTrue(body.contains("REGISTRY_DAO.createSect(conn,"),
                "插宗门必须用事务连接");
        assertTrue(body.contains("REGISTRY_DAO.insertLeaderMember(conn, sectId, playerId)"),
                "插 LEADER 成员行也必须用事务连接：自取连接会读不到未提交的宗门行");
        assertFalse(body.contains("DatabaseManager.getConnection()"),
                "事务块内绝不能再开连接");
        // 三步顺序不能乱
        assertTrue(body.indexOf("itemService.removeSpiritStones(conn")
                        < body.indexOf("REGISTRY_DAO.createSect(conn")
                        && body.indexOf("REGISTRY_DAO.createSect(conn")
                        < body.indexOf("REGISTRY_DAO.insertLeaderMember(conn"),
                "必须先扣灵石 → 插宗门 → 插成员行");
    }

    @Test
    @DisplayName("初始声望写死 1000、宗门 id 走 generated keys，两条都没丢")
    void createSectKeepsHardcodedPrestigeAndGeneratedKeys() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("Statement.RETURN_GENERATED_KEYS"),
                "必须拿 generated keys：sect_members 的 LEADER 行要用这个 id");
        assertTrue(dao.contains("ps.setLong(4, 1000L);"),
                "初始声望写死 1000：让宗门升级/宗门战在创建后即可积累，避免声望死锁");
        assertTrue(dao.contains("INSERT INTO sects (name, description, leader_player_id, prestige) VALUES (?, ?, ?, ?)"),
                "建宗门的列与列序必须逐字保留");
        assertTrue(dao.contains("INSERT INTO sect_members (sect_id, player_id, role) VALUES (?, ?, 'LEADER')"),
                "LEADER 成员行的 SQL 必须逐字保留");
        assertTrue(dao.contains("return keys.getLong(1);"), "把自增 id 交还给调用方");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("created.setPrestige(1000L);"),
                "构造返回的 Sect 时声望也是 1000：两边必须一致");
        assertTrue(service.contains("created.setMemberCount(1);"), "成员数是 1（只有宗主）");
        // 这条在注释里，必须读未剥注释的原文
        assertTrue(read(servicePath()).contains("避免事务内跨连接查询读不到未提交数据"),
                "「直接构造 Sect 返回」的理由要留在注释里，否则后人会改成查库");
    }

    @Test
    @DisplayName("解散的四条 DELETE 仍在同一事务，且顺序是子表→父表")
    void disbandKeepsFourDeletesInOrder() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("DELETE FROM sect_warehouse WHERE sect_id = ?"), "删仓库");
        assertTrue(dao.contains("DELETE FROM sect_applications WHERE sect_id = ?"), "删申请");
        assertTrue(dao.contains("DELETE FROM sect_members WHERE sect_id = ?"), "删成员");
        assertTrue(dao.contains("DELETE FROM sects WHERE id = ?"), "删宗门本身");
        // 解散的四步只在事务里被调用，所以四个方法只有 conn 版。
        // 唯一的自取连接方法是 levelUp —— 它原本就是事务外的单语句。
        assertEquals(1, countOccurrences(dao, "DatabaseManager.getConnection()"),
                "只有 levelUp 自取连接：解散四步原本就在 runTransaction 里，"
                        + "给它们加自取连接版等于鼓励有人在事务外单独删一张子表");
        String service = stripComments(read(servicePath()));
        int txStart = service.lastIndexOf("DatabaseManager.runTransaction(");
        String body = service.substring(txStart, service.indexOf("result.put(\"success\", true);", txStart));
        assertTrue(body.indexOf("REGISTRY_DAO.deleteWarehouse(conn")
                        < body.indexOf("REGISTRY_DAO.deleteApplications(conn")
                        && body.indexOf("REGISTRY_DAO.deleteApplications(conn")
                        < body.indexOf("REGISTRY_DAO.deleteMembers(conn")
                        && body.indexOf("REGISTRY_DAO.deleteMembers(conn")
                        < body.indexOf("REGISTRY_DAO.deleteSect(conn"),
                "必须子表清完再删 sects 父行，否则启用外键时会失败");
    }

    @Test
    @DisplayName("宗主转让仍是三条 UPDATE：换 leader + 降旧 + 升新")
    void transferKeepsThreeUpdates() throws IOException {
        String dao = stripComments(read(daoPath()));
        // 三条 UPDATE 各只有一个 conn 版（转让原本就在 runTransaction 里），
        // 所以每条 SQL 在 DAO 里只出现一次。
        assertEquals(1, countOccurrences(dao, "UPDATE sects SET leader_player_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"),
                "换 leader 的 SQL 逐字保留，且只写一遍");
        assertEquals(1, countOccurrences(dao, "UPDATE sect_members SET role = 'VICE_LEADER' WHERE sect_id = ? AND player_id = ?"),
                "旧宗主降副宗主");
        assertEquals(1, countOccurrences(dao, "UPDATE sect_members SET role = 'LEADER' WHERE sect_id = ? AND player_id = ?"),
                "新宗主升宗主");
        // 整个 DAO 只有 levelUp 一个自取连接方法（它原本就是事务外的单语句）。
        // 转让的三步、解散的四步、创建的两步都只有 conn 版。
        assertEquals(1, countOccurrences(dao, "DatabaseManager.getConnection()"),
                "只有 levelUp 该自取连接：其余方法只可能在 runTransaction 里被调用，"
                        + "给它们加自取连接版等于鼓励有人在事务外单独改一张子表");
        String service = stripComments(read(servicePath()));
        int txStart = service.indexOf("DatabaseManager.runTransaction(",
                service.indexOf("Map<String, Object> transferLeader("));
        String body = service.substring(txStart, service.indexOf("result.put(\"success\", true);", txStart));
        assertTrue(body.contains("REGISTRY_DAO.setLeader(conn")
                        && body.contains("REGISTRY_DAO.demoteToViceLeader(conn")
                        && body.contains("REGISTRY_DAO.promoteToLeader(conn"),
                "三条 UPDATE 都必须在同一个事务里");
        assertFalse(body.contains("DatabaseManager.getConnection()"),
                "事务块内绝不能再开连接");
        // 换 leader 必须在两次角色调整之前
        assertTrue(body.indexOf("REGISTRY_DAO.setLeader(conn")
                        < body.indexOf("REGISTRY_DAO.demoteToViceLeader(conn"),
                "先换 sects.leader_player_id 再调角色，否则中间态会出现两个 LEADER");
    }

    @Test
    @DisplayName("升级的三列一起改，且不带声望条件（校验在服务层）")
    void levelUpKeepsThreeColumnsWithoutGuard() throws IOException {
        String dao = stripComments(read(daoPath()));
        // SQL 在源码里跨两行拼接，stripComments 不合并字符串，所以逐段断言
        assertTrue(dao.contains("UPDATE sects SET level = ?, prestige = prestige - ?, max_members = ?,"),
                "三列必须在同一条 UPDATE 里");
        assertTrue(dao.contains("updated_at = CURRENT_TIMESTAMP WHERE id = ?"),
                "WHERE 与 updated_at 的口径逐字保留");
        assertFalse(dao.contains("AND prestige >= ?"),
                "升级原来就没有条件更新：够不够升级在服务层判断，"
                        + "不要顺手加保护，那会改变「声望刚好够」时的行为");
        assertTrue(dao.contains("throw new RuntimeException(\"宗门升级失败\", e)"),
                "失败文案沿用原实现");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("if (sect.getPrestige() < cost)"),
                "声望是否足够的校验留在服务层");
        assertTrue(service.contains("sect.getLevel() >= Sect.MAX_LEVEL"),
                "满级校验留在服务层");
    }

    @Test
    @DisplayName("DAO 不碰事务、不读配置、不认识玩家与物品服务")
    void daoOwnsNoBusinessRules() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("runTransaction("), "DAO 不该碰事务");
        assertFalse(dao.contains("setAutoCommit("), "DAO 不该改自动提交");
        assertFalse(dao.contains("AppConfig"), "DAO 不该读配置");
        assertFalse(dao.contains("PlayerService"), "DAO 不该依赖玩家服务");
        assertFalse(dao.contains("ItemService"), "DAO 不该依赖物品服务");
        assertFalse(dao.contains("GameConfigLoader"), "境界校验是业务，留在服务层");
        assertFalse(dao.contains("I18n"), "DAO 不该做国际化");
        assertFalse(dao.contains("TitleService"), "授予称号是业务，留在服务层");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("new com.mtxgdn.game.service.TitleService().grantTitle"),
                "创建成功授予「开山祖师」称号仍留在服务层");
        assertTrue(service.contains("sectName.trim().length() < 2"),
                "宗门名长度校验留在服务层");
    }
}

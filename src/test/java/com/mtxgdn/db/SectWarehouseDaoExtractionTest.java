package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宗门仓库模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code SectWarehouseDomain} 原来 248 行里 2 处
 * {@code DatabaseManager.getConnection()}，另有一段 upsert 的双方言分支
 * （SQLite {@code ON CONFLICT} / MySQL {@code ON DUPLICATE KEY UPDATE}）
 * 和两处条件 UPDATE 写在 {@code runTransaction} 的 lambda 体里。</p>
 */
class SectWarehouseDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return SectWarehouseDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/SectWarehouseDomain.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/SectWarehouseDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of("getItemContributionCost", "donateToWarehouse", "withdrawFromWarehouse",
                "getWarehouse", "isManager", "canManage", "contributionGain", "prestigeGain",
                "ItemRegistry.resolve", "hasItem", "removeItem", "addItem",
                "getWarehouseItemCount");
    }

    @Test
    @DisplayName("upsert 的双方言分支搬进了 DAO，服务层不再有方言判断")
    void upsertDialectsMovedToDao() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("ON CONFLICT(sect_id, item_key) DO UPDATE"),
                "SQLite 的 upsert 分支不能丢");
        assertTrue(dao.contains("ON DUPLICATE KEY UPDATE quantity = quantity + ?"),
                "MySQL 的 upsert 分支不能丢");
        // 捐献事务里的四步只用 conn 版，所以方言分支只该出现一次
        assertEquals(1, countOccurrences(dao, "isSqlite()"),
                "upsert 只有一个方言判断：事务内只走 conn 重载，不重复分支");
        assertFalse(stripComments(read(servicePath())).contains("isSqlite()"),
                "方言选择不该留在服务层");
    }

    @Test
    @DisplayName("取出仍是「单事务 + 两处条件扣减」，防止并发超取/重复取物")
    void withdrawKeepsSingleTransactionAndGuards() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("AND quantity >= ?"),
                "存量够不够与扣减必须在同一条 UPDATE 里完成，"
                        + "并发下两个成员同时取同一件物品，只有一个能扣成功");
        assertTrue(dao.contains("WHERE id = ? AND contribution >= ?"),
                "贡献值同样要条件扣减：先查再扣会让并发超取");
        // deductContributionIfEnough / deductWarehouseIfEnough 各一次
        assertEquals(2, countOccurrences(dao, "return ps.executeUpdate() > 0;"),
                "两处条件扣减都要把 executeUpdate() == 0 变成布尔返回值");
        String service = stripComments(read(servicePath()));
        // 事务块仍在服务层：DAO 不碰事务
        assertEquals(2, countOccurrences(service, "DatabaseManager.runTransaction("),
                "捐献与取出原本各一个事务，合并或拆散都是改行为");
        assertTrue(service.contains("throw new SQLException(\"贡献值不足\")"),
                "条件扣减失败仍要抛 SQLException 让整个事务回滚");
        assertTrue(service.contains("throw new SQLException(\"仓库物品数量不足\")"),
                "存量不足的失败路径不能丢");
    }

    @Test
    @DisplayName("捐献事务四步共享同一条 conn，没有一步自己开连接")
    void donateStepsShareOneConnection() throws IOException {
        String service = stripComments(read(servicePath()));
        int txStart = service.indexOf("DatabaseManager.runTransaction(");
        assertTrue(txStart >= 0, "捐献必须仍是事务");
        String body = service.substring(txStart, service.indexOf("result.put(\"success\", true);", txStart));
        assertTrue(body.contains("itemService.removeItem(conn, playerId, fullKey, quantity)"),
                "扣背包必须用事务连接");
        assertTrue(body.contains("WAREHOUSE_DAO.upsertWarehouse(conn,"), "入仓库必须用事务连接");
        assertTrue(body.contains("WAREHOUSE_DAO.addContribution(conn,"), "加贡献必须用事务连接");
        assertTrue(body.contains("WAREHOUSE_DAO.addPrestige(conn,"), "加声望必须用事务连接");
        assertFalse(body.contains("DatabaseManager.getConnection()"),
                "事务块内再开连接会撞 SQLITE_BUSY_SNAPSHOT，而且回滚不了");
        // 四步的顺序不能反：先扣背包再入仓库，否则仓库会凭空多出物品
        assertTrue(body.indexOf("itemService.removeItem(conn") < body.indexOf("WAREHOUSE_DAO.upsertWarehouse(conn"),
                "必须先扣背包再入仓库");
    }

    @Test
    @DisplayName("管理职位捐献不发贡献值：反刷贡献的口径没丢")
    void managerDonationGainsNoContribution() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("isManager"), "管理职位的判定必须留在服务层（读 member.canManage()）");
        assertTrue(service.contains("if (contributionGain > 0)"),
                "贡献值只在普通弟子捐献时才发，否则「取出→捐献→取出」可以无限刷");
        assertTrue(service.contains("Math.max(1L, quantity / 10L)"),
                "任何成员捐献都小幅加声望，这是宗门声望此前唯一的来源");
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("canManage"), "DAO 不该认识职位语义");
        assertFalse(dao.contains("contributionGain"), "发不发贡献是业务判断，DAO 只负责加多少");
    }

    @Test
    @DisplayName("取出成本仍是价格/5（纯函数，事务内也要能调）")
    void contributionCostStaysPureInService() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("Math.max(10, price / 5)"),
                "取物成本是价格/5、最低 10，比捐献所得（价格/10）高，保证套利必亏");
        assertTrue(service.contains("private long getItemContributionCost") || service.contains("long getItemContributionCost(Item item)"),
                "定价函数必须留在服务层：它是纯函数，要在事务块内被调用");
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("ItemRegistry"), "DAO 不该查物品注册表");
        assertFalse(dao.contains("getPrice()"), "DAO 不该知道物品价格");
    }

    @Test
    @DisplayName("仓库列表仍是 LEFT JOIN + 只显示有货的行")
    void warehouseListShapeKept() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("LEFT JOIN players p ON sw.donated_by_player_id = p.id"),
                "必须 LEFT JOIN：捐赠者被删号时这一行还要在");
        assertTrue(dao.contains("WHERE sw.sect_id = ? AND sw.quantity > 0"),
                "只显示还有货的行：取空的记录不该出现在列表里");
        assertTrue(dao.contains("ORDER BY sw.created_at"), "排序口径不能变");
        assertTrue(dao.contains("SELECT sw.*"), "列头保持 sw.*，不改成逐列");
    }

    @Test
    @DisplayName("查不到存量返回 0，不是 -1 也不是异常")
    void missingWarehouseRowReturnsZero() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("return 0;"), "查不到行按 0 处理，调用方随后就会给出「仓库不足」文案");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("return 0;"), "服务层的兜底也保持 0");
    }

    @Test
    @DisplayName("行 → 实体的映射留在服务层，DAO 只交扁平记录")
    void rowMappingStaysInService() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("record WarehouseItemRow"), "DAO 用扁平记录出行，像 MapDAO 那样");
        assertFalse(dao.contains("SectWarehouseItem"), "DAO 不该构造宗门实体");
        assertFalse(dao.contains("SectSupport"), "DAO 不该依赖宗门服务包");
        assertTrue(dao.contains("catch (SQLException ignored) {}"),
                "老库缺 donated_by_* 列时的容错要跟着记录一起搬过来");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("new SectWarehouseItem()"), "实体构造仍留在服务层");
        assertTrue(service.contains("row.donatedByName()"), "服务层从记录里取值");
    }
}

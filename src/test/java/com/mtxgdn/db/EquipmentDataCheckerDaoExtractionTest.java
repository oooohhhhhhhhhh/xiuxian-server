package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 启动期装备自检的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code EquipmentDataChecker} 原来 229 行里 1 处
 * {@code DatabaseManager.getConnection()}，外加六个 private 方法各自
 * new {@code PreparedStatement}，其中五个收 {@code conn}、一个自取连接。
 * 整个"读装备 → 修一个玩家"的循环体在 {@code runTransaction} 的 lambda 里，
 * 全靠手工传 {@code conn} 维持原子性。</p>
 *
 * <p>它<b>不是</b>只在构建期运行的一次性脚本：{@code Main} 启动时调
 * {@code checkEquipmentData()}，管理指令 {@code /修复装备} 还能随时调
 * {@code forceCheckEquipmentData()}。所以它和游戏内的其它服务一样，
 * 必须收进 DAO 边界内。</p>
 */
class EquipmentDataCheckerDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return EquipmentDataCheckerDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/EquipmentDataChecker.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/EquipmentDataCheckerDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of("hasRun", "checkEquipmentData", "forceCheckEquipmentData",
                "ItemRegistry.get", "ItemType.EQUIPMENT", "runTransaction",
                "装备数据自检已执行过，跳过", "开始检查装备数据一致性");
    }

    @Test
    @DisplayName("自检不是构建期脚本：启动入口与管理入口都在")
    void checkerRunsAtRuntimeNotOnlyAtBuildTime() throws IOException {
        String main = stripComments(read("src/main/java/com/mtxgdn/Main.java"));
        assertTrue(main.contains("new EquipmentDataChecker().checkEquipmentData()"),
                "Main 启动流程要调 checkEquipmentData —— 说明它在运行时跑，不是构建期工具");
        String cmd = stripComments(read("src/main/java/com/mtxgdn/onebot/command/admin/FixEquipmentCommand.java"));
        assertTrue(cmd.contains("forceCheckEquipmentData()"),
                "管理指令 /修复装备 要能强制再跑一次 —— 说明它有运行时的第二次调用");
    }

    @Test
    @DisplayName("每个玩家的修复步骤全部显式传 conn（否则修一半崩掉留下中间态）")
    void perPlayerFixSharesOneConnection() throws IOException {
        String service = stripComments(read(servicePath()));
        int start = service.indexOf("private boolean checkPlayerEquipment(");
        assertTrue(start >= 0, "找不到 checkPlayerEquipment");
        String body = service.substring(start);
        assertTrue(body.contains("DatabaseManager.runTransaction(conn ->"),
                "一个玩家的检查必须整批走一条事务连接");
        assertTrue(body.contains("CHECKER_DAO.getEquipment(conn, playerId)"), "读装备必须用事务连接");
        assertTrue(body.contains("CHECKER_DAO.removeFromEquipment(conn, playerId, slot)"),
                "删槽位必须用同一条事务连接");
        assertTrue(body.contains("CHECKER_DAO.getItemCount(conn, playerId, itemKey)"),
                "查背包数量必须用同一条事务连接");
        assertTrue(body.contains("CHECKER_DAO.removeItem(conn, playerId, itemKey, 1)"),
                "背包扣 1 必须用同一条事务连接");
        assertTrue(body.contains("CHECKER_DAO.getEquipmentKeys(conn, playerId)"),
                "读已装备 key 必须用同一条事务连接");
        assertTrue(body.contains("CHECKER_DAO.getBackpackItemKeys(conn, playerId)"),
                "读背包物品必须用同一条事务连接");
        assertTrue(body.contains("catch (java.sql.SQLException e)"),
                "lambda 内必须把 SQLException 包成 RuntimeException 抛给 runTransaction 回滚");
    }

    @Test
    @DisplayName("只有取玩家清单那一步自取连接（它在事务之外）")
    void onlyPlayerIdLookupTakesItsOwnConnection() throws IOException {
        String service = stripComments(read(servicePath()));
        assertEquals(0, countOccurrences(service, "getConnection("),
                "服务层一行 JDBC 都不该剩：取玩家清单已下沉到 DAO");
        assertTrue(service.contains("CHECKER_DAO.findAllPlayerIds()"),
                "先取玩家清单，再逐个开事务修 —— 这个顺序不能反");
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("public List<Long> findAllPlayerIds()"),
                "findAllPlayerIds 是唯一自取连接的方法");
        assertEquals(1, countOccurrences(dao, "getConnection("),
                "DAO 里只该有 findAllPlayerIds 一处自取连接");
    }

    @Test
    @DisplayName("背包里装备物品的过滤搬到了服务层（原实现在 DAO 侧读注册表）")
    void backpackEquipmentFilterMovedToService() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("ItemType.EQUIPMENT"),
                "按物品类型过滤装备必须是业务：DAO 不该认识 ItemType");
        assertTrue(service.contains("equipmentKeys.contains(itemKey)"),
                "「已装备则不提示」的规则必须留在服务层");
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("ItemType"), "DAO 不该碰物品类型");
        assertFalse(dao.contains("ItemRegistry"), "DAO 不该查物品注册表");
        assertTrue(dao.contains("SELECT item_key, quantity FROM players_items WHERE player_id = ? AND quantity > 0"),
                "quantity > 0 的条件不能丢（读出来只用于打日志，不参与修复）");
    }

    @Test
    @DisplayName("removeItem 的条件扣减 + 清理零行必须都在 Connection 重载里")
    void removeItemKeepsAtomicCleanup() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("quantity >= ?"),
                "「检查数量」与「扣除」必须在同一条 UPDATE 里，消除 TOCTOU");
        assertTrue(dao.contains("DELETE FROM players_items WHERE player_id = ? AND item_key = ? AND quantity <= 0"),
                "扣完必须清理零数量行，否则留下 quantity = 0 的僵尸行");
        assertFalse(dao.contains("public void removeItem(long playerId"),
                "removeItem 不该有自取连接的重载：它只在事务里被调用");
    }

    @Test
    @DisplayName("读失败只打日志不抛的降级语义保留在服务层")
    void readFailureDegradesGracefully() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("throw new RuntimeException(\"获取玩家列表失败\", e)"),
                "取玩家清单失败的文案不能改");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("LOG.error(\"检查玩家 \" + playerId + \" 的装备数据失败: \" + e.getMessage())"),
                "单个玩家失败不能让整个启动自检中断");
        assertTrue(service.contains("LOG.info(\"装备数据检查完成: 修复 \" + fixedCount + \" 个玩家, 错误 \" + errorCount + \" 个\")"),
                "汇总日志不能丢");
    }
}

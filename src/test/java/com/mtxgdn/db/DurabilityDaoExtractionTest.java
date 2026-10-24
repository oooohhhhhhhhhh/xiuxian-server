package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装备耐久模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code DurabilityService} 原来 306 行里 5 处
 * {@code DatabaseManager.getConnection()}，磨损与修理各写一遍 upsert 的双方言分支
 * （SQLite {@code ON CONFLICT} + {@code MAX} / MySQL
 * {@code ON DUPLICATE KEY UPDATE} + {@code GREATEST}），而"没有耐久记录时返回满耐久"
 * 这个语义散落在三个查询方法里。</p>
 */
class DurabilityDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return DurabilityDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/DurabilityService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/DurabilityDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of("getMaxDurability", "getPveWear", "getPvpWear",
                "getRepairPriceDivisor", "getSlotDisplayName", "isBroken",
                "BuffEffect", "ItemRegistry.get", "getBrokenPenalty",
                "applyWear", "repair", "describeSlot", "removeSpiritStones");
    }

    @Test
    @DisplayName("磨损与修理的 upsert 双方言分支都在 DAO 里，各只出现一次")
    void bothUpsertDialectsKeptOnce() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("ON CONFLICT(player_id, item_key)"), "SQLite 的磨损/修理 upsert 不能丢");
        assertTrue(code.contains("ON DUPLICATE KEY UPDATE"), "MySQL 的磨损/修理 upsert 不能丢");
        assertTrue(code.contains("durability = MAX(0, durability - ?)"), "SQLite 的不扣成负数保护不能丢");
        assertTrue(code.contains("durability = GREATEST(0, durability - ?)"), "MySQL 的不扣成负数保护不能丢");
        assertTrue(code.contains("durability = VALUES(durability)"), "MySQL 修理分支的 VALUES 回引不能丢");
        // 两处方言分支（wearSql / repairSql）+ 修理的两处"仅 SQLite 才绑第 4 参"。
        // 后者是原实现就有的不对称：MySQL 的 VALUES(durability) 会自己回引，
        // SQLite 的 DO UPDATE 不会，所以必须显式再绑一次，否则 SQLite 下修理会插错值。
        assertEquals(4, countOccurrences(code, "isSqlite()"),
                "磨损与修理各一次方言分支，外加修理的两处仅 SQLite 的第 4 参绑定");
        assertFalse(stripComments(read(servicePath())).contains("isSqlite()"),
                "方言选择不该留在服务层");
    }

    @Test
    @DisplayName("没有耐久记录 = 满耐久，这个语义不能丢")
    void missingRowMeansFullDurability() throws IOException {
        String code = stripComments(read(daoPath()));
        // 只有 4 参的 getDurability 直接 return maxDurability：
        // 3 参版委托给它，装备栏查询走 rs.wasNull() 补默认值。三条路径都要兜底。
        assertEquals(1, countOccurrences(code, "return maxDurability;"),
                "查不到耐久记录时必须返回满耐久，否则新装备一上来就是坏的");
        assertEquals(2, countOccurrences(code, "rs.wasNull()"),
                "LEFT JOIN 出来的 NULL 耐久要补成满耐久（两个方言分支各一次）");
        assertTrue(code.contains("LEFT JOIN player_item_durability"),
                "装备栏查询必须 LEFT JOIN：没有耐久行的装备也要出现，内连接会整行吞掉");
        // 满耐久值由服务层传入：DAO 不读配置项
        assertTrue(code.contains("maxDurability"), "满耐久值应由调用方传入");
    }

    @Test
    @DisplayName("损坏加成仍是服务层算：DAO 只交 item_key 列表")
    void brokenPenaltyStaysInService() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("BuffEffect"), "BuffEffect 的属性累加是业务，应留在服务层");
        assertTrue(service.contains("be.getAttackBonus()"), "四项加成累加应留在服务层");
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("BuffEffect"), "DAO 不该认识 BuffEffect");
        assertFalse(dao.contains("ItemRegistry"), "DAO 不该查物品注册表");
        assertTrue(dao.contains("findBrokenItemKeys"), "DAO 只负责查出损坏装备的 item_key");
    }

    @Test
    @DisplayName("磨损与修理都是批量落库，逐行跳过空 item_key 的逻辑不能丢")
    void batchWriteKeepsPerRowTolerance() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(4, countOccurrences(code, "addBatch()"),
                "磨损与修理各一对重载，都要 addBatch：多件装备要么一起磨损/修好，要么整批失败");
        assertEquals(4, countOccurrences(code, "executeBatch()"),
                "自取连接版与 Connection 版都要 executeBatch");
        assertEquals(4, countOccurrences(code, "if (itemKey == null || itemKey.isEmpty()) continue;"),
                "空 item_key 的槽位要跳过，否则会插入一条 (player_id, '', 0) 的垃圾行");
    }

    @Test
    @DisplayName("磨损失败不该让战斗报错：服务层吞掉并只打 warn")
    void applyWearFailureIsTolerated() throws IOException {
        String service = stripComments(read(servicePath()));
        int applyIdx = service.indexOf("public void applyWear(");
        assertTrue(applyIdx >= 0, "找不到 applyWear");
        String body = service.substring(applyIdx);
        assertTrue(body.contains("LOG.warn(\"装备磨损结算失败: \" + e.getMessage())"),
                "磨损失败只该打 warn：耐久是长期账，漏一次可以接受，报错会让整场战斗失败");
        // 价格计算与灵石扣除是业务，必须留在服务层
        assertTrue(body.contains("ItemRegistry.get(itemKey)"), "按物品价格折算修理单价应留在服务层");
        assertTrue(body.contains("itemService.removeSpiritStones(playerId, totalCost)"),
                "扣灵石必须留在服务层，DAO 只管写耐久");
    }

}

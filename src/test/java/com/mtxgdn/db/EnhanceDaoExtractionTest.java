package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 强化模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code EnhanceService} 原来 336 行里 2 处
 * {@code DatabaseManager.getConnection()}，外加 3 个 private 方法各自
 * new {@code PreparedStatement}。真正危险的是 {@code unequipItem}
 * （强化碎裂）—— 它要读装备、读等级、回退四项属性、最后删掉装备行，
 * 四步原实现靠手工传 {@code conn} 保证同连接。一旦某一步自己开连接，
 * "删装备行"与"扣属性"就会分属两次提交：装备消失了，白嫖的属性留下了。</p>
 */
class EnhanceDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return EnhanceDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/EnhanceService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/EnhanceDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of("MAX_ENHANCE_LEVEL", "MAX_PITY_STREAK", "PITY_BONUS_PER_FAIL",
                "ENHANCE_GOLD_COST", "ENHANCE_STONE_COST", "ENHANCE_SUCCESS_RATE",
                "ENHANCE_BREAK_RATE", "ENHANCE_DEGRADE_RATE", "FAIL_STREAK",
                "getEnhanceStatBonus", "checkGold", "buildEnhanceResult",
                "BuffEffect", "ItemRegistry.get", "addAttack", "addDefense",
                "addSpeed", "addSpirit", "protect_charm", "enhance_stone");
    }

    @Test
    @DisplayName("碎裂的四步必须全部显式传 conn（否则白嫖属性）")
    void unequipItemStepsShareOneConnection() throws IOException {
        String service = stripComments(read(servicePath()));
        int start = service.indexOf("private void unequipItem(");
        assertTrue(start >= 0, "找不到 unequipItem");
        String body = service.substring(start);
        assertTrue(body.contains("ENHANCE_DAO.getEquippedItemKey(conn, playerId, slot)"),
                "读 item_key 必须用事务连接");
        assertTrue(body.contains("ENHANCE_DAO.getEnhanceLevel(conn, playerId, slot)"),
                "读强化等级必须用同一条事务连接");
        assertTrue(body.contains("ENHANCE_DAO.deleteEquipment(conn, playerId, slot)"),
                "删装备行必须用同一条事务连接 —— 换成自取连接的重载，"
                        + "第 4 步回滚时前三步已经单独提交了");
        // 属性回退必须在同一个方法体里、同一条连接上
        assertTrue(body.contains("playerService.addAttack(conn, playerId, -be.getAttackBonus())"),
                "攻击回退必须走事务连接");
        assertTrue(body.contains("playerService.addDefense(conn, playerId, -be.getDefenseBonus())"),
                "防御回退必须走事务连接");
        assertTrue(body.contains("playerService.addSpeed(conn, playerId, -be.getSpeedBonus())"),
                "速度回退必须走事务连接");
        assertTrue(body.contains("playerService.addSpirit(conn, playerId, -be.getSpiritBonus())"),
                "灵力回退必须走事务连接");
        assertTrue(body.contains("LOG.warn(\"装备强化碎裂销毁: playerId=\" + playerId + \" slot=\" + slot"),
                "碎裂的 warn 日志不能丢：不可逆的资产损失，申诉时要能查证");
        assertTrue(body.contains("LOG.warn(\"强化碎裂时找不到装备定义，属性无法回退"),
                "找不到装备定义时的告警不能丢");
    }

    @Test
    @DisplayName("DAO 的写方法只收 Connection，没有自取连接的重载")
    void writeMethodsOnlyTakeConnection() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("public void setEnhanceLevel(Connection conn,"), "setEnhanceLevel 只收连接");
        assertTrue(dao.contains("public void deleteEquipment(Connection conn,"), "deleteEquipment 只收连接");
        assertFalse(dao.contains("public void setEnhanceLevel(long playerId"),
                "setEnhanceLevel 不该有自取连接的重载：它只在事务里被调用，"
                        + "给一个自取连接的版本就是在邀请别人在事务外调它");
        assertFalse(dao.contains("public void deleteEquipment(long playerId"),
                "deleteEquipment 不该有自取连接的重载，理由同上");
    }

    @Test
    @DisplayName("查询失败降级成 0 / null 的语义保留在服务层")
    void queryFailureDegradesGracefully() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("catch (EnhanceQueryException e)"),
                "查询失败只该被 EnhanceQueryException 捕获，不能把事务里的业务异常一起吞掉");
        assertEquals(2, countOccurrences(service, "catch (EnhanceQueryException e)"),
                "getEnhanceLevel 与 getEquippedItemKey 各一处降级");
        assertTrue(service.contains("LOG.error(\"查询强化等级失败: \" + e.getMessage(), e)"),
                "等级查询失败的日志文案不能改");
        assertTrue(service.contains("LOG.error(\"查询装备失败: \" + e.getMessage(), e)"),
                "装备查询失败的日志文案不能改");
        // 降级后必须真的返回兜底值
        int lvlIdx = service.indexOf("public int getEnhanceLevel(");
        int keyIdx = service.indexOf("public String getEquippedItemKey(");
        assertTrue(lvlIdx >= 0 && keyIdx > lvlIdx, "找不到两个查询方法");
        assertTrue(service.substring(lvlIdx, keyIdx).contains("return 0;"),
                "等级查询失败降级成 0（调用方当成没强化过）");
        assertTrue(service.substring(keyIdx, service.indexOf("public Map<String, Object> enhanceItem("))
                        .contains("return null;"),
                "装备查询失败降级成 null（调用方当成这个槽位没装备）");
    }

    @Test
    @DisplayName("强化概率表与保护符消耗是业务，必须留在服务层")
    void enhanceRulesStayInService() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("Math.min(95, successRate + pityStreak * PITY_BONUS_PER_FAIL)"),
                "连续失败保底的封顶计算必须留在服务层");
        assertTrue(service.contains("if (failRoll < breakRate && currentLevel >= 5)"),
                "碎裂只在 currentLevel >= 5 时触发，这个门槛必须留在服务层");
        assertTrue(service.contains("mtxgdn:protect_charm"),
                "有保护符时消耗一张、装备免碎 —— 这条规则必须留在服务层");
        assertTrue(service.contains("FAIL_STREAK.remove(pityKey)"), "成功后清零保底必须留在服务层");
        assertTrue(service.contains("itemService.removeItem(conn, playerId, \"mtxgdn:enhance_stone\", stoneCost)"),
                "扣强化石必须走事务连接");
        assertTrue(service.contains("playerService.addGold(conn, playerId, -goldCost)"),
                "扣金币必须走事务连接");
        assertTrue(service.contains("setEnhanceLevel(conn, playerId, slot, currentLevel + 1)"),
                "升级写库必须走事务连接");
        assertTrue(service.contains("setEnhanceLevel(conn, playerId, slot, currentLevel - 1)"),
                "降级写库必须走事务连接");
    }

    @Test
    @DisplayName("DAO 不认识 BuffEffect / ItemRegistry / PlayerService")
    void daoHasNoBusinessKnowledge() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("BuffEffect"), "DAO 不该认识 BuffEffect");
        assertFalse(dao.contains("ItemRegistry"), "DAO 不该查物品注册表");
        assertFalse(dao.contains("PlayerService"), "DAO 不该碰玩家服务");
        assertFalse(dao.contains("I18n"), "DAO 不该做文案");
        assertFalse(dao.contains("GameLogger"), "DAO 不该自己打日志");
    }

}

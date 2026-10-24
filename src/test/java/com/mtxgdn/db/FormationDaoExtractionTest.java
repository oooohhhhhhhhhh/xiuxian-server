package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阵法模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code FormationService} 原来 363 行里 3 处
 * {@code DatabaseManager.getConnection()}，而布置 / 升级 / 拆除 /
 * 用阵图布置都是"扣灵石 → 写阵法"的事务。最隐蔽的是
 * {@code deactivateFormation} —— 它既被事务内的 {@code removeFormation}
 * 调用，也被事务外的 {@code getActiveFormation}（读到过期阵法时当场失效）
 * 调用，两处必须落在同一条连接上。</p>
 */
class FormationDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return FormationDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/FormationService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/FormationDAO.java";
    }

    @Override
    protected java.util.List<String> serviceAnchors() {
        return java.util.List.of(
                "markDirty",
                "Formation.getPlaceCost",
                "Formation.getBaseDuration",
                "getSpiritEnergyBoostForLevel",
                "BuiltInPluginRegistry",
                "treasureKeyToFormationKey");
    }

    @Test
    @DisplayName("三条 formations 语句逐字搬进 DAO，列清单没有被顺手优化")
    void formationSqlIsVerbatim() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("SELECT * FROM formations WHERE player_id = ? AND active = 1 "
                        + "ORDER BY expires_at DESC LIMIT 1"),
                "取生效阵法的语句不能丢：ORDER BY expires_at DESC LIMIT 1 是"
                        + "「多行并存时取最晚过期那个」的语义");
        // insertSql() 是私有共享方法，所以这条 INSERT 只出现一次，
        // 且分三行拼接。逐段断言而不是断言拼接结果，重新分行不断言。
        assertTrue(code.contains("INSERT INTO formations (player_id, formation_key, name, level, "),
                "布置语句的列清单开头不能变");
        assertTrue(code.contains("spirit_energy_boost, cultivation_bonus, defense_bonus, heart_demon_resist, "),
                "布置语句的四项加成名不能变、不能加列");
        assertTrue(code.contains("duration_minutes, placed_at, expires_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"),
                "布置语句的列清单结尾、11 个占位符与 active 常量 1 不能变："
                        + "少一个占位符会在运行期才炸，改 active 会让阵法插进去就是失效状态");
        // 升级语句同样分三行拼接，逐段断言
        assertTrue(code.contains("UPDATE formations SET level = ?, spirit_energy_boost = ?, "),
                "升级语句的列清单开头不能变");
        assertTrue(code.contains("cultivation_bonus = ?, defense_bonus = ?, heart_demon_resist = ?, "),
                "升级只改四项加成，多改一列就会破坏阵法定价");
        assertTrue(code.contains("updated_at = CURRENT_TIMESTAMP WHERE id = ?"),
                "升级刷新 updated_at，但不延长剩余时间（expires_at 不在 SET 列表里）");
        assertTrue(code.contains("UPDATE formations SET active = 0 WHERE active = 1 AND expires_at < ?"),
                "定时结算用的是严格小于，等于临界值那一毫秒不算过期");
        // 失效语句在 DAO 里只应有一处文本（两个重载各自一份，共 2 次）
        assertEquals(2, countOccurrences(code, "UPDATE formations SET active = 0 WHERE id = ?"),
                "自取连接版与 Connection 版都要能失效单条阵法");
    }

    @Test
    @DisplayName("RETURN_GENERATED_KEYS 与 CURRENT_TIMESTAMP 的原语义保留")
    void insertKeepsGeneratedKeysFlag() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("Statement.RETURN_GENERATED_KEYS"),
                "插入语句原本就带 RETURN_GENERATED_KEYS，照原样保留");
        assertEquals(2, countOccurrences(code, "CURRENT_TIMESTAMP"),
                "只有升级语句刷 updated_at（自取连接版与 Connection 版各一次），"
                        + "插入与失效都不碰它 —— 让阵法在升级时也延长剩余时间会破坏定价");
    }

    @Test
    @DisplayName("拆除与「读到过期阵法当场失效」共用同一条 deactivate")
    void deactivateIsSharedByBothPaths() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("FORMATION_DAO.deactivate(f.getId())"),
                "读到过期阵法时必须当场失效它，否则库里一直挂着 active=1 的过期行");
        assertTrue(service.contains("FORMATION_DAO.deactivate(conn, formation.getId())"),
                "拆除必须走事务连接，与扣灵石同一次提交");
    }

    @Test
    @DisplayName("布置与升级的数值推导仍在服务层")
    void businessMathStaysInService() throws IOException {
        String service = stripComments(read(servicePath()));
        assertFalse(service.contains("getSpiritEnergyBoostForLevel(formationKey, level)") == false,
                "阵法加成数值必须留在服务层推导");
        assertTrue(service.contains("removeSpiritStones(conn,"),
                "灵石扣减必须留在服务层（且走事务连接）");
        assertTrue(service.contains("durationMs"),
                "时长的换算必须留在服务层");
        // 过期判定是实体上的业务规则
        assertTrue(service.contains("f.isExpired()"), "过期判定必须留在服务层");
    }

    @Test
    @DisplayName("DAO 不碰灵石/洞府/玩家快照")
    void daoStaysPurePersistence() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String token : java.util.List.of("markDirty", "ItemService", "removeSpiritStones",
                "getCaveByPlayerId", "BuiltInPluginRegistry", "runTransaction")) {
            assertFalse(code.contains(token), "DAO 不该出现 " + token);
        }
    }
}

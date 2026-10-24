package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能量模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code EnergyService} 原来 182 行里 5 处
 * {@code DatabaseManager.getConnection()}，而 upsert 的双方言分支
 * （SQLite {@code ON CONFLICT} / MySQL {@code ON DUPLICATE KEY UPDATE}）
 * 和 {@code INSERT IGNORE} 的两套写法都写在服务类里。</p>
 */
class EnergyDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return EnergyDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/EnergyService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/EnergyDAO.java";
    }

    @Override
    protected java.util.List<String> serviceAnchors() {
        return java.util.List.of(
                "registerItemEnergy",
                "unregisterItemEnergy",
                "resolveEnergyValue",
                "resolveConvertGain",
                "getCustomEnergyValues",
                "CONVERT_LOSS_RATE");
    }

    @Test
    @DisplayName("upsert 与 INSERT IGNORE 的双方言分支都在 DAO 里")
    void bothDialectsKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("ON CONFLICT(player_id)"), "SQLite 的 upsert 分支不能丢");
        assertTrue(code.contains("ON DUPLICATE KEY UPDATE energy = VALUES(energy)"),
                "MySQL 的 upsert 分支不能丢");
        assertTrue(code.contains("INSERT OR IGNORE INTO player_energy"), "SQLite 的建行分支不能丢");
        assertTrue(code.contains("INSERT IGNORE INTO player_energy"), "MySQL 的建行分支不能丢");
        assertEquals(2, countOccurrences(code, "isSqlite()"),
                "upsert 与建行各一次方言判断（自取连接的重载直接委托，不重复分支）");
        // 服务层不该再出现方言判断
        assertFalse(stripComments(read(servicePath())).contains("isSqlite()"),
                "方言选择不该留在服务层");
    }

    @Test
    @DisplayName("条件扣减的并发保护还在：AND energy >= ?")
    void conditionalRemoveKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("energy = energy - ?"), "扣减语句不能丢");
        assertTrue(code.contains("AND energy >= ?"),
                "「检查」与「扣减」必须在同一条 UPDATE 里完成，"
                        + "改成先 SELECT 再 UPDATE 就会在并发下扣成负数");
        assertEquals(2, countOccurrences(code, "AND energy >= ?"),
                "自取连接版与 Connection 版都要有这个条件");
    }

    @Test
    @DisplayName("addEnergy 仍先建行再累加，两步顺序不能反")
    void addEnergyStillEnsuresRowFirst() throws IOException {
        String service = stripComments(read(servicePath()));
        int ensureIdx = service.indexOf("ENERGY_DAO.ensureRowExists(playerId)");
        int addIdx = service.indexOf("ENERGY_DAO.addEnergy(playerId, amount)");
        assertTrue(ensureIdx >= 0 && addIdx > ensureIdx,
                "必须先 ensureRowExists 再 addEnergy：没有行时 UPDATE 影响 0 行，"
                        + "能量就静默丢了");
    }

}

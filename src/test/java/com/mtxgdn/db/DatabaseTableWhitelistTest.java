package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表名白名单、索引与"清除玩家数据"清单必须与实际 schema 一致。
 *
 * <p><b>三处真实缺陷</b>：</p>
 * <ol>
 *   <li>{@code ALLOWED_TABLES} 比 {@code DatabaseSchema} 实际建的表少 5 张
 *       （{@code player_item_durability}、{@code token_blacklist}、
 *       {@code trade_price_history}、{@code world_bosses}、{@code world_boss_damage}）——
 *       这些表在备份/还原与通用表操作里被当成"非法表名"直接拒掉，
 *       报错看起来像安全拦截，很容易被误判成"安全策略太严"。</li>
 *   <li>{@code chat_messages} 的索引只写在 {@code CREATE TABLE} 里且被
 *       {@code supportsIndexInCreateTable()} 包着 —— 只有 MySQL 执行，
 *       <b>SQLite 上一条索引都没有</b>，而 SQLite 是默认数据库。
 *       {@code player_action_logs} 更彻底：两种方言都没有任何索引。</li>
 *   <li>{@code clearPlayerData} 漏了 {@code player_item_durability}：
 *       清空玩家后耐久行成为孤儿，而 SQLite 的 {@code INTEGER PRIMARY KEY}
 *       会复用被删掉的最大 id，新玩家可能"继承"上一个玩家的装备耐久。</li>
 * </ol>
 */
class DatabaseTableWhitelistTest extends TestBase {

    @Test
    @DisplayName("数据库里的每一张表都必须被白名单接受")
    void everyExistingTablePassesWhitelist() throws SQLException {
        List<String> offenders = new ArrayList<>();
        for (String table : listTables()) {
            try {
                DatabaseManager.assertSafeTableName(table);
            } catch (RuntimeException e) {
                offenders.add(table + " -> " + e.getMessage());
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下表存在于数据库里但被白名单拒绝（备份/还原会失败）：\n"
                        + String.join("\n", offenders));
    }

    @Test
    @DisplayName("后来新增的 5 张表必须被白名单接受")
    void newlyAddedTablesAreWhitelisted() {
        for (String table : List.of("player_item_durability", "token_blacklist",
                "trade_price_history", "world_bosses", "world_boss_damage")) {
            assertDoesNotThrow(() -> DatabaseManager.assertSafeTableName(table),
                    "表 " + table + " 应被白名单接受");
        }
    }

    @Test
    @DisplayName("SQLite 上聊天记录与操作日志必须有索引")
    void indexesExistOnSqlite() throws SQLException {
        List<String> indexes = listIndexes();
        assertTrue(indexes.contains("idx_chat_channel"),
                "缺少 idx_chat_channel —— 该索引原本只写在 CREATE TABLE 里且只有 MySQL 执行，"
                        + "SQLite（默认数据库）上从未创建。现有索引: " + indexes);
        assertTrue(indexes.contains("idx_chat_receiver"), "缺少 idx_chat_receiver。现有: " + indexes);
        assertTrue(indexes.contains("idx_player_action_logs_created"),
                "缺少 idx_player_action_logs_created —— 该表原本一条索引都没有。现有: " + indexes);
        assertTrue(indexes.contains("idx_player_action_logs_user"), "缺少 idx_player_action_logs_user。现有: " + indexes);
        assertTrue(indexes.contains("idx_player_action_logs_qq"), "缺少 idx_player_action_logs_qq。现有: " + indexes);
    }

    @Test
    @DisplayName("清除玩家数据的表清单必须包含耐久表")
    void clearPlayerDataCoversDurabilityTable() {
        List<String> tables = List.of(DatabaseManager.CLEAR_PLAYER_TABLES);
        assertTrue(tables.contains("player_item_durability"),
                "clearPlayerData 必须清空 player_item_durability，否则孤儿行会被新玩家继承");
        assertTrue(tables.contains("players"), "清单里当然要有 players 本身");
    }

    @Test
    @DisplayName("清除玩家数据的表清单里不得出现非白名单表名")
    void clearPlayerDataOnlyUsesWhitelistedTables() {
        for (String table : DatabaseManager.CLEAR_PLAYER_TABLES) {
            assertDoesNotThrow(() -> DatabaseManager.assertSafeTableName(table),
                    "CLEAR_PLAYER_TABLES 里的 " + table + " 不在白名单里");
        }
    }

    @Test
    @DisplayName("操作日志保留策略：0/负数表示不清理")
    void retentionZeroMeansKeepEverything() {
        PlayerActionLogDAO dao = new PlayerActionLogDAO();
        assertEquals(0, dao.purgeOlderThanDays(0), "保留天数 0 表示永久保留，不应删除任何行");
        assertEquals(0, dao.purgeOlderThanDays(-1), "负数同样表示永久保留");
    }

    // ==================== 夹具 ====================

    private static List<String> listTables() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name FROM sqlite_master WHERE type = 'table' "
                             + "AND name NOT LIKE 'sqlite_%' ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static List<String> listIndexes() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name FROM sqlite_master WHERE type = 'index' AND name IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}

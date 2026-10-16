package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DatabaseMigration {

    private final DatabaseDialect dialect;
    private final boolean isSqlite;

    public DatabaseMigration(DatabaseDialect dialect, boolean isSqlite) {
        this.dialect = dialect;
        this.isSqlite = isSqlite;
    }

    public void executeMigrations(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            addVerificationCodesSentAtColumn(stmt);
            addPlayersBattleStrategyColumn(stmt);
            addPlayersLocationColumns(stmt);
            addPlayersBreakthroughTimeColumn(stmt);
            addEquipmentEnhanceLevelColumn(stmt);
            migrateRedeemCodesStatusColumn(stmt);
            migratePlayersItemsQuantityType(stmt);
            addRecipesQualityColumns(stmt);
            createPlayerBuffsTable(stmt);
            createFarmPlotsTable(stmt);
            addFarmPlotsAdditionalColumns(stmt);
            migrateQqOfficialBindings(stmt);
            migrateQqOfficialBindingsAppId(stmt);
            addPlayersTitleCounterColumns(stmt);
            addPlayerDailyPvpCountColumn(stmt);
            addTradeListingsExpiresAtColumn(stmt);
            addMapLocationBonusColumns(stmt);
            addMapDangerAndWildernessColumns(stmt);
            addPlayersLastGatherColumn(stmt);
        }
    }

    /**
     * 危险度与野外标记列：老库的 map_locations 补 danger_level / is_wilderness /
     * expires_at / entry_location_id。
     *
     * <p>与 {@link #addMapLocationBonusColumns} 不同，这里<b>带 DEFAULT</b>：危险度与野外标记
     * 都是"非 NULL 即已定稿"的语义，老库已有行应直接落到 0（安全 / 已知世界），
     * 而不是留 NULL 等 MapService 回填 —— 因为 0 就是这两个字段的合理出厂值，
     * 不需要区分"未播种"与"已设为 0"。</p>
     */
    private void addMapDangerAndWildernessColumns(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE map_locations ADD COLUMN danger_level INT DEFAULT 0");
        } catch (SQLException ignored) {
            // 列已存在（新库由 CREATE TABLE 直接建出）
        }
        try {
            stmt.execute("ALTER TABLE map_locations ADD COLUMN is_wilderness INT DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            // 已知世界地点恒为 NULL（永不过期）；野外节点才有值
            stmt.execute("ALTER TABLE map_locations ADD COLUMN expires_at BIGINT");
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE map_locations ADD COLUMN entry_location_id BIGINT");
        } catch (SQLException ignored) {
        }
    }

    /** 野外采集冷却列：老库的 players 补 last_gather_time。 */
    private void addPlayersLastGatherColumn(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE players ADD COLUMN last_gather_time BIGINT DEFAULT 0");
        } catch (SQLException ignored) {
        }
    }

    /**
     * 地界加成列：老库的 map_locations 补 cultivation_bonus / exploration_bonus。
     *
     * <p>此处刻意<b>不带 DEFAULT</b>：新库由 CREATE TABLE 直接建出带默认值的列，而老库升级后
     * 已有行会得到 NULL —— NULL 即"尚未播种"的标记，由 MapService 的补齐流程按出厂值回填。
     * 这样既不需要额外的迁移版本号，也不会覆盖管理员后来手工调整过的数值（非 NULL 即视为已定稿）。</p>
     */
    private void addMapLocationBonusColumns(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE map_locations ADD COLUMN cultivation_bonus DOUBLE");
        } catch (SQLException ignored) {
            // 列已存在（新库或已升级过的库）
        }
        try {
            stmt.execute("ALTER TABLE map_locations ADD COLUMN exploration_bonus DOUBLE");
        } catch (SQLException ignored) {
        }
    }

    /**
     * 坊市挂单时限：为既有库的 trade_listings 补 expires_at 列。
     * <p>
     * 旧行保持 NULL（视为永不过期），由 TradeService 在首次运行时按
     * created_at + 配置时长回填，避免升级瞬间把大量老挂单一次性清空。
     */
    private void addTradeListingsExpiresAtColumn(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE trade_listings ADD COLUMN expires_at TIMESTAMP DEFAULT NULL");
        } catch (SQLException ignored) {
            // 列已存在（新库由 CREATE TABLE 直接建出）
        }
    }

    /** 称号玩法计数列：秘境探索次数 / PVP 胜场 / 炼丹成功次数 */
    private void addPlayersTitleCounterColumns(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE players ADD COLUMN secret_realm_count INT DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE players ADD COLUMN pvp_wins INT DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE players ADD COLUMN craft_count INT DEFAULT 0");
        } catch (SQLException ignored) {
        }
    }

    /**
     * 历史版本曾把 QQ 官方 openid 误存入 qq_bindings.qq_number（与 OneBot 真实 QQ 号共用唯一键）。
     * openid 含字母等非数字字符，与纯数字的真实 QQ 号可区分，据此迁移：
     * 非纯数字行 → qq_official_bindings，并从 qq_bindings 移除。
     */
    private void migrateQqOfficialBindings(Statement stmt) {
        try {
            String nonDigitPredicate = isSqlite ? "GLOB '*[^0-9]*'" : "REGEXP '[^0-9]'";
            String copySql = (isSqlite ? "INSERT OR IGNORE " : "INSERT IGNORE ")
                    + "INTO qq_official_bindings (openid, user_id) "
                    + "SELECT qq_number, user_id FROM qq_bindings WHERE qq_number " + nonDigitPredicate;
            stmt.executeUpdate(copySql);
            stmt.executeUpdate("DELETE FROM qq_bindings WHERE qq_number " + nonDigitPredicate);
            System.out.println("[DB] 已将 qq_bindings 中的 QQ官方 openid 记录迁移至 qq_official_bindings");
        } catch (SQLException e) {
            // 表不存在或无数据时忽略
        }
    }

    /**
     * qq_official_bindings 增加 app_id 维度：唯一键由 (openid)/(user_id) 改为
     * (app_id, openid)/(app_id, user_id)，从而支持同机运行多个 QQ 官方机器人。
     *
     * <p>openid 是按 AppID 隔离的，同一个真人在不同机器人下 openid 不同。老表只按 openid 和
     * user_id 建唯一键，会导致"在 A 机器人绑定、在 B 机器人显示未绑定"，且在 B 上重新绑定
     * 会因 user_id 已被占用而失败。</p>
     *
     * <p>历史行 app_id 置为空字符串，查询时作为兜底（视为"任意机器人"的旧数据），
     * 因此升级后已绑定的玩家不会被登出。</p>
     *
     * <p>SQLite 无法删除表内已有唯一约束，故统一采用"建新表 → 拷数据 → 删旧表 → 改名"；
     * 拷贝完成前不触碰旧表，任一步失败都会保留原表与原有数据。</p>
     */
    private void migrateQqOfficialBindingsAppId(Statement stmt) {
        List<String> columns;
        try {
            columns = tableColumns(stmt, "qq_official_bindings");
        } catch (SQLException e) {
            // 表不存在（全新库由 DatabaseSchema 直接按新结构建表）
            return;
        }
        if (columns.isEmpty()) {
            // 表还不存在，无需迁移
            return;
        }
        if (columns.contains("app_id")) {
            return;
        }

        String newTable = "qq_official_bindings_appid_new";
        boolean fkDisabled = false;
        try {
            stmt.executeUpdate("DROP TABLE IF EXISTS " + newTable);
            stmt.execute(DatabaseSchema.createQqOfficialBindingsTable(newTable,
                    dialect.getPkDefinition(), dialect.getTimestampDefault(), dialect));

            // 拷贝期间关闭外键校验，避免历史脏数据（user 已被删而没级联）导致升级失败
            try {
                dialect.disableForeignKeyChecks(stmt);
                fkDisabled = true;
            } catch (SQLException ignored) {
            }
            int copied = stmt.executeUpdate("INSERT INTO " + newTable
                    + " (app_id, openid, user_id, created_at) "
                    + "SELECT '', openid, user_id, created_at FROM qq_official_bindings");

            stmt.executeUpdate("DROP TABLE qq_official_bindings");
            stmt.execute("ALTER TABLE " + newTable + " RENAME TO qq_official_bindings");
            System.out.println("[DB] qq_official_bindings 已升级为按 app_id 隔离（迁移 " + copied
                    + " 条历史绑定，app_id 置空 = 兼容任意机器人）");
        } catch (SQLException e) {
            System.err.println("[DB] qq_official_bindings 升级失败，已保留原表与数据: " + e.getMessage());
        } finally {
            if (fkDisabled) {
                try {
                    dialect.enableForeignKeyChecks(stmt);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    /** 读取表的所有列名（SQLite 用 PRAGMA，MySQL 用 DESCRIBE，经 dialect 适配）；表不存在时返回空列表或抛异常。 */
    private List<String> tableColumns(Statement stmt, String tableName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(dialect.getTableColumnsQuery(tableName))) {
            while (rs.next()) {
                String name = dialect.getColumnNameFromMetadata(rs);
                if (name != null) {
                    columns.add(name.toLowerCase());
                }
            }
        }
        return columns;
    }

    private void addVerificationCodesSentAtColumn(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE verification_codes ADD COLUMN IF NOT EXISTS sent_at TIMESTAMP DEFAULT NULL");
        } catch (SQLException ignored) {
        }
    }

    private void addPlayersBattleStrategyColumn(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE players ADD COLUMN battle_strategy VARCHAR(16) DEFAULT 'balanced'");
        } catch (SQLException ignored) {
        }
    }

    private void addPlayersLocationColumns(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE players ADD COLUMN current_location_id BIGINT DEFAULT 1");
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE players ADD COLUMN last_travel_time BIGINT DEFAULT 0");
        } catch (SQLException ignored) {
        }
    }

    private void addPlayersBreakthroughTimeColumn(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE players ADD COLUMN last_breakthrough_time BIGINT DEFAULT 0");
        } catch (SQLException ignored) {
        }
    }

    private void addEquipmentEnhanceLevelColumn(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE players_equipment ADD COLUMN enhance_level INT DEFAULT 0");
        } catch (SQLException ignored) {
        }
    }

    private void migrateRedeemCodesStatusColumn(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE redeem_codes ADD COLUMN status VARCHAR(16) DEFAULT 'active'");
        } catch (SQLException ignored) {
        }
        try {
            if (!isSqlite) {
                stmt.execute("UPDATE redeem_codes SET status = CASE WHEN is_active THEN 'active' ELSE 'disabled' END WHERE status = 'active'");
                stmt.execute("ALTER TABLE redeem_codes DROP COLUMN is_active");
            }
        } catch (SQLException ignored) {
        }
    }

    private void migratePlayersItemsQuantityType(Statement stmt) {
        try {
            if (!isSqlite) {
                stmt.execute("ALTER TABLE players_items MODIFY COLUMN quantity BIGINT NOT NULL DEFAULT 1");
            }
        } catch (SQLException ignored) {
        }
    }

    private void addRecipesQualityColumns(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE recipes ADD COLUMN high_quality_rate DOUBLE DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE recipes ADD COLUMN max_quality_rate DOUBLE DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE recipes ADD COLUMN quality_bonus_rate DOUBLE DEFAULT 0");
        } catch (SQLException ignored) {
        }
    }

    private void createPlayerBuffsTable(Statement stmt) {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS player_buffs (" +
                    "id " + dialect.getPkDefinition() + ", " +
                    "player_id BIGINT NOT NULL, " +
                    "buff_id VARCHAR(64) NOT NULL, " +
                    "attack_bonus INT DEFAULT 0, " +
                    "defense_bonus INT DEFAULT 0, " +
                    "speed_bonus INT DEFAULT 0, " +
                    "spirit_bonus INT DEFAULT 0, " +
                    "expire_time BIGINT NOT NULL, " +
                    "created_at " + dialect.getTimestampDefault() +
                    (dialect.supportsForeignKeysInCreateTable()
                            ? ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                            : "") +
                    ")";
            stmt.execute(sql);
        } catch (SQLException ignored) {
        }
    }

    private void createFarmPlotsTable(Statement stmt) {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS farm_plots (" +
                    "id " + dialect.getPkDefinition() + ", " +
                    "player_id BIGINT NOT NULL, " +
                    "plot_index INT NOT NULL, " +
                    "state VARCHAR(16) NOT NULL DEFAULT 'EMPTY', " +
                    "seed_key VARCHAR(128), " +
                    "crop_key VARCHAR(128), " +
                    "planted_time BIGINT DEFAULT 0, " +
                    "harvest_time BIGINT DEFAULT 0, " +
                    "wilted_time BIGINT DEFAULT 0, " +
                    "growth_stage INT DEFAULT 0, " +
                    "water_level INT DEFAULT 100, " +
                    "fertilizer_level INT DEFAULT 0, " +
                    "yield INT DEFAULT 0, " +
                    "settled_time BIGINT DEFAULT 0" +
                    (dialect.supportsForeignKeysInCreateTable()
                            ? ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE"
                            : "") +
                    ")";
            stmt.execute(sql);
        } catch (SQLException ignored) {
        }
    }

    private void addFarmPlotsAdditionalColumns(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE farm_plots ADD COLUMN wilted_time BIGINT DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE farm_plots ADD COLUMN root_bonus DOUBLE DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE farm_plots ADD COLUMN season_modifier DOUBLE DEFAULT 1");
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE farm_plots ADD COLUMN pest_state VARCHAR(16) DEFAULT 'CLEAN'");
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE farm_plots ADD COLUMN pest_time BIGINT DEFAULT 0");
        } catch (SQLException ignored) {
        }
        try {
            stmt.execute("ALTER TABLE farm_plots ADD COLUMN crop_quality VARCHAR(16)");
        } catch (SQLException ignored) {
        }
        // 惰性结算时间戳：里程碑改造前的老库存量地块没有这一列，
        // 补列后默认 0，结算时回退为 planted_time 作为起点（见 FarmService.settlePlot）。
        try {
            stmt.execute("ALTER TABLE farm_plots ADD COLUMN settled_time BIGINT DEFAULT 0");
        } catch (SQLException ignored) {
        }
    }

    /** player_daily 表添加 pvp_count 列：每日PVP切磋次数持久化（重启不丢失） */
    private void addPlayerDailyPvpCountColumn(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE player_daily ADD COLUMN pvp_count INT DEFAULT 0");
        } catch (SQLException ignored) {
        }
    }
}
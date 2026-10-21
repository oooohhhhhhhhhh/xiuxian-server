package com.mtxgdn.db;

import com.mtxgdn.util.AppConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 数据库结构迁移。
 *
 * <h2>为什么有 schema_migrations 表</h2>
 * <p>这里的所有迁移都是"可重复执行"的：{@code ALTER TABLE ADD COLUMN} 撞上已存在的列会报错，
 * 历史上靠 {@code catch (SQLException ignored) {}} 吞掉。问题在于这个 catch 把
 * <b>"列已存在"和"磁盘满 / 没权限 / SQL 写错 / 表根本不存在"当成同一件事</b>——
 * 真出故障时服务照常启动，玩家数据却缺列，直到某个查询在运行期炸掉才被发现。</p>
 *
 * <p>现在每一步都登记到 {@code schema_migrations}：跑没跑过、什么时候跑的、成功还是失败、
 * 失败原因是什么，一条 SQL 就能查清楚。真正的错误会以 ERROR 级别打印出来，
 * 需要"宁可起不来也别带着坏结构跑"时把 {@code database.migration.strict} 打开即可中断启动。</p>
 *
 * <h2>为什么不用版本号跳过已执行的迁移</h2>
 * <p>刻意<b>不做</b>"记录过就跳过"。这些迁移的幂等性同时也是<b>自愈能力</b>：
 * 运维手工删掉某列、或者从备份恢复出一张缺列的表，重启就能补回来
 * （{@code LegacySchemaUpgradeTest} 正是靠这个行为做回归的）。
 * 一旦引入跳过逻辑，结构被外部改动后就再也不会被修复，而"少跑几次 ALTER"根本省不下什么。</p>
 */
public class DatabaseMigration {

    /** 迁移执行记录表。 */
    private static final String HISTORY_TABLE = "schema_migrations";

    private final DatabaseDialect dialect;
    private final boolean isSqlite;
    private final boolean strict;

    public DatabaseMigration(DatabaseDialect dialect, boolean isSqlite) {
        this(dialect, isSqlite, AppConfig.getBoolean("database.migration.strict", false));
    }

    public DatabaseMigration(DatabaseDialect dialect, boolean isSqlite, boolean strict) {
        this.dialect = dialect;
        this.isSqlite = isSqlite;
        this.strict = strict;
    }

    public void executeMigrations(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            ensureHistoryTable(stmt);

            int ok = 0;
            int failed = 0;
            for (Step step : buildSteps()) {
                try {
                    step.body.run(stmt);
                    record(stmt, step, "ok", null);
                    ok++;
                } catch (SQLException e) {
                    failed++;
                    record(stmt, step, "failed", e.getMessage());
                    System.err.println("[DB] 迁移失败 " + step.version + "（" + step.name + "）: "
                            + e.getMessage() + (strict ? "" : " —— 已跳过，继续启动"));
                    if (strict) {
                        throw new SQLException("迁移 " + step.version + " 失败（database.migration.strict=true）: "
                                + e.getMessage(), e);
                    }
                }
            }
            if (failed > 0) {
                System.err.println("[DB] 本次共执行 " + ok + " 个迁移，其中 " + failed
                        + " 个失败，详情见 " + HISTORY_TABLE + " 表");
            }
        }
    }

    // ==================== 迁移清单 ====================

    /**
     * 全部迁移，按依赖顺序排列。
     *
     * <p>{@code version} 一经发布就不要改：它是对外承诺的标识，改了会让历史记录对不上。</p>
     */
    private List<Step> buildSteps() {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("001_verification_codes_sent_at", "验证码发送时间列", this::addVerificationCodesSentAtColumn));
        steps.add(new Step("002_players_battle_strategy", "玩家战斗策略列", this::addPlayersBattleStrategyColumn));
        steps.add(new Step("003_players_location", "玩家位置与移动时间列", this::addPlayersLocationColumns));
        steps.add(new Step("004_players_breakthrough_time", "玩家上次突破时间列", this::addPlayersBreakthroughTimeColumn));
        steps.add(new Step("005_equipment_enhance_level", "装备强化等级列", this::addEquipmentEnhanceLevelColumn));
        steps.add(new Step("006_redeem_codes_status", "兑换码状态列", this::migrateRedeemCodesStatusColumn));
        steps.add(new Step("007_players_items_quantity_type", "背包数量列类型修正", this::migratePlayersItemsQuantityType));
        steps.add(new Step("008_recipes_quality", "配方品质列", this::addRecipesQualityColumns));
        steps.add(new Step("009_player_buffs_table", "Buff 表", this::createPlayerBuffsTable));
        steps.add(new Step("010_farm_plots_table", "农田地块表", this::createFarmPlotsTable));
        steps.add(new Step("011_farm_plots_columns", "农田扩展列", this::addFarmPlotsAdditionalColumns));
        steps.add(new Step("012_qq_official_bindings_split", "QQ官方绑定拆表", this::migrateQqOfficialBindings));
        steps.add(new Step("013_qq_official_bindings_app_id", "QQ官方绑定按 app_id 隔离", this::migrateQqOfficialBindingsAppId));
        steps.add(new Step("014_players_title_counters", "称号计数列", this::addPlayersTitleCounterColumns));
        steps.add(new Step("015_player_daily_pvp_count", "每日切磋次数列", this::addPlayerDailyPvpCountColumn));
        steps.add(new Step("016_trade_listings_expires_at", "坊市挂单时限列", this::addTradeListingsExpiresAtColumn));
        steps.add(new Step("017_map_location_bonus", "地界加成列", this::addMapLocationBonusColumns));
        steps.add(new Step("018_map_danger_wilderness", "地界危险度与野外标记列", this::addMapDangerAndWildernessColumns));
        steps.add(new Step("019_players_last_gather", "玩家采集冷却列", this::addPlayersLastGatherColumn));
        steps.add(new Step("020_players_last_active", "玩家最近活跃时间列", this::addPlayersLastActiveColumn));
        steps.add(new Step("021_sects_succession", "宗主继位与竞争期列", this::addSectSuccessionColumns));
        steps.add(new Step("022_sects_treasury", "宗门金库与工资列", this::addSectTreasuryColumns));
        steps.add(new Step("023_players_breakthrough_pity", "突破失败增益与凝实层数", this::addBreakthroughPityColumns));
        steps.add(new Step("024_players_language", "玩家界面语言列", this::addPlayersLanguageColumn));
        return steps;
    }

    /** 一个迁移步骤：版本号 + 人类可读名字 + 实际动作。 */
    private record Step(String version, String name, StepBody body) {
    }

    @FunctionalInterface
    private interface StepBody {
        void run(Statement stmt) throws SQLException;
    }

    // ==================== 迁移记录 ====================

    private void ensureHistoryTable(Statement stmt) {
        try {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE + " ("
                    + "version VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "name VARCHAR(128), "
                    + "status VARCHAR(16) NOT NULL, "
                    + "applied_at BIGINT NOT NULL, "
                    + "detail VARCHAR(255))");
        } catch (SQLException e) {
            // 建不出来不影响迁移本身，只是少了可观测性
            System.err.println("[DB] 创建 " + HISTORY_TABLE + " 失败（不影响迁移执行）: " + e.getMessage());
        }
    }

    private void record(Statement stmt, Step step, String status, String detail) {
        String truncated = detail == null ? null
                : (detail.length() > 250 ? detail.substring(0, 250) : detail);
        // 先删后插：SQLite 老版本没有 ON CONFLICT DO UPDATE 的通用写法，删插最省心
        try {
            try (PreparedStatement del = stmt.getConnection()
                    .prepareStatement("DELETE FROM " + HISTORY_TABLE + " WHERE version = ?")) {
                del.setString(1, step.version);
                del.executeUpdate();
            }
            try (PreparedStatement ins = stmt.getConnection()
                    .prepareStatement("INSERT INTO " + HISTORY_TABLE
                            + " (version, name, status, applied_at, detail) VALUES (?, ?, ?, ?, ?)")) {
                ins.setString(1, step.version);
                ins.setString(2, step.name);
                ins.setString(3, status);
                ins.setLong(4, System.currentTimeMillis());
                ins.setString(5, truncated);
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("[DB] 记录迁移状态失败 " + step.version + ": " + e.getMessage());
        }
    }

    /** 读取迁移历史（供管理后台 / 排障使用），按版本号排序。 */
    public static List<String> readHistory(Connection conn) {
        List<String> out = new ArrayList<>();
        String sql = "SELECT version, name, status, applied_at, detail FROM " + HISTORY_TABLE + " ORDER BY version";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String detail = rs.getString("detail");
                out.add(rs.getString("version") + " | " + rs.getString("name") + " | "
                        + rs.getString("status") + " | " + rs.getLong("applied_at")
                        + (detail != null ? " | " + detail : ""));
            }
        } catch (SQLException e) {
            out.add("读取 " + HISTORY_TABLE + " 失败: " + e.getMessage());
        }
        return out;
    }

    // ==================== 工具方法 ====================

    /**
     * 执行一条"可能因为已存在而失败"的 DDL。
     *
     * <p>只吞掉"列/表/索引已存在"这一类<b>预期内</b>的错误；其余一律抛出，
     * 由 {@link #executeMigrations} 记录并报错。这是本次改造的核心：
     * 以前所有错误都走同一个 {@code catch (SQLException ignored)}，
     * 真正的问题（没权限、表不存在、SQL 写错）连日志都没有。</p>
     */
    private void tryAlter(Statement stmt, String sql) throws SQLException {
        try {
            stmt.execute(sql);
        } catch (SQLException e) {
            if (isAlreadyExists(e)) {
                return;
            }
            throw e;
        }
    }

    /**
     * 这个 SQL 错误是不是"对象已存在"这类预期内的情况。
     *
     * <p>包级可见是为了让测试能直接钉住这条边界（{@code MigrationHistoryTest}）：
     * 一旦有人把它放宽成"所有错误都算已存在"，静默吞错的老问题就会原样回来。</p>
     */
    static boolean isAlreadyExists(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return msg.contains("duplicate column")
                || msg.contains("duplicate field")
                || msg.contains("duplicate key name")
                || msg.contains("duplicate index")
                || msg.contains("already exists");
    }

    // ==================== 具体迁移 ====================

    /**
     * 界面语言列：老库的 players 补 {@code lang}。
     *
     * <p>刻意<b>回填空串而不是 {@code zh_cn}</b>：空串表示"未设置"，读取方会回落到
     * {@code i18n.default-language}。如果这里硬写 zh_cn，将来运维把默认语言改成 en_us 时，
     * 所有老玩家会停留在中文——他们的"未选择"状态被错误地固化了。
     * 另外空串也是 {@code GameLanguage.parse(null)} 的安全输入，不会抛异常。</p>
     */
    private void addPlayersLanguageColumn(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN lang VARCHAR(16) DEFAULT ''");
    }

    /**
     * 突破失败增益（pity）与凝实层数：老库的 players 补 breakthrough_pity / consolidation。
     *
     * <p>两列都带 DEFAULT 0，语义上 0 就是"没失败过、没凝实过"的出厂值，
     * 老玩家升级后直接落到 0 即可，不需要区分"未播种"与"已设为 0"。</p>
     */
    private void addBreakthroughPityColumns(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN breakthrough_pity INT DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN consolidation INT DEFAULT 0");
    }

    /**
     * 宗门金库 / 工资列：老库的 sects 补 treasury / salary_per_member / last_salary_time。
     *
     * <p>三列都带 DEFAULT 0，语义上 0 就是"金库空、没设工资、从未发过"的出厂值。
     * 老宗门升级后不会突然被发一笔钱 —— 必须先有人捐灵石、宗主设定档位才会开始运转。</p>
     */
    private void addSectTreasuryColumns(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE sects ADD COLUMN treasury BIGINT DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE sects ADD COLUMN salary_per_member BIGINT DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE sects ADD COLUMN last_salary_time BIGINT DEFAULT 0");
    }

    /**
     * 宗主缺位继位 / 竞争期列：老库的 sects 补 leader_absent_since / contest_until /
     * contest_challenger_id / contest_requested。
     *
     * <p>全部带 DEFAULT，语义上 0 就是"宗主在任、无竞争期"的出厂值，因此老宗门升级后
     * 直接落到 0 即可，不需要区分"未播种"与"已设为 0"。</p>
     */
    private void addSectSuccessionColumns(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE sects ADD COLUMN leader_absent_since BIGINT DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE sects ADD COLUMN contest_until BIGINT DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE sects ADD COLUMN contest_challenger_id BIGINT DEFAULT NULL");
        tryAlter(stmt, "ALTER TABLE sects ADD COLUMN contest_requested INT DEFAULT 0");
    }

    /**
     * 最近活跃时间列：老库的 players 补 last_active_time。
     *
     * <p>刻意<b>回填为 0 而非当前时间</b>：0 表示"尚未观测到活跃"，SectService 的
     * 弃坑判定会跳过这类玩家。这样升级瞬间不会因为所有老玩家都是 0 而误判成
     * "全服宗主集体弃坑"、把大批宗门一次性换掉宗主 —— 判定从各自下一次登录开始计时。</p>
     */
    private void addPlayersLastActiveColumn(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN last_active_time BIGINT DEFAULT 0");
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
    private void addMapDangerAndWildernessColumns(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE map_locations ADD COLUMN danger_level INT DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE map_locations ADD COLUMN is_wilderness INT DEFAULT 0");
        // 已知世界地点恒为 NULL（永不过期）；野外节点才有值
        tryAlter(stmt, "ALTER TABLE map_locations ADD COLUMN expires_at BIGINT");
        tryAlter(stmt, "ALTER TABLE map_locations ADD COLUMN entry_location_id BIGINT");
    }

    /** 野外采集冷却列：老库的 players 补 last_gather_time。 */
    private void addPlayersLastGatherColumn(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN last_gather_time BIGINT DEFAULT 0");
    }

    /**
     * 地界加成列：老库的 map_locations 补 cultivation_bonus / exploration_bonus。
     *
     * <p>此处刻意<b>不带 DEFAULT</b>：新库由 CREATE TABLE 直接建出带默认值的列，而老库升级后
     * 已有行会得到 NULL —— NULL 即"尚未播种"的标记，由 MapService 的补齐流程按出厂值回填。
     * 这样既不需要额外的迁移版本号，也不会覆盖管理员后来手工调整过的数值（非 NULL 即视为已定稿）。</p>
     */
    private void addMapLocationBonusColumns(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE map_locations ADD COLUMN cultivation_bonus DOUBLE");
        tryAlter(stmt, "ALTER TABLE map_locations ADD COLUMN exploration_bonus DOUBLE");
    }

    /**
     * 坊市挂单时限：为既有库的 trade_listings 补 expires_at 列。
     *
     * <p>旧行保持 NULL（视为永不过期），由 TradeService 在首次运行时按
     * created_at + 配置时长回填，避免升级瞬间把大量老挂单一次性清空。</p>
     */
    private void addTradeListingsExpiresAtColumn(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE trade_listings ADD COLUMN expires_at TIMESTAMP DEFAULT NULL");
    }

    /** 称号玩法计数列：秘境探索次数 / PVP 胜场 / 炼丹成功次数 */
    private void addPlayersTitleCounterColumns(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN secret_realm_count INT DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN pvp_wins INT DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN craft_count INT DEFAULT 0");
    }

    /**
     * 历史版本曾把 QQ 官方 openid 误存入 qq_bindings.qq_number（与 OneBot 真实 QQ 号共用唯一键）。
     * openid 含字母等非数字字符，与纯数字的真实 QQ 号可区分，据此迁移：
     * 非纯数字行 → qq_official_bindings，并从 qq_bindings 移除。
     */
    private void migrateQqOfficialBindings(Statement stmt) throws SQLException {
        String nonDigitPredicate = isSqlite ? "GLOB '*[^0-9]*'" : "REGEXP '[^0-9]'";
        String copySql = (isSqlite ? "INSERT OR IGNORE " : "INSERT IGNORE ")
                + "INTO qq_official_bindings (openid, user_id) "
                + "SELECT qq_number, user_id FROM qq_bindings WHERE qq_number " + nonDigitPredicate;
        int copied = stmt.executeUpdate(copySql);
        int removed = stmt.executeUpdate("DELETE FROM qq_bindings WHERE qq_number " + nonDigitPredicate);
        if (copied > 0 || removed > 0) {
            System.out.println("[DB] 已将 qq_bindings 中的 QQ官方 openid 记录迁移至 qq_official_bindings");
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
    private void migrateQqOfficialBindingsAppId(Statement stmt) throws SQLException {
        List<String> columns = tableColumns(stmt, "qq_official_bindings");
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
                // 部分驱动不支持在事务内关闭外键校验，关不掉就继续拷
            }
            int copied = stmt.executeUpdate("INSERT INTO " + newTable
                    + " (app_id, openid, user_id, created_at) "
                    + "SELECT '', openid, user_id, created_at FROM qq_official_bindings");

            stmt.executeUpdate("DROP TABLE qq_official_bindings");
            stmt.execute("ALTER TABLE " + newTable + " RENAME TO qq_official_bindings");
            System.out.println("[DB] qq_official_bindings 已升级为按 app_id 隔离（迁移 " + copied
                    + " 条历史绑定，app_id 置空 = 兼容任意机器人）");
        } finally {
            if (fkDisabled) {
                try {
                    dialect.enableForeignKeyChecks(stmt);
                } catch (SQLException ignored) {
                    // 恢复失败也无可奈何；连接关闭后约束自然回到默认状态
                }
            }
        }
    }

    /** 读取表的所有列名（SQLite 用 PRAGMA，MySQL 用 DESCRIBE，经 dialect 适配）；表不存在时返回空列表。 */
    private List<String> tableColumns(Statement stmt, String tableName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery(dialect.getTableColumnsQuery(tableName))) {
            while (rs.next()) {
                String name = dialect.getColumnNameFromMetadata(rs);
                if (name != null) {
                    columns.add(name.toLowerCase(Locale.ROOT));
                }
            }
        } catch (SQLException e) {
            // 表不存在：PRAGMA/DESCRIBE 会直接报错，此时视为"无需迁移"
            return columns;
        }
        return columns;
    }

    /**
     * 验证码发送时间列。
     *
     * <p>原实现写的是 {@code ADD COLUMN IF NOT EXISTS}，而 <b>SQLite 不支持这个语法</b>，
     * 于是这条迁移在 SQLite 上从来没有成功过，错误又被 {@code catch (SQLException ignored)}
     * 吞掉——新库靠 CREATE TABLE 有这一列，老库则一直缺。这里去掉 {@code IF NOT EXISTS}，
     * 交给 {@link #tryAlter} 处理"列已存在"。</p>
     */
    private void addVerificationCodesSentAtColumn(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE verification_codes ADD COLUMN sent_at TIMESTAMP DEFAULT NULL");
    }

    private void addPlayersBattleStrategyColumn(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN battle_strategy VARCHAR(16) DEFAULT 'balanced'");
    }

    private void addPlayersLocationColumns(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN current_location_id BIGINT DEFAULT 1");
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN last_travel_time BIGINT DEFAULT 0");
    }

    private void addPlayersBreakthroughTimeColumn(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE players ADD COLUMN last_breakthrough_time BIGINT DEFAULT 0");
    }

    private void addEquipmentEnhanceLevelColumn(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE players_equipment ADD COLUMN enhance_level INT DEFAULT 0");
    }

    /**
     * 兑换码状态列：把布尔 {@code is_active} 迁移成 {@code status} 字符串。
     *
     * <p>先确认 {@code is_active} 真的还在再动它：MySQL 上重复执行
     * {@code DROP COLUMN is_active} 会直接报错，旧实现把这条错误也一并吞了，
     * 导致"列已经删掉"和"删除失败"看起来完全一样。</p>
     */
    private void migrateRedeemCodesStatusColumn(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE redeem_codes ADD COLUMN status VARCHAR(16) DEFAULT 'active'");
        if (isSqlite) {
            // SQLite 侧保留 is_active（DROP COLUMN 在旧版本不可用），由写入方同时维护
            return;
        }
        List<String> columns = tableColumns(stmt, "redeem_codes");
        if (!columns.contains("is_active")) {
            return;
        }
        stmt.execute("UPDATE redeem_codes SET status = CASE WHEN is_active THEN 'active' ELSE 'disabled' END"
                + " WHERE status = 'active'");
        tryAlter(stmt, "ALTER TABLE redeem_codes DROP COLUMN is_active");
    }

    private void migratePlayersItemsQuantityType(Statement stmt) throws SQLException {
        if (isSqlite) {
            // SQLite 的列类型是动态的，INTEGER 与 BIGINT 没有区别
            return;
        }
        stmt.execute("ALTER TABLE players_items MODIFY COLUMN quantity BIGINT NOT NULL DEFAULT 1");
    }

    private void addRecipesQualityColumns(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE recipes ADD COLUMN high_quality_rate DOUBLE DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE recipes ADD COLUMN max_quality_rate DOUBLE DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE recipes ADD COLUMN quality_bonus_rate DOUBLE DEFAULT 0");
    }

    private void createPlayerBuffsTable(Statement stmt) throws SQLException {
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
    }

    private void createFarmPlotsTable(Statement stmt) throws SQLException {
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
    }

    private void addFarmPlotsAdditionalColumns(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE farm_plots ADD COLUMN wilted_time BIGINT DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE farm_plots ADD COLUMN root_bonus DOUBLE DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE farm_plots ADD COLUMN season_modifier DOUBLE DEFAULT 1");
        tryAlter(stmt, "ALTER TABLE farm_plots ADD COLUMN pest_state VARCHAR(16) DEFAULT 'CLEAN'");
        tryAlter(stmt, "ALTER TABLE farm_plots ADD COLUMN pest_time BIGINT DEFAULT 0");
        tryAlter(stmt, "ALTER TABLE farm_plots ADD COLUMN crop_quality VARCHAR(16)");
        // 惰性结算时间戳：里程碑改造前的老库存量地块没有这一列，
        // 补列后默认 0，结算时回退为 planted_time 作为起点（见 FarmService.settlePlot）。
        tryAlter(stmt, "ALTER TABLE farm_plots ADD COLUMN settled_time BIGINT DEFAULT 0");
    }

    /** player_daily 表添加 pvp_count 列：每日PVP切磋次数持久化（重启不丢失） */
    private void addPlayerDailyPvpCountColumn(Statement stmt) throws SQLException {
        tryAlter(stmt, "ALTER TABLE player_daily ADD COLUMN pvp_count INT DEFAULT 0");
    }
}

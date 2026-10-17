package com.mtxgdn.db;

import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DatabaseManager {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);

    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String SQLITE_DRIVER = "org.sqlite.JDBC";

    private static String dbUrl;
    private static String dbUser;
    private static String dbPassword;
    private static boolean IS_SQLITE = false;
    private static DatabaseDialect DIALECT;
    /** HikariCP 连接池（替代每次裸建连接，显著降低连接开销） */
    private static HikariDataSource dataSource;

    public static void init(String url, String user, String password) {
        dbUrl = url;
        dbUser = user;
        dbPassword = password;
        IS_SQLITE = url.startsWith("jdbc:sqlite:");
        DIALECT = IS_SQLITE ? new SQLiteDialect() : new MySQLDialect();
        try {
            Class.forName(IS_SQLITE ? SQLITE_DRIVER : MYSQL_DRIVER);
            // 初始化连接池
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(url);
            cfg.setUsername(user);
            cfg.setPassword(password);
            cfg.setPoolName("xiuxian-db");
            cfg.setMaximumPoolSize(Math.max(4, AppConfig.getInt("database.pool_max", 4)));
            cfg.setMinimumIdle(1);
            cfg.setConnectionTimeout(10_000);
            cfg.setValidationTimeout(3_000);
            cfg.setIdleTimeout(300_000);
            cfg.setMaxLifetime(1_800_000);
            if (IS_SQLITE) {
                // SQLite 单写者模型：池内保持少量连接即可，避免写锁争用
                cfg.setMaximumPoolSize(Math.min(8, Math.max(2, AppConfig.getInt("database.pool_max", 4))));
            }
            dataSource = new HikariDataSource(cfg);
            getConnection();
            initTable();
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("数据库驱动加载失败", e);
        }
    }

    public static Connection getConnection() throws SQLException {
        if (dataSource != null) {
            Connection conn = dataSource.getConnection();
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            DIALECT.initConnection(conn);
            return conn;
        }
        // 未初始化时回退裸连接（容错）
        Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        DIALECT.initConnection(conn);
        return conn;
    }

    public static boolean isSqlite() {
        return IS_SQLITE;
    }

    public static DatabaseDialect getDialect() {
        return DIALECT;
    }

    public static void executeInTransaction(SQLRunnable runnable) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                runnable.run(conn);
                conn.commit();
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw new RuntimeException("事务执行失败", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("事务执行失败", e);
        }
    }

    public static <T> T runTransaction(SQLFunction<T> function) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = function.run(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw new RuntimeException("事务执行失败", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("事务执行失败", e);
        }
    }

    public static void initTable() {
        String pk = DIALECT.getPkDefinition();
        String tsDefault = DIALECT.getTimestampDefault();
        String tsUpdate = DIALECT.getTimestampUpdate();
        String boolType = DIALECT.getBooleanType();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(DatabaseSchema.createUsersTable(pk, tsDefault));
            stmt.execute(DatabaseSchema.createVerificationCodesTable(pk, tsDefault));
            stmt.execute(DatabaseSchema.createPlayersTable(pk, tsDefault, tsUpdate, boolType, DIALECT));
            stmt.execute(DatabaseSchema.createPlayersItemsTable(pk, tsDefault, tsUpdate, DIALECT));
            stmt.execute(DatabaseSchema.createPlayersEquipmentTable(pk, tsDefault, tsUpdate, DIALECT));
            stmt.execute(DatabaseSchema.createSkillsTable(pk, tsDefault));
            stmt.execute(DatabaseSchema.createPlayersSkillsTable(pk, tsDefault, tsUpdate, DIALECT));
            stmt.execute(DatabaseSchema.createTechniquesTable(pk, tsDefault));
            stmt.execute(DatabaseSchema.createPlayersTechniquesTable(pk, tsDefault, tsUpdate, boolType, DIALECT));
            stmt.execute(DatabaseSchema.createRecipesTable(pk, tsDefault));
            stmt.execute(DatabaseSchema.createTradeListingsTable(pk, tsDefault, tsUpdate, DIALECT));
            stmt.execute(DatabaseSchema.createPlayerDailyTable(pk, tsDefault, DIALECT));
            stmt.execute(DatabaseSchema.createQqBindingsTable(pk, tsDefault, DIALECT));
            stmt.execute(DatabaseSchema.createQqOfficialBindingsTable(pk, tsDefault, DIALECT));
            stmt.execute(DatabaseSchema.createMcBindingsTable(pk, tsDefault, DIALECT));
            stmt.execute(DatabaseSchema.createRolesTable(pk, tsDefault));
            stmt.execute(DatabaseSchema.createPermissionsTable(pk, tsDefault));
            stmt.execute(DatabaseSchema.createRolePermissionsTable(pk, tsDefault));
            stmt.execute(DatabaseSchema.createUserRolesTable(pk, tsDefault, DIALECT));
            stmt.execute(DatabaseSchema.createChatMessagesTable(pk, tsDefault, DIALECT));
            stmt.execute(DatabaseSchema.createFriendsTable(pk, tsDefault, tsUpdate, DIALECT));
            stmt.execute(DatabaseSchema.createPlayerActionLogsTable(pk, tsDefault));
            stmt.execute(DatabaseSchema.createSectsTable(pk, tsDefault, tsUpdate, DIALECT));
            stmt.execute(DatabaseSchema.createSectMembersTable(pk, tsDefault, DIALECT));
            stmt.execute(DatabaseSchema.createSectApplicationsTable(pk, tsDefault, tsUpdate, DIALECT));
            stmt.execute(DatabaseSchema.createSectWarehouseTable(pk, tsDefault, tsUpdate, DIALECT));
            stmt.execute(DatabaseSchema.createSectWarsTable(pk, tsDefault, DIALECT));
            stmt.execute(DatabaseSchema.createMapLocationsTable(pk, tsDefault, boolType));
            stmt.execute(DatabaseSchema.createMapConnectionsTable(pk, tsDefault, DIALECT));
            stmt.execute(DatabaseSchema.createMapLocationEventBiasTable(pk, DIALECT));
            stmt.execute(DatabaseSchema.createPlayerMapExplorationTable(pk, tsDefault, DIALECT));
            // 野外资源表已废弃：野外改存分片文件，新库不再建这张表（老库留着的空表无害）
            stmt.execute(DatabaseSchema.createRedeemCodesTable(pk, tsDefault, tsUpdate));
            stmt.execute(DatabaseSchema.createRedeemedCodesTable(pk, tsDefault, DIALECT));
            stmt.execute(DatabaseSchema.createCavesTable(pk, tsDefault, tsUpdate, DIALECT));
            stmt.execute(DatabaseSchema.createFormationsTable(pk, tsDefault, tsUpdate, DIALECT));
            stmt.execute(DatabaseSchema.createPlayerEconomyTable(pk, DIALECT));
            stmt.execute(DatabaseSchema.createPlayerBankTable(pk, tsDefault, tsUpdate));
            stmt.execute(DatabaseSchema.createAuctionListingsTable(pk, tsDefault, tsUpdate));
            stmt.execute(DatabaseSchema.createAuctionBidsTable(pk, tsDefault));
            stmt.execute(DatabaseSchema.createPlayerEnergyTable(pk, DIALECT));
            stmt.execute(DatabaseSchema.createPlayerTitlesTable(pk, tsDefault, boolType, DIALECT));
            // V4 修复：JWT 吊销名单持久化表（登出后重启不复活）
            stmt.execute(DatabaseSchema.createTokenBlacklistTable(pk, tsDefault));
            // 坊市挂单时限 + 成交价格历史
            stmt.execute(DatabaseSchema.createTradePriceHistoryTable(pk, tsDefault));
            // 世界 Boss（B6）：共享血量 + 伤害台账
            stmt.execute(DatabaseSchema.createWorldBossesTable(pk, tsDefault, tsUpdate, DIALECT));
            stmt.execute(DatabaseSchema.createWorldBossDamageTable(pk, tsDefault, DIALECT));

            new DatabaseMigration(DIALECT, IS_SQLITE).executeMigrations(conn);

            // 索引必须在迁移之后创建。
            //
            // 老库的 trade_listings 没有 expires_at 列，该列是由 executeMigrations 里的
            // ALTER TABLE 补上的；索引却曾经先于迁移执行，于是任何"升级上来的库"都会在
            // 启动时直接抛 no such column: expires_at —— 表建好了、服务却起不来。
            // 建表用 CREATE TABLE IF NOT EXISTS，对老库是空操作，真正补列的是迁移，
            // 所以"先迁移、后建索引"才是唯一正确的顺序。
            // 回归测试：LegacySchemaUpgradeTest
            createIndexQuietly(stmt, DatabaseSchema.createTradePriceHistoryIndex());
            createIndexQuietly(stmt, DatabaseSchema.createTradeListingsStatusExpiresIndex());
            createIndexQuietly(stmt, DatabaseSchema.createTradeListingsSellerStatusIndex());
            createIndexQuietly(stmt, DatabaseSchema.createPlayerBuffsExpireIndex());
            createIndexQuietly(stmt, DatabaseSchema.createWorldBossesScopeIndex());

            logger.info("所有数据库表创建完成");
        } catch (SQLException e) {
            throw new RuntimeException("创建数据库表失败", e);
        }

        PermissionService.initDefaultData();
    }

    /**
     * 创建索引，失败只告警、不中断启动。
     *
     * <p>索引只是性能优化，不该成为"服务器起不来"的原因。万一将来某个索引引用了
     * 迁移尚未补上的列，宁可少一个索引（查询慢一点）也不能让服务完全不可用。</p>
     */
    private static void createIndexQuietly(Statement stmt, String sql) {
        try {
            stmt.execute(sql);
        } catch (SQLException e) {
            logger.warn("创建索引失败（不影响启动）: {} -> {}", sql, e.getMessage());
        }
    }

    public static Map<String, Integer> clearPlayerData() {
        Map<String, Integer> result = new LinkedHashMap<>();
        String[] tables = {
                "players_skills", "players_techniques", "players_items", "players_equipment",
                "player_daily", "trade_listings", "chat_messages", "friends", "player_action_logs",
                "sect_members", "sect_applications", "sect_warehouse", "sects", "player_energy",
                "players", "player_buffs", "farm_plots", "player_economy", "player_bank",
                "auction_listings", "auction_bids", "player_titles", "caves", "formations",
                "redeemed_codes", "sect_wars"
        };

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            DIALECT.disableForeignKeyChecks(stmt);
            for (String table : tables) {
                int count = stmt.executeUpdate("DELETE FROM " + table);
                result.put(table, count);
            }
            DIALECT.enableForeignKeyChecks(stmt);
        } catch (SQLException e) {
            throw new RuntimeException("清除玩家数据失败", e);
        }

        return result;
    }

    public static Map<String, Integer> resetAllData() {
        Map<String, Integer> result = new LinkedHashMap<>();
        String[] tables = {
                "players_skills", "players_techniques", "players_items", "players_equipment",
                "player_daily", "trade_listings", "chat_messages", "friends", "player_action_logs",
                "sect_members", "sect_applications", "sect_warehouse", "sects", "player_energy",
                "players", "qq_bindings", "qq_official_bindings", "mc_bindings", "user_roles", "role_permissions",
                "roles", "permissions", "verification_codes", "player_buffs", "farm_plots",
                "player_economy", "player_bank", "auction_listings", "auction_bids",
                "player_titles", "caves", "formations", "redeemed_codes", "sect_wars",
                "redeem_codes"
        };

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            DIALECT.disableForeignKeyChecks(stmt);
            for (String table : tables) {
                int count = stmt.executeUpdate("DELETE FROM " + table);
                result.put(table, count);
            }
            DIALECT.enableForeignKeyChecks(stmt);
        } catch (SQLException e) {
            throw new RuntimeException("重置数据库失败", e);
        }

        PermissionService.initDefaultData();
        return result;
    }

    public static List<String> getAllTableNames() {
        List<String> names = new ArrayList<>();
        if (IS_SQLITE) {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
            } catch (SQLException e) {
                logger.error("获取SQLite表名失败", e);
            }
        } else {
            try (Connection conn = getConnection();
                 ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    names.add(rs.getString("TABLE_NAME"));
                }
            } catch (SQLException e) {
                logger.error("获取MySQL表名失败", e);
            }
        }
        return names;
    }

    /** 表名白名单：硬编码所有合法表名（安全加固） */
    private static final java.util.Set<String> ALLOWED_TABLES = java.util.Set.of(
        "users", "verification_codes", "players", "players_items", "players_equipment",
        "skills", "players_skills", "techniques", "players_techniques", "recipes",
        "trade_listings", "player_daily", "qq_bindings", "qq_official_bindings",
        "mc_bindings", "roles", "permissions", "role_permissions", "user_roles",
        "chat_messages", "friends", "player_action_logs", "sects", "sect_members",
        "sect_applications", "sect_warehouse", "sect_wars", "map_locations",
        "map_connections", "map_location_event_bias", "player_map_exploration",
        "map_wilderness_resources", "redeem_codes", "redeemed_codes", "caves", "formations",
        "player_economy", "player_bank", "auction_listings", "auction_bids",
        "player_energy", "player_titles", "player_buffs", "farm_plots"
    );

    /** 表名白名单缓存（避免每次校验都查库） */
    private static volatile Set<String> tableNameCache;
    private static volatile long tableNameCacheTime;

    /** 表名白名单校验：仅允许当前数据库中真实存在的表（防 SQL 标识符注入）。 */
    private static void assertSafeTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("非法表名: " + tableName);
        }
        
        // 先检查格式：只允许小写字母和下划线
        if (!tableName.matches("^[a-z_]+$")) {
            throw new IllegalArgumentException("非法表名格式: " + tableName);
        }
        
        // 再检查硬编码白名单
        if (!ALLOWED_TABLES.contains(tableName)) {
            throw new IllegalArgumentException("表名不在白名单中: " + tableName);
        }
        
        // 最后检查缓存（确保表真实存在）
        long now = System.currentTimeMillis();
        Set<String> cache = tableNameCache;
        if (cache == null || now - tableNameCacheTime > 30_000L) { // 缓存时间改为30秒
            cache = new java.util.HashSet<>(getAllTableNames());
            tableNameCache = cache;
            tableNameCacheTime = now;
        }
        if (!cache.contains(tableName)) {
            throw new IllegalArgumentException("表不存在: " + tableName);
        }
    }

    /** 列名校验：仅允许纯标识符字符（防 SQL 标识符注入）。 */
    private static void assertSafeColumnName(String columnName) {
        if (columnName == null || !columnName.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new IllegalArgumentException("非法列名: " + columnName);
        }
    }

    public static List<Map<String, Object>> queryTable(String tableName, int limit, int offset) {
        List<Map<String, Object>> result = new ArrayList<>();
        assertSafeTableName(tableName);
        String sql = "SELECT * FROM " + tableName + " LIMIT ? OFFSET ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                int columnCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        String name = rs.getMetaData().getColumnName(i);
                        Object value = rs.getObject(i);
                        row.put(name, value);
                    }
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            logger.error("查询表数据失败: {}", tableName, e);
            throw new RuntimeException("查询表数据失败: " + tableName, e);
        }
        return result;
    }

    public static int countTable(String tableName) {
        assertSafeTableName(tableName);
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.error("统计表记录失败: {}", tableName, e);
            throw new RuntimeException("统计表记录失败: " + tableName, e);
        }
        return 0;
    }

    public static void shutdown() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            logger.info("数据库连接池已关闭");
        }
    }

    public static List<Map<String, Object>> queryPlayerActionLogs(Long userId, String playerName, String action,
                                                                   String qqNumber, String startTime, String endTime,
                                                                   int limit, int offset) {
        return new PlayerActionLogDAO().queryPlayerActionLogs(userId, playerName, action, qqNumber, startTime, endTime, limit, offset);
    }

    public static int countPlayerActionLogs(Long userId, String playerName, String action,
                                             String qqNumber, String startTime, String endTime) {
        return new PlayerActionLogDAO().countPlayerActionLogs(userId, playerName, action, qqNumber, startTime, endTime);
    }

    public static int countTableRows(String tableName) {
        return countTable(tableName);
    }

    public static List<Map<String, Object>> getTableColumns(String tableName) {
        List<Map<String, Object>> columns = new ArrayList<>();
        try (Connection conn = getConnection();
             ResultSet rs = conn.getMetaData().getColumns(null, null, tableName.toUpperCase(), "%")) {
            while (rs.next()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", rs.getString("COLUMN_NAME"));
                col.put("type", rs.getString("TYPE_NAME"));
                col.put("size", rs.getInt("COLUMN_SIZE"));
                col.put("notnull", rs.getInt("NULLABLE") == 0);
                col.put("pk", false);
                columns.add(col);
            }
        } catch (SQLException e) {
            logger.error("获取表列信息失败: {}", tableName, e);
        }

        // 补全主键标记：通过 JDBC 主键元数据识别（SQLite/MySQL 通用）
        try (Connection conn = getConnection();
             ResultSet pkRs = conn.getMetaData().getPrimaryKeys(null, null, tableName)) {
            java.util.Set<String> pkColumns = new java.util.HashSet<>();
            while (pkRs.next()) {
                pkColumns.add(pkRs.getString("COLUMN_NAME"));
            }
            for (Map<String, Object> col : columns) {
                if (pkColumns.contains(String.valueOf(col.get("name")))) {
                    col.put("pk", true);
                }
            }
        } catch (SQLException e) {
            logger.error("获取表主键信息失败: {}", tableName, e);
        }
        return columns;
    }

    public static List<Map<String, Object>> queryTableData(String tableName, int limit, int offset) {
        return queryTable(tableName, limit, offset);
    }

    public static Map<String, Object> getRowById(String tableName, long id) {
        assertSafeTableName(tableName);
        String sql = "SELECT * FROM " + tableName + " WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    int columnCount = rs.getMetaData().getColumnCount();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                    }
                    return row;
                }
            }
        } catch (SQLException e) {
            logger.error("查询行失败: {}", tableName, e);
        }
        return null;
    }

    public static long insertRow(String tableName, Map<String, Object> data) {
        assertSafeTableName(tableName);
        StringBuilder sql = new StringBuilder("INSERT INTO " + tableName + " (");
        StringBuilder values = new StringBuilder("VALUES (");
        List<Object> params = new ArrayList<>();

        boolean first = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            assertSafeColumnName(entry.getKey());
            if (!first) {
                sql.append(", ");
                values.append(", ");
            }
            sql.append(entry.getKey());
            values.append("?");
            params.add(entry.getValue());
            first = false;
        }
        sql.append(") ").append(values).append(")");

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString(), Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
            return 0;
        } catch (SQLException e) {
            logger.error("插入行失败: {}", tableName, e);
            throw new RuntimeException("插入行失败: " + tableName, e);
        }
    }

    public static int updateRow(String tableName, long id, Map<String, Object> data) {
        assertSafeTableName(tableName);
        StringBuilder sql = new StringBuilder("UPDATE " + tableName + " SET ");
        List<Object> params = new ArrayList<>();

        boolean first = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            assertSafeColumnName(entry.getKey());
            if (!first) sql.append(", ");
            sql.append(entry.getKey()).append(" = ?");
            params.add(entry.getValue());
            first = false;
        }
        sql.append(" WHERE id = ?");
        params.add(id);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("更新行失败: {}", tableName, e);
            throw new RuntimeException("更新行失败: " + tableName, e);
        }
    }

    public static int deleteRow(String tableName, long id) {
        assertSafeTableName(tableName);
        String sql = "DELETE FROM " + tableName + " WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("删除行失败: {}", tableName, e);
            throw new RuntimeException("删除行失败: " + tableName, e);
        }
    }

    public static String exportAllData() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        List<String> tables = getAllTableNames();
        for (String table : tables) {
            result.put(table, queryTable(table, Integer.MAX_VALUE, 0));
        }
        try {
            return new com.google.gson.Gson().toJson(result);
        } catch (Exception e) {
            logger.error("导出数据失败", e);
            throw new RuntimeException("导出数据失败", e);
        }
    }

    /**
     * 整库数据还原：解析 exportAllData 的 JSON 格式（{表名: [行...]}），
     * 逐表清空后按显式列插入（含 id）。全程单事务，失败自动回滚；表名/列名均做白名单校验。
     */
    public static Map<String, Integer> importData(String jsonData) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (jsonData == null || jsonData.isBlank()) {
            return result;
        }
        com.google.gson.JsonObject root;
        try {
            root = com.google.gson.JsonParser.parseString(jsonData).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("导入数据格式错误: " + e.getMessage(), e);
        }

        try {
            DatabaseManager.runTransaction(conn -> {
                try (Statement st = conn.createStatement()) {
                    DIALECT.disableForeignKeyChecks(st);
                }
                for (String table : root.keySet()) {
                    assertSafeTableName(table);
                    try (Statement st = conn.createStatement()) {
                        st.executeUpdate("DELETE FROM " + table);
                    }
                    com.google.gson.JsonArray rows = root.getAsJsonArray(table);
                    int inserted = 0;
                    if (rows != null) {
                        for (com.google.gson.JsonElement el : rows) {
                            if (!el.isJsonObject()) continue;
                            com.google.gson.JsonObject row = el.getAsJsonObject();
                            StringBuilder cols = new StringBuilder();
                            StringBuilder vals = new StringBuilder();
                            List<Object> params = new ArrayList<>();
                            for (String col : row.keySet()) {
                                assertSafeColumnName(col);
                                if (cols.length() > 0) cols.append(", ");
                                if (vals.length() > 0) vals.append(", ");
                                cols.append(col);
                                vals.append("?");
                                params.add(jsonToSqlValue(row.get(col)));
                            }
                            if (cols.length() == 0) continue;
                            String sql = "INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ")";
                            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                                for (int i = 0; i < params.size(); i++) {
                                    ps.setObject(i + 1, params.get(i));
                                }
                                ps.executeUpdate();
                                inserted++;
                            }
                        }
                    }
                    result.put(table, inserted);
                }
                try (Statement st = conn.createStatement()) {
                    DIALECT.enableForeignKeyChecks(st);
                }
                return null;
            });
        } catch (Exception e) {
            logger.error("导入数据失败（已回滚）", e);
            throw new RuntimeException("导入数据失败（已回滚）: " + e.getMessage(), e);
        }
        return result;
    }

    private static Object jsonToSqlValue(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsNumber();
            return p.getAsString();
        }
        return el.toString();
    }

    @FunctionalInterface
    public interface SQLRunnable {
        void run(Connection conn) throws Exception;
    }

    @FunctionalInterface
    public interface SQLFunction<T> {
        T run(Connection conn) throws Exception;
    }
}
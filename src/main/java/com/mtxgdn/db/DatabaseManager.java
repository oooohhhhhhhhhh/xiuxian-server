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

    public static synchronized void init(String url, String user, String password) {
        dbUrl = url;
        dbUser = user;
        dbPassword = password;
        IS_SQLITE = url.startsWith("jdbc:sqlite:");
        DIALECT = IS_SQLITE ? new SQLiteDialect() : new MySQLDialect();
        try {
            Class.forName(IS_SQLITE ? SQLITE_DRIVER : MYSQL_DRIVER);
            // 重复初始化（测试里每个测试类都会调一次）必须先关掉旧池，
            // 否则旧池连同它借出的连接一起泄漏，进程里会堆出几十个池。
            if (dataSource != null) {
                dataSource.close();
                dataSource = null;
            }
            // 初始化连接池
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(url);
            cfg.setUsername(user);
            cfg.setPassword(password);
            cfg.setPoolName("xiuxian-db");
            cfg.setMaximumPoolSize(Math.max(8, AppConfig.getInt("database.pool_max", 8)));
            cfg.setMinimumIdle(1);
            cfg.setConnectionTimeout(10_000);
            cfg.setValidationTimeout(3_000);
            cfg.setIdleTimeout(300_000);
            cfg.setMaxLifetime(1_800_000);
            if (IS_SQLITE) {
                // SQLite 单写者模型：连接数不必很多，但**不能少于并发业务线程数**。
                // 池子借空后，后来者会卡满 connectionTimeout（10 秒）才抛
                // "Connection is not available"，堆栈指向一条毫不相干的业务查询，
                // 现场极难定位。指令线程池默认 4 个，再加 Grizzly worker、WS 推送、
                // 定时任务，4 个连接是不够的，所以下限给到 8。
                cfg.setMaximumPoolSize(Math.min(16, Math.max(8, AppConfig.getInt("database.pool_max", 8))));
            }
            dataSource = new HikariDataSource(cfg);
            // 预热连接池：让驱动加载与方言初始化在启动阶段就完成，
            // 而不是把第一个真实请求当成试验品。
            //
            // **必须关闭**：这条连接以前是"借了不还"，会永久占掉池里的一个名额。
            // SQLite 下池子只有 4 个连接，少一个就是少 25%，
            // 表现是高峰期莫名其妙的
            // "Connection is not available, request timed out after 10000ms"。
            try (Connection warmup = getConnection()) {
                // 拿到就够，无需做任何事
                if (warmup == null) {
                    throw new SQLException("连接池预热失败");
                }
            }
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

    /**
     * 事务遇到"可重试的并发冲突"时的最大尝试次数。
     *
     * <p>为什么需要重试：SQLite 在 WAL 模式下，一个 {@code BEGIN DEFERRED} 事务
     * （{@code setAutoCommit(false)} 发出的就是这个）如果先读后写，而期间别的连接
     * 已经提交了写，SQLite 会直接抛 {@code SQLITE_BUSY_SNAPSHOT} —— 这个错误
     * <b>不受 {@code busy_timeout} 约束</b>（重试会破坏快照隔离，所以它选择立刻报错）。
     * MySQL 的 InnoDB 死锁（SQLState 40001）同理。</p>
     *
     * <p>而本进程里有大量后台调度线程在写同一批表（自动回血每 10 秒 UPDATE players、
     * 玩家快照每 30 秒回写、拍卖/坊市/宗门定时清扫），所以"前台事务读后写"与
     * "后台任务写"撞车是常态而不是意外。不重试的表现就是随机出现
     * 「事务执行失败: [SQLITE_BUSY_SNAPSHOT]」，用户侧看到的是"使用物品失败"
     * 这类毫无规律、无法复现的报错。</p>
     *
     * <p>事务已回滚，重跑一次是安全的；次数刻意压得很少（4 次，退避 20/40/80ms），
     * 避免真正的高争用时把线程长时间占住。</p>
     */
    private static final int TX_MAX_ATTEMPTS = 4;

    private static final long TX_RETRY_BASE_DELAY_MS = 20L;

    public static <T> T runTransaction(SQLFunction<T> function) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= TX_MAX_ATTEMPTS; attempt++) {
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
                    if (isRetryableConflict(e) && attempt < TX_MAX_ATTEMPTS) {
                        last = new RuntimeException("事务执行失败: " + e.getMessage(), e);
                        backoffBeforeRetry(attempt);
                        continue;
                    }
                    throw new RuntimeException("事务执行失败: " + e.getMessage(), e);
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException ignored) {
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("事务执行失败: " + e.getMessage(), e);
            }
        }
        throw last != null ? last : new RuntimeException("事务执行失败");
    }

    /**
     * 判断异常是否属于"回滚后重跑就能成功"的并发冲突。
     *
     * <p>只认三类明确语义的冲突，其余异常一律原样抛出 —— 不能把业务失败
     * （比如"灵石不足"）当成并发冲突去重试。</p>
     */
    private static boolean isRetryableConflict(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("40001".equals(state) || "40P01".equals(state)) {
                    return true; // 序列化失败 / 死锁
                }
                String msg = sql.getMessage();
                if (msg != null) {
                    String upper = msg.toUpperCase(java.util.Locale.ROOT);
                    if (upper.contains("SQLITE_BUSY_SNAPSHOT")
                            || upper.contains("SQLITE_BUSY")
                            || upper.contains("DATABASE IS LOCKED")
                            || upper.contains("DEADLOCK")) {
                        return true;
                    }
                }
            }
            String msg = t.getMessage();
            if (msg != null) {
                String upper = msg.toUpperCase(java.util.Locale.ROOT);
                if (upper.contains("SQLITE_BUSY_SNAPSHOT") || upper.contains("SQLITE_BUSY")) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static void backoffBeforeRetry(int attempt) {
        try {
            Thread.sleep(TX_RETRY_BASE_DELAY_MS * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
            // 装备耐久台账：独立于 players_equipment，卸下装备不会清掉耐久
            stmt.execute(DatabaseSchema.createPlayerItemDurabilityTable(pk, tsDefault, tsUpdate, DIALECT));
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
            // 聊天记录与操作日志：这两张表的索引原本只写在 CREATE TABLE 里、
            // 且只有 MySQL 会执行（SQLite 上一条索引都没有），而 SQLite 是默认数据库。
            createIndexQuietly(stmt, DatabaseSchema.createChatMessagesChannelIndex());
            createIndexQuietly(stmt, DatabaseSchema.createChatMessagesReceiverIndex());
            createIndexQuietly(stmt, DatabaseSchema.createPlayerActionLogsTimeIndex());
            createIndexQuietly(stmt, DatabaseSchema.createPlayerActionLogsUserIndex());
            createIndexQuietly(stmt, DatabaseSchema.createPlayerActionLogsQqIndex());
            createIndexQuietly(stmt, DatabaseSchema.createPlayerItemDurabilityPlayerIndex());

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

    /**
     * 「清除玩家数据」要清空的表（保留 users / 绑定关系等账号信息）。
     *
     * <p>抽成包内可见的常量是为了能被测试直接断言：这张清单曾经漏掉
     * {@code player_item_durability}，导致清空玩家后耐久行成为孤儿，
     * 而 SQLite 的 {@code INTEGER PRIMARY KEY} 会复用被删掉的最大 id，
     * 新玩家于是可能"继承"上一个玩家的装备耐久。</p>
     */
    static final String[] CLEAR_PLAYER_TABLES = {
            "players_skills", "players_techniques", "players_items", "players_equipment",
            "player_daily", "trade_listings", "chat_messages", "friends", "player_action_logs",
            "sect_members", "sect_applications", "sect_warehouse", "sects", "player_energy",
            "players", "player_buffs", "farm_plots", "player_economy", "player_bank",
            "auction_listings", "auction_bids", "player_titles", "caves", "formations",
            "redeemed_codes", "sect_wars", "player_item_durability"
    };

    public static Map<String, Integer> clearPlayerData() {
        Map<String, Integer> result = new LinkedHashMap<>();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            DIALECT.disableForeignKeyChecks(stmt);
            for (String table : CLEAR_PLAYER_TABLES) {
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
                "redeem_codes", "player_item_durability"
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

    /**
     * 表名白名单：硬编码所有合法表名（安全加固）。
     *
     * <p><b>必须与 {@link DatabaseSchema} 实际建的表保持一致</b>。原来少了 5 张：
     * {@code player_item_durability}、{@code token_blacklist}、{@code trade_price_history}、
     * {@code world_bosses}、{@code world_boss_damage} —— 都是后来新增的表，加了建表语句却
     * 忘了同步白名单。后果是这些表在备份/还原、管理后台的通用表操作里被当成"非法表名"直接拒掉，
     * 表现为"某几张表的数据备份不出来"，而且报错信息看起来像安全拦截，很容易被误判。
     * 回归测试：{@code DatabaseTableWhitelistTest}。</p>
     */
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
        "player_energy", "player_titles", "player_buffs", "farm_plots",
        // 以下 5 张是后来新增的表，此前漏登记
        "player_item_durability", "token_blacklist", "trade_price_history",
        "world_bosses", "world_boss_damage",
        // 这两张同样是实测发现漏登记的：user_permissions 由权限模块直接建表
        // （单条权限授予），schema_migrations 由 DatabaseMigration 建表
        "user_permissions", "schema_migrations"
    );

    /** 表名白名单缓存（避免每次校验都查库） */
    private static volatile Set<String> tableNameCache;
    private static volatile long tableNameCacheTime;

    /**
     * 表名白名单校验：仅允许当前数据库中真实存在的表（防 SQL 标识符注入）。
     *
     * <p>凡是把表名拼进 SQL 文本的地方（不能走 {@code ?} 占位符，标识符天生无法参数化）
     * 都必须先调用它。校验分三层：格式（只允许小写字母与下划线）、硬编码白名单、
     * 以及"该表在库里真实存在"。</p>
     *
     * <p>公开给其它拼 SQL 的模块复用（如 {@code PlayerDataService} 的备份还原：
     * 表名与列名都来自外部 JSON 文件，属于不可信输入）。</p>
     *
     * @throws IllegalArgumentException 表名非法、不在白名单或库里不存在
     */
    public static void assertSafeTableName(String tableName) {
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

    /**
     * 列名校验：仅允许纯标识符字符（防 SQL 标识符注入）。
     *
     * <p>与 {@link #assertSafeTableName(String)} 同样公开给其它拼 SQL 的模块复用。</p>
     *
     * @throws IllegalArgumentException 列名含标识符以外的字符
     */
    public static void assertSafeColumnName(String columnName) {
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

    /**
     * 连接池当前是否可用（已初始化且未关闭）。
     *
     * <p>给后台定时任务用：停机与测试收尾阶段连接池已被关闭，
     * 这时再发起查询只会得到 "no such table" / "pool has been shut down" 这类
     * 无意义的报错，把真正的故障淹没在噪音里。</p>
     */
    public static boolean isAvailable() {
        return dataSource != null;
    }

    public static void shutdown() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            logger.info("数据库连接池已关闭");
        }
    }

    /**
     * 当前被借出的连接数（监控与测试用）。
     *
     * @return 借出数；池未初始化时返回 {@code -1}
     */
    public static int getActiveConnectionCount() {
        HikariDataSource ds = dataSource;
        if (ds == null || ds.getHikariPoolMXBean() == null) {
            return -1;
        }
        return ds.getHikariPoolMXBean().getActiveConnections();
    }

    /** 连接池上限（监控与测试用）；池未初始化时返回 {@code -1}。 */
    public static int getMaxPoolSize() {
        HikariDataSource ds = dataSource;
        return ds == null ? -1 : ds.getMaximumPoolSize();
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
    public interface SQLFunction<T> {
        T run(Connection conn) throws Exception;
    }
}

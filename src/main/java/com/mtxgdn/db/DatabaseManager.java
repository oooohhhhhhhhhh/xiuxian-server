package com.mtxgdn.db;

import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DatabaseManager {

    private static final String DB_TYPE = AppConfig.get("database.type", "mysql");
    private static final boolean IS_SQLITE = "sqlite".equalsIgnoreCase(DB_TYPE);

    private static final String DB_URL = IS_SQLITE
            ? "jdbc:sqlite:" + AppConfig.get("database.sqlite_path", "xiuxian.db")
            : AppConfig.get("database.url", "jdbc:mysql://localhost:3306/xiuxian?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci");
    private static final String DB_USER = IS_SQLITE ? "" : AppConfig.get("database.username", "root");
    private static final String DB_PASSWORD = IS_SQLITE ? "" : AppConfig.get("database.password", "root");

    private static volatile HikariDataSource dataSource;

    private static HikariDataSource getDataSource() {
        if (dataSource == null) {
            synchronized (DatabaseManager.class) {
                if (dataSource == null) {
                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(DB_URL);

                    if (IS_SQLITE) {
                        config.setMaximumPoolSize(1);
                        config.setMinimumIdle(1);
                        config.setConnectionTimeout(10000);
                        config.addDataSourceProperty("journal_mode", "WAL");
                        config.addDataSourceProperty("busy_timeout", "5000");
                    } else {
                        config.setUsername(DB_USER);
                        config.setPassword(DB_PASSWORD);
                        config.setMaximumPoolSize(10);
                        config.setMinimumIdle(2);
                        config.setIdleTimeout(30000);
                        config.setConnectionTimeout(10000);
                        config.addDataSourceProperty("cachePrepStmts", "true");
                        config.addDataSourceProperty("prepStmtCacheSize", "250");
                        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                        config.addDataSourceProperty("useUnicode", "true");
                        config.addDataSourceProperty("characterEncoding", "UTF-8");
                        config.setConnectionInitSql("SET NAMES utf8mb4");
                    }

                    dataSource = new HikariDataSource(config);
                }
            }
        }
        return dataSource;
    }

    public static boolean isSqlite() {
        return IS_SQLITE;
    }

    public static Connection getConnection() throws SQLException {
        Connection conn = getDataSource().getConnection();
        if (IS_SQLITE) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
        }
        return conn;
    }

    @FunctionalInterface
    public interface TransactionTask<T> {
        T execute(Connection conn) throws SQLException;
    }

    public static <T> T runTransaction(TransactionTask<T> task) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = task.execute(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("事务执行失败", e);
        }
    }

    public static void initTable() {
        String pkMySql = "BIGINT AUTO_INCREMENT PRIMARY KEY";
        String pkSqlite = "INTEGER PRIMARY KEY AUTOINCREMENT";
        String pk = IS_SQLITE ? pkSqlite : pkMySql;

        String tsDefault = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
        String tsUpdate = IS_SQLITE ? tsDefault : "TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP";
        String boolType = IS_SQLITE ? "INTEGER DEFAULT 0" : "BOOLEAN DEFAULT FALSE";

        String userTableSql = "CREATE TABLE IF NOT EXISTS users (" +
                "id " + pk + ", " +
                "username VARCHAR(64) NOT NULL UNIQUE, " +
                "password VARCHAR(256) NOT NULL, " +
                "email VARCHAR(128) DEFAULT '', " +
                "created_at " + tsDefault +
                ")";

        String techniquesTableSql = "CREATE TABLE IF NOT EXISTS techniques (" +
                "id " + pk + ", " +
                "name VARCHAR(64) NOT NULL, " +
                "description VARCHAR(256) DEFAULT '', " +
                "required_realm INT DEFAULT 0, " +
                "learn_cost_gold BIGINT DEFAULT 0, " +
                "learn_cost_spirit_stones BIGINT DEFAULT 0, " +
                "upgrade_base_cost_gold INT DEFAULT 100, " +
                "upgrade_base_cost_spirit_stones INT DEFAULT 50, " +
                "type VARCHAR(16) NOT NULL DEFAULT 'CULTIVATION', " +
                "max_level INT DEFAULT 10, " +
                "hp_bonus INT DEFAULT 0, " +
                "mp_bonus INT DEFAULT 0, " +
                "attack_bonus INT DEFAULT 0, " +
                "defense_bonus INT DEFAULT 0, " +
                "speed_bonus INT DEFAULT 0, " +
                "spirit_bonus INT DEFAULT 0, " +
                "cultivation_speed_bonus DOUBLE DEFAULT 0, " +
                "exp_bonus DOUBLE DEFAULT 0, " +
                "combat_damage_bonus DOUBLE DEFAULT 0, " +
                "damage_reduction DOUBLE DEFAULT 0, " +
                "created_at " + tsDefault +
                ")";

        String playersTechniquesTableSql = "CREATE TABLE IF NOT EXISTS players_techniques (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "technique_id BIGINT NOT NULL, " +
                "level INT NOT NULL DEFAULT 1, " +
                "proficiency INT DEFAULT 0, " +
                "is_equipped " + (IS_SQLITE ? "INTEGER DEFAULT 0" : "BOOLEAN DEFAULT FALSE") + ", " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (IS_SQLITE ? "" : "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, FOREIGN KEY (technique_id) REFERENCES techniques(id) ON DELETE CASCADE, ") +
                "UNIQUE (player_id, technique_id)" +
                ")";

        String recipesTableSql = "CREATE TABLE IF NOT EXISTS recipes (" +
                "id " + pk + ", " +
                "name VARCHAR(64) NOT NULL, " +
                "description VARCHAR(256) DEFAULT '', " +
                "category VARCHAR(16) NOT NULL DEFAULT 'PILL', " +
                "required_realm INT DEFAULT 0, " +
                "result_item_key VARCHAR(128) NOT NULL, " +
                "result_quantity INT DEFAULT 1, " +
                "material1_key VARCHAR(128), " +
                "material1_count INT DEFAULT 0, " +
                "material2_key VARCHAR(128), " +
                "material2_count INT DEFAULT 0, " +
                "material3_key VARCHAR(128), " +
                "material3_count INT DEFAULT 0, " +
                "cost_gold BIGINT DEFAULT 0, " +
                "cost_spirit_stones BIGINT DEFAULT 0, " +
                "success_rate DOUBLE DEFAULT 0.8, " +
                "min_exp_gain BIGINT DEFAULT 0, " +
                "max_exp_gain BIGINT DEFAULT 0, " +
                "created_at " + tsDefault +
                ")";

        String verificationCodesTableSql = "CREATE TABLE IF NOT EXISTS verification_codes (" +
                "id " + pk + ", " +
                "email VARCHAR(128) NOT NULL, " +
                "code VARCHAR(6) NOT NULL, " +
                "expires_at TIMESTAMP NOT NULL, " +
                "created_at " + tsDefault +
                ")";

        String playersTableSql = "CREATE TABLE IF NOT EXISTS players (" +
                "id " + pk + ", " +
                "user_id BIGINT NOT NULL UNIQUE, " +
                "name VARCHAR(64) NOT NULL, " +
                "spiritual_root VARCHAR(16) DEFAULT 'FIRE', " +
                "level INT DEFAULT 1, " +
                "experience BIGINT DEFAULT 0, " +
                "realm INT DEFAULT 0, " +
                "sub_realm INT DEFAULT 0, " +
                "hp INT DEFAULT 100, " +
                "max_hp INT DEFAULT 100, " +
                "mp INT DEFAULT 50, " +
                "max_mp INT DEFAULT 50, " +
                "attack INT DEFAULT 10, " +
                "defense INT DEFAULT 5, " +
                "speed INT DEFAULT 5, " +
                "spirit INT DEFAULT 5, " +
                "gold BIGINT DEFAULT 0, " +
                "cultivation_progress INT DEFAULT 0, " +
                "is_cultivating " + boolType + ", " +
                "cultivation_start_time BIGINT DEFAULT 0, " +
                "last_secret_realm_time BIGINT DEFAULT 0, " +
                "last_exploration_time BIGINT DEFAULT 0, " +
                "tutorial_step INT DEFAULT 0, " +
                "tutorial_tips INT DEFAULT 0, " +
                "last_offline_time BIGINT DEFAULT 0, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate;

        if (!IS_SQLITE) {
            playersTableSql += ", FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE";
        }
        playersTableSql += ")";

        String playersItemsTableSql = "CREATE TABLE IF NOT EXISTS players_items (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "quantity INT NOT NULL DEFAULT 1, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (IS_SQLITE ? "" : "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, ") +
                "UNIQUE (player_id, item_key)" +
                ")";

        String playersEquipmentTableSql = "CREATE TABLE IF NOT EXISTS players_equipment (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "slot VARCHAR(32) NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "enhance_level INT DEFAULT 0, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (IS_SQLITE ? "" : "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, ") +
                "UNIQUE (player_id, slot)" +
                ")";

        String skillsTableSql = "CREATE TABLE IF NOT EXISTS skills (" +
                "id " + pk + ", " +
                "name VARCHAR(64) NOT NULL, " +
                "description VARCHAR(256) DEFAULT '', " +
                "required_realm INT DEFAULT 0, " +
                "learn_cost_gold BIGINT DEFAULT 0, " +
                "learn_cost_spirit_stones BIGINT DEFAULT 0, " +
                "damage INT DEFAULT 0, " +
                "mp_cost INT DEFAULT 0, " +
                "cooldown_seconds INT DEFAULT 0, " +
                "skill_type INT DEFAULT 0, " +
                "heal_amount INT DEFAULT 0, " +
                "max_level INT DEFAULT 10, " +
                "created_at " + tsDefault +
                ")";

        String playersSkillsTableSql = "CREATE TABLE IF NOT EXISTS players_skills (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "skill_id BIGINT NOT NULL, " +
                "level INT NOT NULL DEFAULT 1, " +
                "proficiency INT DEFAULT 0, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (IS_SQLITE ? "" : "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, FOREIGN KEY (skill_id) REFERENCES skills(id) ON DELETE CASCADE, ") +
                "UNIQUE (player_id, skill_id)" +
                ")";

        String tradeListingsTableSql = "CREATE TABLE IF NOT EXISTS trade_listings (" +
                "id " + pk + ", " +
                "seller_player_id BIGINT NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "quantity INT NOT NULL, " +
                "price_spirit_stones BIGINT NOT NULL, " +
                "fee BIGINT NOT NULL DEFAULT 0, " +
                "status VARCHAR(16) DEFAULT 'active', " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                (IS_SQLITE ? "" : ", FOREIGN KEY (seller_player_id) REFERENCES players(id) ON DELETE CASCADE") +
                ")";

        String playerDailyTableSql = "CREATE TABLE IF NOT EXISTS player_daily (" +
                "player_id BIGINT PRIMARY KEY, " +
                "last_morning_cultivation VARCHAR(16), " +
                "consecutive_days INT DEFAULT 0, " +
                "total_active_days INT DEFAULT 0, " +
                "last_daily_reset DATE DEFAULT (CURRENT_DATE), " +
                "exploration_count INT DEFAULT 0, " +
                "battle_count INT DEFAULT 0, " +
                "secret_realm_count INT DEFAULT 0, " +
                "skill_learn_count INT DEFAULT 0, " +
                "exploration_rewarded INT DEFAULT 0, " +
                "battle_rewarded INT DEFAULT 0, " +
                "secret_realm_rewarded INT DEFAULT 0, " +
                "skill_learn_rewarded INT DEFAULT 0, " +
                "resonance7_awarded INT DEFAULT 0, " +
                "resonance30_awarded INT DEFAULT 0" +
                (IS_SQLITE ? "" : ", FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE") +
                ")";

        String qqBindingsTableSql = "CREATE TABLE IF NOT EXISTS qq_bindings (" +
                "id " + pk + ", " +
                "qq_number VARCHAR(32) NOT NULL UNIQUE, " +
                "user_id BIGINT NOT NULL UNIQUE, " +
                "created_at " + tsDefault +
                (IS_SQLITE ? "" : ", FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE") +
                ")";

        String rolesTableSql = "CREATE TABLE IF NOT EXISTS roles (" +
                "id " + pk + ", " +
                "name VARCHAR(32) NOT NULL UNIQUE, " +
                "display_name VARCHAR(64) NOT NULL, " +
                "level INT NOT NULL, " +
                "created_at " + tsDefault +
                ")";

        String permissionsTableSql = "CREATE TABLE IF NOT EXISTS permissions (" +
                "id " + pk + ", " +
                "code VARCHAR(64) NOT NULL UNIQUE, " +
                "name VARCHAR(64) NOT NULL, " +
                "category VARCHAR(32) NOT NULL, " +
                "created_at " + tsDefault +
                ")";

        String rolePermissionsTableSql = "CREATE TABLE IF NOT EXISTS role_permissions (" +
                "id " + pk + ", " +
                "role_name VARCHAR(32) NOT NULL, " +
                "permission_code VARCHAR(64) NOT NULL, " +
                "created_at " + tsDefault + ", " +
                "UNIQUE (role_name, permission_code)" +
                ")";

        String userRolesTableSql = "CREATE TABLE IF NOT EXISTS user_roles (" +
                "id " + pk + ", " +
                "user_id BIGINT NOT NULL, " +
                "role_name VARCHAR(32) NOT NULL, " +
                "created_at " + tsDefault + ", " +
                (IS_SQLITE ? "" : "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, ") +
                "UNIQUE (user_id, role_name)" +
                ")";

        String chatMessagesTableSql = "CREATE TABLE IF NOT EXISTS chat_messages (" +
                "id " + pk + ", " +
                "channel VARCHAR(16) NOT NULL DEFAULT 'world', " +
                "sender_player_id BIGINT NOT NULL, " +
                "receiver_player_id BIGINT DEFAULT NULL, " +
                "content VARCHAR(1024) NOT NULL, " +
                "created_at " + tsDefault +
                (IS_SQLITE ? "" : ", INDEX idx_chat_channel (channel), INDEX idx_chat_receiver (receiver_player_id)") +
                ")";

        String friendsTableSql = "CREATE TABLE IF NOT EXISTS friends (" +
                "id " + pk + ", " +
                "player_id BIGINT NOT NULL, " +
                "friend_player_id BIGINT NOT NULL, " +
                "status VARCHAR(16) NOT NULL DEFAULT 'pending', " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate + ", " +
                (IS_SQLITE ? "" : "FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, FOREIGN KEY (friend_player_id) REFERENCES players(id) ON DELETE CASCADE, ") +
                "UNIQUE (player_id, friend_player_id)" +
                ")";

        String playerActionLogsTableSql = "CREATE TABLE IF NOT EXISTS player_action_logs (" +
                "id " + pk + ", " +
                "user_id BIGINT DEFAULT NULL, " +
                "player_name VARCHAR(64) DEFAULT '', " +
                "action VARCHAR(64) NOT NULL, " +
                "detail VARCHAR(1024) DEFAULT '', " +
                "qq_number VARCHAR(32) DEFAULT NULL, " +
                "created_at " + tsDefault +
                ")";

        String sectsTableSql = "CREATE TABLE IF NOT EXISTS sects (" +
                "id " + pk + ", " +
                "name VARCHAR(32) NOT NULL UNIQUE, " +
                "description VARCHAR(256) DEFAULT '', " +
                "leader_player_id BIGINT NOT NULL, " +
                "level INT DEFAULT 1, " +
                "prestige BIGINT DEFAULT 0, " +
                "max_members INT DEFAULT 20, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate;

        if (!IS_SQLITE) {
            sectsTableSql += ", FOREIGN KEY (leader_player_id) REFERENCES players(id) ON DELETE CASCADE";
        }
        sectsTableSql += ")";

        String sectMembersTableSql = "CREATE TABLE IF NOT EXISTS sect_members (" +
                "id " + pk + ", " +
                "sect_id BIGINT NOT NULL, " +
                "player_id BIGINT NOT NULL, " +
                "role VARCHAR(16) NOT NULL DEFAULT 'MEMBER', " +
                "contribution BIGINT DEFAULT 0, " +
                "joined_at " + tsDefault;

        if (!IS_SQLITE) {
            sectMembersTableSql += ", FOREIGN KEY (sect_id) REFERENCES sects(id) ON DELETE CASCADE, FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, UNIQUE (player_id)";
        } else {
            sectMembersTableSql += ", UNIQUE (player_id)";
        }
        sectMembersTableSql += ")";

        String sectApplicationsTableSql = "CREATE TABLE IF NOT EXISTS sect_applications (" +
                "id " + pk + ", " +
                "sect_id BIGINT NOT NULL, " +
                "player_id BIGINT NOT NULL, " +
                "message VARCHAR(256) DEFAULT '', " +
                "status VARCHAR(16) NOT NULL DEFAULT 'pending', " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate;

        if (!IS_SQLITE) {
            sectApplicationsTableSql += ", FOREIGN KEY (sect_id) REFERENCES sects(id) ON DELETE CASCADE, FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE";
        }
        sectApplicationsTableSql += ")";

        String sectWarehouseTableSql = "CREATE TABLE IF NOT EXISTS sect_warehouse (" +
                "id " + pk + ", " +
                "sect_id BIGINT NOT NULL, " +
                "item_key VARCHAR(128) NOT NULL, " +
                "quantity INT NOT NULL DEFAULT 1, " +
                "donated_by_player_id BIGINT DEFAULT NULL, " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate;

        if (!IS_SQLITE) {
            sectWarehouseTableSql += ", FOREIGN KEY (sect_id) REFERENCES sects(id) ON DELETE CASCADE, UNIQUE (sect_id, item_key)";
        } else {
            sectWarehouseTableSql += ", UNIQUE (sect_id, item_key)";
        }
        sectWarehouseTableSql += ")";

        String redeemCodesTableSql = "CREATE TABLE IF NOT EXISTS redeem_codes (" +
                "id " + pk + ", " +
                "code VARCHAR(32) NOT NULL UNIQUE, " +
                "name VARCHAR(64) DEFAULT '', " +
                "items_json TEXT, " +
                "gold BIGINT DEFAULT 0, " +
                "spirit_stones BIGINT DEFAULT 0, " +
                "exp BIGINT DEFAULT 0, " +
                "max_uses INT DEFAULT 1, " +
                "current_uses INT DEFAULT 0, " +
                "status VARCHAR(16) DEFAULT 'active', " +
                "expires_at TIMESTAMP NULL, " +
                "created_by VARCHAR(64) DEFAULT '', " +
                "created_at " + tsDefault + ", " +
                "updated_at " + tsUpdate +
                ")";

        String redeemedCodesTableSql = "CREATE TABLE IF NOT EXISTS redeemed_codes (" +
                "id " + pk + ", " +
                "code_id BIGINT NOT NULL, " +
                "player_id BIGINT NOT NULL, " +
                "redeemed_at " + tsDefault;

        if (!IS_SQLITE) {
            redeemedCodesTableSql += ", FOREIGN KEY (code_id) REFERENCES redeem_codes(id) ON DELETE CASCADE, FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE, UNIQUE (code_id, player_id)";
        } else {
            redeemedCodesTableSql += ", UNIQUE (code_id, player_id)";
        }
        redeemedCodesTableSql += ")";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(userTableSql);
            stmt.execute(verificationCodesTableSql);
            stmt.execute(playersTableSql);
            stmt.execute(playersItemsTableSql);
            stmt.execute(playersEquipmentTableSql);
            stmt.execute(skillsTableSql);
            stmt.execute(playersSkillsTableSql);
            stmt.execute(techniquesTableSql);
            stmt.execute(playersTechniquesTableSql);
            stmt.execute(recipesTableSql);
            stmt.execute(tradeListingsTableSql);
            stmt.execute(playerDailyTableSql);
            stmt.execute(qqBindingsTableSql);
            stmt.execute(rolesTableSql);
            stmt.execute(permissionsTableSql);
            stmt.execute(rolePermissionsTableSql);
            stmt.execute(userRolesTableSql);
            stmt.execute(chatMessagesTableSql);
            stmt.execute(friendsTableSql);
            stmt.execute(playerActionLogsTableSql);
            stmt.execute(sectsTableSql);
            stmt.execute(sectMembersTableSql);
            stmt.execute(sectApplicationsTableSql);
            stmt.execute(sectWarehouseTableSql);
            stmt.execute(redeemCodesTableSql);
            stmt.execute(redeemedCodesTableSql);
        } catch (SQLException e) {
            throw new RuntimeException("创建数据库表失败", e);
        }

        PermissionService.initDefaultData();
        migrateColumns();
    }

    private static void migrateColumns() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("ALTER TABLE players_equipment ADD COLUMN enhance_level INT DEFAULT 0");
            } catch (SQLException ignored) {
            }
            // 兑换码 is_active -> status 迁移
            try {
                stmt.execute("ALTER TABLE redeem_codes ADD COLUMN status VARCHAR(16) DEFAULT 'active'");
            } catch (SQLException ignored) {
            }
            // 尝试把旧 is_active 数据迁移到 status（仅 SQLite 不支持的 ALTER DROP 忽略即可）
            try {
                if (!IS_SQLITE) {
                    stmt.execute("UPDATE redeem_codes SET status = CASE WHEN is_active THEN 'active' ELSE 'disabled' END WHERE status = 'active'");
                    stmt.execute("ALTER TABLE redeem_codes DROP COLUMN is_active");
                }
            } catch (SQLException ignored) {
            }
        } catch (SQLException e) {
            throw new RuntimeException("数据库迁移失败", e);
        }
    }

    public static Map<String, Integer> clearPlayerData() {
        Map<String, Integer> result = new LinkedHashMap<>();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            if (!IS_SQLITE) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
            }
            int ps = stmt.executeUpdate("DELETE FROM players_skills");
            int pt = stmt.executeUpdate("DELETE FROM players_techniques");
            int pi = stmt.executeUpdate("DELETE FROM players_items");
            int pe = stmt.executeUpdate("DELETE FROM players_equipment");
            int pd = stmt.executeUpdate("DELETE FROM player_daily");
            int tl = stmt.executeUpdate("DELETE FROM trade_listings");
            int cm = stmt.executeUpdate("DELETE FROM chat_messages");
            int fr = stmt.executeUpdate("DELETE FROM friends");
            int pal = stmt.executeUpdate("DELETE FROM player_action_logs");
            int sm = stmt.executeUpdate("DELETE FROM sect_members");
            int sa = stmt.executeUpdate("DELETE FROM sect_applications");
            int sw = stmt.executeUpdate("DELETE FROM sect_warehouse");
            int sc = stmt.executeUpdate("DELETE FROM sects");
            int pl = stmt.executeUpdate("DELETE FROM players");
            if (!IS_SQLITE) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
            }

            result.put("players_skills", ps);
            result.put("players_techniques", pt);
            result.put("players_items", pi);
            result.put("players_equipment", pe);
            result.put("player_daily", pd);
            result.put("trade_listings", tl);
            result.put("chat_messages", cm);
            result.put("friends", fr);
            result.put("player_action_logs", pal);
            result.put("sect_members", sm);
            result.put("sect_applications", sa);
            result.put("sect_warehouse", sw);
            result.put("sects", sc);
            result.put("players", pl);
        } catch (SQLException e) {
            throw new RuntimeException("清除玩家数据失败", e);
        }

        return result;
    }

    public static Map<String, Integer> resetAllData() {
        Map<String, Integer> result = new LinkedHashMap<>();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            if (!IS_SQLITE) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
            }
            int ps = stmt.executeUpdate("DELETE FROM players_skills");
            int pt = stmt.executeUpdate("DELETE FROM players_techniques");
            int pi = stmt.executeUpdate("DELETE FROM players_items");
            int pe = stmt.executeUpdate("DELETE FROM players_equipment");
            int pd = stmt.executeUpdate("DELETE FROM player_daily");
            int tl = stmt.executeUpdate("DELETE FROM trade_listings");
            int cm = stmt.executeUpdate("DELETE FROM chat_messages");
            int fr = stmt.executeUpdate("DELETE FROM friends");
            int pal = stmt.executeUpdate("DELETE FROM player_action_logs");
            int sm2 = stmt.executeUpdate("DELETE FROM sect_members");
            int sa2 = stmt.executeUpdate("DELETE FROM sect_applications");
            int sw2 = stmt.executeUpdate("DELETE FROM sect_warehouse");
            int sc2 = stmt.executeUpdate("DELETE FROM sects");
            int pl = stmt.executeUpdate("DELETE FROM players");
            int qb = stmt.executeUpdate("DELETE FROM qq_bindings");
            int ur = stmt.executeUpdate("DELETE FROM user_roles");
            int rp = stmt.executeUpdate("DELETE FROM role_permissions");
            int roles = stmt.executeUpdate("DELETE FROM roles");
            int perm = stmt.executeUpdate("DELETE FROM permissions");
            int vc = stmt.executeUpdate("DELETE FROM verification_codes");
            if (!IS_SQLITE) {
                stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
            }

            result.put("players_skills", ps);
            result.put("players_techniques", pt);
            result.put("players_items", pi);
            result.put("players_equipment", pe);
            result.put("player_daily", pd);
            result.put("trade_listings", tl);
            result.put("chat_messages", cm);
            result.put("friends", fr);
            result.put("player_action_logs", pal);
            result.put("sect_members", sm2);
            result.put("sect_applications", sa2);
            result.put("sect_warehouse", sw2);
            result.put("sects", sc2);
            result.put("players", pl);
            result.put("qq_bindings", qb);
            result.put("user_roles", ur);
            result.put("role_permissions", rp);
            result.put("roles", roles);
            result.put("permissions", perm);
            result.put("verification_codes", vc);
        } catch (SQLException e) {
            throw new RuntimeException("重置数据库失败", e);
        }

        PermissionService.initDefaultData();
        return result;
    }

    public static void insertPlayerActionLog(long userId, String playerName, String action, String detail, String qqNumber) {
        String sql = "INSERT INTO player_action_logs (user_id, player_name, action, detail, qq_number) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, playerName != null ? playerName : "");
            ps.setString(3, action);
            ps.setString(4, detail != null ? detail : "");
            ps.setString(5, qqNumber != null ? qqNumber : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("插入玩家操作日志失败", e);
        }
    }

    public static List<Map<String, Object>> queryPlayerActionLogs(Long userId, String playerName, String action,
                                                                   String qqNumber, String startTime, String endTime,
                                                                   int limit, int offset) {
        List<Map<String, Object>> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM player_action_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (playerName != null && !playerName.isBlank()) {
            sql.append(" AND player_name LIKE ?");
            params.add("%" + playerName + "%");
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action LIKE ?");
            params.add("%" + action + "%");
        }
        if (qqNumber != null && !qqNumber.isBlank()) {
            sql.append(" AND qq_number = ?");
            params.add(qqNumber);
        }
        if (startTime != null && !startTime.isBlank()) {
            sql.append(" AND created_at >= ?");
            params.add(startTime);
        }
        if (endTime != null && !endTime.isBlank()) {
            sql.append(" AND created_at <= ?");
            params.add(endTime);
        }

        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("userId", rs.getLong("user_id"));
                    row.put("playerName", rs.getString("player_name"));
                    row.put("action", rs.getString("action"));
                    row.put("detail", rs.getString("detail"));
                    row.put("qqNumber", rs.getString("qq_number"));
                    row.put("createdAt", rs.getTimestamp("created_at").toString());
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询玩家操作日志失败", e);
        }
        return result;
    }

    public static int countPlayerActionLogs(Long userId, String playerName, String action,
                                             String qqNumber, String startTime, String endTime) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM player_action_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (playerName != null && !playerName.isBlank()) {
            sql.append(" AND player_name LIKE ?");
            params.add("%" + playerName + "%");
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action LIKE ?");
            params.add("%" + action + "%");
        }
        if (qqNumber != null && !qqNumber.isBlank()) {
            sql.append(" AND qq_number = ?");
            params.add(qqNumber);
        }
        if (startTime != null && !startTime.isBlank()) {
            sql.append(" AND created_at >= ?");
            params.add(startTime);
        }
        if (endTime != null && !endTime.isBlank()) {
            sql.append(" AND created_at <= ?");
            params.add(endTime);
        }

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("统计玩家操作日志失败", e);
        }
        return 0;
    }

    // ========== 通用数据库浏览 ==========

    /**
     * 获取所有表名
     */
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
                throw new RuntimeException("获取表名失败", e);
            }
        } else {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW TABLES")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            } catch (SQLException e) {
                throw new RuntimeException("获取表名失败", e);
            }
        }
        return names;
    }

    /**
     * 获取表的列信息
     */
    public static List<Map<String, Object>> getTableColumns(String tableName) {
        validateTableName(tableName);
        List<Map<String, Object>> columns = new ArrayList<>();
        if (IS_SQLITE) {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA table_info(\"" + tableName + "\")")) {
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", rs.getString("name"));
                    col.put("type", rs.getString("type"));
                    col.put("notnull", rs.getInt("notnull") == 1);
                    col.put("pk", rs.getInt("pk") == 1);
                    columns.add(col);
                }
            } catch (SQLException e) {
                throw new RuntimeException("获取表结构失败: " + tableName, e);
            }
        } else {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("DESCRIBE `" + tableName + "`")) {
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", rs.getString("Field"));
                    col.put("type", rs.getString("Type"));
                    col.put("notnull", "NO".equalsIgnoreCase(rs.getString("Null")));
                    col.put("pk", "PRI".equalsIgnoreCase(rs.getString("Key")));
                    columns.add(col);
                }
            } catch (SQLException e) {
                throw new RuntimeException("获取表结构失败: " + tableName, e);
            }
        }
        return columns;
    }

    /**
     * 查询表数据（分页）
     */
    public static List<Map<String, Object>> queryTableData(String tableName, int limit, int offset) {
        validateTableName(tableName);
        String sql = IS_SQLITE
                ? "SELECT * FROM \"" + tableName + "\" LIMIT ? OFFSET ?"
                : "SELECT * FROM `" + tableName + "` LIMIT ? OFFSET ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                var meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        row.put(meta.getColumnName(i), val);
                    }
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询表数据失败: " + tableName, e);
        }
        return rows;
    }

    /**
     * 统计表行数
     */
    public static int countTableRows(String tableName) {
        validateTableName(tableName);
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(IS_SQLITE
                     ? "SELECT COUNT(*) FROM \"" + tableName + "\""
                     : "SELECT COUNT(*) FROM `" + tableName + "`")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("统计行数失败: " + tableName, e);
        }
        return 0;
    }

    /**
     * 获取单行数据
     */
    public static Map<String, Object> getRowById(String tableName, long id) {
        validateTableName(tableName);
        String sql = IS_SQLITE
                ? "SELECT * FROM \"" + tableName + "\" WHERE id = ?"
                : "SELECT * FROM `" + tableName + "` WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    var meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnName(i), rs.getObject(i));
                    }
                    return row;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询行数据失败: " + tableName + " id=" + id, e);
        }
        return null;
    }

    /**
     * 插入新行
     */
    public static long insertRow(String tableName, Map<String, Object> data) {
        validateTableName(tableName);
        if (data.isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }

        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        List<Object> params = new ArrayList<>();

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (cols.length() > 0) {
                cols.append(", ");
                vals.append(", ");
            }
            cols.append(IS_SQLITE ? "\"" + entry.getKey() + "\"" : "`" + entry.getKey() + "`");
            vals.append("?");
            params.add(entry.getValue());
        }

        String sql = IS_SQLITE
                ? "INSERT INTO \"" + tableName + "\" (" + cols + ") VALUES (" + vals + ")"
                : "INSERT INTO `" + tableName + "` (" + cols + ") VALUES (" + vals + ")";

        try (Connection conn = getConnection()) {
            if (IS_SQLITE) {
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                    }
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("插入数据失败: " + tableName, e);
        }
        return -1;
    }

    /**
     * 更新行
     */
    public static int updateRow(String tableName, long id, Map<String, Object> data) {
        validateTableName(tableName);
        if (data.isEmpty()) {
            throw new IllegalArgumentException("数据不能为空");
        }

        StringBuilder setClause = new StringBuilder();
        List<Object> params = new ArrayList<>();

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (setClause.length() > 0) {
                setClause.append(", ");
            }
            setClause.append(IS_SQLITE ? "\"" + entry.getKey() + "\" = ?" : "`" + entry.getKey() + "` = ?");
            params.add(entry.getValue());
        }
        params.add(id);

        String sql = IS_SQLITE
                ? "UPDATE \"" + tableName + "\" SET " + setClause + " WHERE id = ?"
                : "UPDATE `" + tableName + "` SET " + setClause + " WHERE id = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新数据失败: " + tableName + " id=" + id, e);
        }
    }

    /**
     * 删除行
     */
    public static int deleteRow(String tableName, long id) {
        validateTableName(tableName);
        String sql = IS_SQLITE
                ? "DELETE FROM \"" + tableName + "\" WHERE id = ?"
                : "DELETE FROM `" + tableName + "` WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除数据失败: " + tableName + " id=" + id, e);
        }
    }

    /**
     * 验证表名是否存在于数据库中
     */
    private static void validateTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        // 只允许字母、数字和下划线
        if (!tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("无效的表名: " + tableName);
        }
    }
}
